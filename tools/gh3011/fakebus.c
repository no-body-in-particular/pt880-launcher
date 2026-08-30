/* Run the vendor's saturation algorithm against a chip that is not there.
 *
 * The algorithm is not hardwired to the sensor. It reads registers through a function pointer -
 * Goodix's SDK has the caller register bus callbacks - and that pointer lives in .bss where we can
 * reach it once dlopen has mapped the image:
 *
 *     FUN_00016ee0(reg):  splits reg into two bytes, then
 *                         (*callback)(handle, addr, 2, out, 2)
 *     callback pointer at Ghidra 0x3e994, handle pointer at 0x3d53c
 *
 * So the sensor can be replaced with a function of ours. Point the callback at a routine that
 * answers register reads out of a waveform ppgd recorded earlier, and the vendor's code runs on
 * our data with no chip involved, no daemon, and nothing to contend over. That is the second
 * opinion four of our own estimators cannot give each other, because they share every assumption
 * and this shares none of them.
 *
 * Ghidra loads a PIE at 0x10000, so every address it prints is that much above the offset from
 * the load base. Checked against the bytes rather than assumed - the routine's first instruction
 * is push {r4,r5,r6,r7,lr}, f0 b5 in Thumb - because getting this wrong once already produced
 * three crashes and three wrong explanations for them.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#include <signal.h>
#include <ucontext.h>

#define VENDOR "/system/bin/gh3011_service.real"

#define GHIDRA_BASE  0x10000
#define OFF(x)       ((x) - GHIDRA_BASE)

#define SPO2_FN      OFF(0x1b7c0)   /* the saturation routine */
#define MODE_BYTE    OFF(0x3ec4a)   /* must read 1 or 7 or it returns at once */
#define READ_CB      OFF(0x3e994)   /* the register-read callback it goes through */
#define BUS_HANDLE   OFF(0x3d53c)   /* first argument handed to that callback */
#define FRAME_WIDTH  OFF(0x3ec4e)   /* samples per frame; divided by, so zero is fatal */

typedef void (*spo2_fn)(void *, int, unsigned, unsigned char *, unsigned char *,
                        unsigned char *, unsigned char *, unsigned char *,
                        unsigned short *, void *, unsigned short *);

/* The waveform standing in for the sensor. */
static int wave1[24000], wave2[24000];
static int wave_n, wave_at;
static int reads;

/* Answer a register read the way the chip would.
 *
 * Only two registers matter to the part of the algorithm being exercised: 0x004a says how many
 * samples are waiting, and 0xaaaa is the FIFO itself, three bytes big-endian per sample with the
 * two channels interleaved. Everything else is answered with something harmless rather than
 * refused, because a plausible answer keeps the code walking forward and a refusal stops it
 * somewhere uninformative.
 */
static int fake_read(void *handle, unsigned char *addr, int alen,
                     unsigned char *out, int olen)
{
    unsigned int reg;
    (void) handle;

    if (!addr || alen < 2 || !out || olen < 1) return -1;
    reg = ((unsigned) addr[0] << 8) | addr[1];
    reads++;

    memset(out, 0, olen);

    if (reg == 0x004a) {
        /* Samples waiting. Kept modest so it asks repeatedly rather than draining in one go. */
        int left = wave_n - wave_at;
        int lvl = left > 64 ? 64 : (left < 0 ? 0 : left);
        out[0] = (unsigned char)(lvl >> 8);
        if (olen > 1) out[1] = (unsigned char) lvl;
        return 0;
    }

    if (reg == 0xaaaa) {
        /* The FIFO. Three bytes a sample, big-endian, the channels alternating. */
        int i;
        for (i = 0; i + 2 < olen && wave_at < wave_n; i += 3) {
            int v = ((i / 3) & 1) ? wave2[wave_at] : wave1[wave_at];
            out[i]     = (unsigned char)((v >> 16) & 0xff);
            out[i + 1] = (unsigned char)((v >> 8) & 0xff);
            out[i + 2] = (unsigned char)(v & 0xff);
            if ((i / 3) & 1) wave_at++;
        }
        return 0;
    }

    if (reg == 0x0008) { out[0] = 0x00; if (olen > 1) out[1] = 0x02; return 0; }  /* running */
    if (reg == 0x0028) { out[0] = 0x00; if (olen > 1) out[1] = 0x31; return 0; }  /* chip id */

    return 0;
}

/* Report where it died rather than decompiling towards it.
 *
 * Chasing this function by function is slow and each step only finds the next registered pointer.
 * The fault itself says which address was touched and which instruction touched it, and the
 * instruction minus the load base is the offset to look up - which turns an afternoon of
 * decompiling into one lookup.
 */
static unsigned long g_base;

static void on_segv(int sig, siginfo_t *si, void *uc)
{
    ucontext_t *u = (ucontext_t *) uc;
    unsigned long pc = 0;

    (void) sig;
#ifdef __arm__
    pc = (unsigned long) u->uc_mcontext.arm_pc;
    /* The link register is the caller, and when the fault address equals the pc - a jump into
     * nowhere rather than a bad read - the caller is the only thing that identifies which
     * unregistered callback was called. */
    {
        unsigned long lr = (unsigned long) u->uc_mcontext.arm_lr;
        fprintf(stderr, "  called from lr 0x%lx", lr);
        if (g_base && lr > g_base && lr - g_base < 0x40000) {
            fprintf(stderr, " = Ghidra 0x%lx", lr - g_base + GHIDRA_BASE);
        }
        fprintf(stderr, "\n");
    }
#endif
    fprintf(stderr, "\nfaulted touching %p\n", si ? si->si_addr : 0);
    if (pc) {
        fprintf(stderr, "  at pc 0x%lx", pc);
        if (g_base && pc > g_base) {
            fprintf(stderr, "  = offset 0x%lx in the image, so Ghidra address 0x%lx",
                    pc - g_base, pc - g_base + GHIDRA_BASE);
        }
        fprintf(stderr, "\n");
    }
    _exit(3);
}

