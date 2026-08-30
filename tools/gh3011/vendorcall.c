/* Run the vendor's own saturation routine on our data, and see what it makes of it.
 *
 * gh3011_service is a PIE - ELF type 3, DYN - and a PIE is a shared object that happens to have
 * an entry point, so dlopen can map it. Its internal functions have no symbols, but the load base
 * is discoverable from /proc/self/maps, and Ghidra gives the offsets. That makes the algorithm
 * callable directly: no daemon, no command socket, and no giving up the chip.
 *
 * The point is comparison rather than theft. We have recorded waveforms and four estimators of
 * our own that now agree with each other to about ten percent; what none of them have is a second
 * opinion from something known to work. Feeding the same samples to the routine the vendor ships
 * is the only way to find out whether our number is right or merely consistent.
 *
 * From Ghidra, FUN_0001b7c0 is
 *
 *     void f(void *samples, int len, unsigned mode, unsigned char *result, unsigned char *level,
 *            unsigned char *status, unsigned char *a, unsigned char *b, unsigned short *c,
 *            void *scratch, unsigned short *e)
 *
 * and it returns immediately unless a global byte is 1 or 7 - a mode the daemon sets elsewhere.
 * So a first run producing zeroes proves the call mechanism and nothing about the algorithm; that
 * global has to be found before the answer means anything. Both outcomes are worth having and the
 * program says which it got.
 *
 * Reads a waveform written by ppgd: a rate on the first line, then "ch1 ch2" per sample.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>

#define VENDOR "/system/bin/gh3011_service.real"
/* Ghidra loads a PIE at 0x10000, so every address it prints is that much higher than the offset
 * from the load base. Verified against the bytes rather than assumed: +0x0b7c0 begins f0 b5,
 * which is the push {r4,r5,r6,r7,lr} the disassembly shows, and +0x1b7c0 does not.
 *
 * Every crash before this was a jump into the middle of unrelated code. The routine had not been
 * called once. */
#define GHIDRA_BASE   0x10000
#define SPO2_OFFSET   (0x1b7c0 - GHIDRA_BASE)
#define INIT_OFFSET   (0x119b0 - GHIDRA_BASE)
#define GUARD_OFFSET  (0x44030 - GHIDRA_BASE)
#define MODE_OFFSET   (0x3ec4a - GHIDRA_BASE)

typedef void (*spo2_fn)(void *, int, unsigned, unsigned char *, unsigned char *,
                        unsigned char *, unsigned char *, unsigned char *,
                        unsigned short *, void *, unsigned short *);

/* Where dlopen put it. The maps file is the only place that says, since nothing in a PIE's
 * dynamic table points at a static function. */
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
            if (!best || lo < best) best = lo;    /* the first mapping is the load base */
        }
    }
    fclose(f);
    return best;
}

int main(int argc, char **argv)
{
    static int ch1[24000], ch2[24000];
    static int mixed[48000];
    unsigned char result = 0, level = 0, status = 0, a = 0, b = 0;
    unsigned short c = 0, e = 0;
    unsigned char scratch[512];
    unsigned long base;
    void *h;
    spo2_fn fn;
    FILE *w;
    double fs = 0;
    int n = 0, i, mode = argc > 2 ? atoi(argv[2]) : 2;

    setvbuf(stdout, NULL, _IONBF, 0);

    if (argc < 2) { printf("usage: vendorcall <waveform> [mode]\n"); return 1; }
    w = fopen(argv[1], "r");
    if (!w) { printf("cannot read %s\n", argv[1]); return 1; }
    if (fscanf(w, "%lf", &fs) != 1) { fclose(w); printf("not a waveform\n"); return 1; }
    while (n < 24000 && fscanf(w, "%d %d", &ch1[n], &ch2[n]) == 2) n++;
    fclose(w);
    printf("waveform: %d samples at %.1f Hz\n", n, fs);
    if (n < 200) { printf("too short\n"); return 1; }

    h = dlopen(VENDOR, RTLD_NOW);
    if (!h) { printf("dlopen refused: %s\n", dlerror()); return 1; }
    base = base_of("gh3011_service.real");
    printf("dlopen accepted it; load base 0x%lx\n", base);
    if (!base) { printf("no mapping found, so the offset cannot be applied\n"); dlclose(h); return 1; }

    /* What the loader actually did, before assuming anything about where things landed.
     *
     * base + vaddr is the right arithmetic for a shared object, and Ghidra's 0x44030 is a virtual
     * address rather than a file offset, so it ought to work. It did not, which means either the
     * base is wrong or the page is in a mapping the scan skipped - and .bss has no filename, so a
     * scan keyed on the filename skips exactly that. Printing the neighbourhood settles it.
     */
    {
        FILE *m = fopen("/proc/self/maps", "r");
        char line[512];
        printf("\nmappings around the image:\n");
        if (m) {
            while (fgets(line, sizeof line, m)) {
                unsigned long lo = strtoul(line, NULL, 16);
                /* the image and whatever anonymous pages follow it */
                if (lo >= base && lo <= base + 0x60000) {
                    line[strcspn(line, "\n")] = 0;
                    printf("   %s\n", line);
                }
            }
            fclose(m);
        }
        printf("   wanted: guard at 0x%lx, mode byte at 0x%lx\n\n",
               base + GUARD_OFFSET, base + MODE_OFFSET);
    }

    /* Which offset is the real one.
     *
     * Ghidra loads a PIE at 0x10000 unless told otherwise, so every address it prints may be that
     * much higher than the offset from the load base. The image here maps only 0x34000 bytes and
     * the addresses wanted were past the end of it, which is exactly what that mistake looks
     * like.
     *
     * Rather than believe either arithmetic, look at the bytes. Ghidra disassembled the routine's
     * first instruction as push {r4,r5,r6,r7,lr}, which in Thumb is f0 b5. Whichever candidate
     * starts with those bytes is the function.
     */
    {
        const unsigned char *raw = (const unsigned char *) base;
        printf("at +0x1b7c0: %02x %02x   at +0x0b7c0: %02x %02x   (want f0 b5)\n",
               raw[0x1b7c0], raw[0x1b7c1], raw[0xb7c0], raw[0xb7c1]);
    }

    /* Satisfy the two things the routine reads before it will run, without running the daemon.
     *
     * Ghidra resolves both, and neither is what the first guess assumed:
     *
     *     ldr r2,[0x1b92c] -> 0x3ccd4 holds 0x44030 ; ldr r3,[r2] -> 0x44030
     *     ldr r3,[0x1b930] -> 0x3ce24 holds 0x3ec4a ; ldrb r1,[r3] -> 0x3ec4a
     *
     * The mode pointer is already valid - it points into .bss at 0x3ec4a, and the byte there is
     * zero, which is why the routine would return without doing anything. That is a guard, not a
     * crash.
     *
     * The crash is the stack protector. __stack_chk_guard lives in .bss at 0x44030 and its
     * relocation is not applied when an executable is dlopened rather than executed, so the slot
     * holds zero and the function dereferences null on its second instruction. Nothing to do with
     * the algorithm at all.
     *
     * Both are ordinary memory once the image is mapped. Give the guard something to read and set
     * the mode byte to 1, and the routine has what it was waiting for - no service object, no
     * constructor, and none of the daemon.
     */
    /* Set the one thing that actually gates the routine.
     *
     * The stack guard was a red herring twice over. __stack_chk_guard is a libc symbol, not part
     * of this image - Ghidra shows it in a synthetic block past the end of the file, which is why
     * base + its address lands in an unmapped gap and why mprotect refused. dlopen resolves it
     * through the GOT like any other import, so it was never broken. Writing to that address was
     * the crash, and the crash was mine.
     *
     * What genuinely gates the function is one byte in .bss, and that mapped fine once the
     * Ghidra base was accounted for. Zero means "no mode set" and the routine returns without
     * doing anything; 1 is what the daemon puts there.
     */
    {
        unsigned char *mode_byte = (unsigned char *)(base + MODE_OFFSET);
        long pagesz = sysconf(_SC_PAGESIZE);
        void *mp = (void *)((unsigned long) mode_byte & ~(pagesz - 1));

        if (mprotect(mp, pagesz, PROT_READ | PROT_WRITE) != 0) {
            printf("could not make the mode byte writable\n");
        } else {
            printf("mode byte at %p held %u", (void *) mode_byte, *mode_byte);
            *mode_byte = 1;
            printf(", now %u\n\n", *mode_byte);

        }
    }

    /* Their own initialisation first, when asked for by a third argument.
     *
     * The routine dereferences a pointer from the data section in its opening lines and that
     * pointer is set during daemon start-up, so calling the algorithm cold segfaults. Rather than
     * write the global by hand - which means guessing its address, its type and its lifetime -
     * the code that owns it can be run.
     *
     * GH30xService::init is at 0x119b0. It will probably open the sensor and start threads, which
     * is why this must not run while anything else is measuring, and why it is behind an argument
     * rather than automatic.
     */
    if (argc > 3) {
        void (*initfn)(void) = (void (*)(void))(void *)(base + INIT_OFFSET);
        printf("calling GH30xService::init at 0x%lx first\n", base + 0x119b0);
        fflush(stdout);
        initfn();
        printf("init returned without crashing\n");
    }

    fn = (spo2_fn)(void *)(base + SPO2_OFFSET);
    printf("calling the routine at 0x%lx with mode %d\n\n", base + SPO2_OFFSET, mode);

    /* Interleaved, which is how the FIFO delivers it and so most likely what it expects. */
    for (i = 0; i < n && i * 2 + 1 < 48000; i++) {
        mixed[i * 2] = ch1[i];
        mixed[i * 2 + 1] = ch2[i];
    }

    memset(scratch, 0, sizeof scratch);
    fn(mixed, n, (unsigned) mode, &result, &level, &status, &a, &b, &c, scratch, &e);

    printf("result=%u level=%u status=%u a=%u b=%u c=%u e=%u\n", result, level, status, a, b, c, e);
    if (!result && !level && !status) {
        printf("\nall zero. The routine returns immediately unless a global mode byte is 1 or 7,\n");
        printf("and nothing here has set it - so this shows the call works and says nothing\n");
        printf("about the algorithm. Finding that global is the next step.\n");
    } else {
        printf("\nit answered. Compare against what ppgd made of the same waveform.\n");
    }

    dlclose(h);
    return 0;
}