static unsigned long base_of(const char *needle)
{
    FILE *f = fopen("/proc/self/maps", "r");
    char line[512];
    unsigned long best = 0;

    if (!f) return 0;
    while (fgets(line, sizeof line, f)) {
        if (!strstr(line, needle)) continue;
        {
            unsigned long lo = strtoul(line, NULL, 16);
            if (!best || lo < best) best = lo;
        }
    }
    fclose(f);
    return best;
}

/* .bss is mapped read-write already, but the pages holding relocated pointers may have been made
 * read-only after relocation. Ask for write and carry on either way. */
static void make_writable(void *p)
{
    long ps = sysconf(_SC_PAGESIZE);
    void *page = (void *)((unsigned long) p & ~(ps - 1));
    mprotect(page, ps, PROT_READ | PROT_WRITE);
}

int main(int argc, char **argv)
{
    static int mixed[48000];
    unsigned char result = 0, level = 0, status = 0, a = 0, b = 0;
    unsigned short c = 0, e = 0;
    unsigned char scratch[512];
    unsigned long base;
    void *h;
    spo2_fn fn;
    FILE *w;
    double fs = 0;
    int i, mode = argc > 2 ? atoi(argv[2]) : 2;

    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { printf("usage: fakebus <waveform> [mode]\n"); return 1; }

    w = fopen(argv[1], "r");
    if (!w) { printf("cannot read %s\n", argv[1]); return 1; }
    if (fscanf(w, "%lf", &fs) != 1) { fclose(w); printf("not a waveform\n"); return 1; }
    while (wave_n < 24000 && fscanf(w, "%d %d", &wave1[wave_n], &wave2[wave_n]) == 2) wave_n++;
    fclose(w);
    printf("waveform: %d samples at %.1f Hz\n", wave_n, fs);
    if (wave_n < 200) { printf("too short\n"); return 1; }

    h = dlopen(VENDOR, RTLD_NOW);
    if (!h) { printf("dlopen refused: %s\n", dlerror()); return 1; }
    base = base_of("gh3011_service.real");
    if (!base) { printf("no mapping\n"); return 1; }
    printf("mapped at 0x%lx\n", base);

    g_base = base;
    {
        struct sigaction sa;
        memset(&sa, 0, sizeof sa);
        sa.sa_sigaction = on_segv;
        sa.sa_flags = SA_SIGINFO;
        sigaction(SIGSEGV, &sa, NULL);
        sigaction(SIGBUS, &sa, NULL);
    }

    {
        const unsigned char *raw = (const unsigned char *) base;
        printf("prologue at the routine: %02x %02x (want f0 b5)\n",
               raw[SPO2_FN], raw[SPO2_FN + 1]);
    }

    /* Take over the bus. */
    {
        void **cb = (void **)(base + READ_CB);
        void **handle = (void **)(base + BUS_HANDLE);
        unsigned char *mode_byte = (unsigned char *)(base + MODE_BYTE);
        static unsigned char dummy_handle[64];

        make_writable(cb);
        make_writable(handle);
        make_writable(mode_byte);

        printf("read callback was %p", *cb);
        *cb = (void *) fake_read;
        printf(", now %p\n", *cb);

        printf("bus handle was %p", *handle);
        if (!*handle) { *handle = dummy_handle; printf(", now %p", *handle); }
        printf("\n");

        {
            /* The frame width, four bytes along from the mode in the same config block. The
             * algorithm divides the FIFO level by it, so zero is a division by zero rather than
             * a wrong answer - which is exactly the exception this raised once it was finally
             * running as Thumb code. Two, because the FIFO interleaves two channels. */
            unsigned char *fw = (unsigned char *)(base + FRAME_WIDTH);
            make_writable(fw);
            printf("frame width was %u", *fw);
            *fw = 2;
            printf(", now %u\n", *fw);
        }
        printf("mode byte was %u", *mode_byte);
        *mode_byte = 1;
        printf(", now %u\n\n", *mode_byte);
    }

    for (i = 0; i < wave_n && i * 2 + 1 < 48000; i++) {
        mixed[i * 2] = wave1[i];
        mixed[i * 2 + 1] = wave2[i];
    }

    /* The low bit says Thumb.
     *
     * This code is Thumb - its first instruction is f0 b5, a Thumb push - and on ARM a function
     * pointer carries that in bit 0. Calling an even address switches the core to ARM mode, where
     * the same bytes decode as something else entirely and run until they fault. That is what the
     * jump to nowhere was: not a missing callback, just a pointer built without its mode bit. */
    fn = (spo2_fn)(void *)((base + SPO2_FN) | 1);
    printf("calling the vendor routine with our samples...\n");
    memset(scratch, 0, sizeof scratch);
    fn(mixed, wave_n, (unsigned) mode, &result, &level, &status, &a, &b, &c, scratch, &e);

    printf("\nit returned.\n");
    printf("  result=%u level=%u status=%u a=%u b=%u c=%u e=%u\n",
           result, level, status, a, b, c, e);
    printf("  register reads served: %d\n", reads);
    printf("  waveform consumed: %d of %d samples\n", wave_at, wave_n);

    dlclose(h);
    return 0;
}
