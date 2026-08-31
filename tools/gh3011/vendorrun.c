/* Run the vendor saturation pipeline on our recorded waveforms, through their own entry points.
 *
 * The previous harness reconstructed start-up by hand: find a null pointer, work out what it
 * should be, write it, run again, find the next one. That reached their computation but does not
 * terminate, because the thing it was rebuilding is an initialiser that already exists in the
 * binary.
 *
 * Walking callers of the heap initialiser found it, with names attached:
 *
 *     FUN_00012b54  gh30x_start_func_with_mode(mode)   mode 2 hb, 3 hrv, 7 spo2
 *     FUN_000134dc  gh30x_spo2_start()                 no arguments
 *     FUN_00011fe4  gh30x_new_data_evt_handler()       no arguments
 *     FUN_000136e0  buffers 100 samples, then computes
 *
 * Mode 7 is why the gate byte had to read 1 or 7. So the whole job is: answer register reads from
 * a recorded waveform, call their start with mode 7, then call their sample pump until it stops
 * saying 0xff - which is what it returns until it has 100 samples.
 *
 * FUN_000136e0 masks every sample with 0x1ffff. 0x300000 & 0x1ffff is zero, so their seventeen-bit
 * mask removes exactly the dark pedestal this project measured by hand and subtracts by name. That
 * is a second opinion on the one correction everything else here rests on.
 *
 * Reads a waveform written by ppgd: a rate on the first line, then "ch1 ch2" per sample.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <signal.h>
#include <ucontext.h>
#include <sys/mman.h>
#include <unistd.h>

#define VENDOR       "/system/bin/gh3011_service.real"
#define GHIDRA_BASE  0x10000
#define OFF(x)       ((x) - GHIDRA_BASE)

#define START_MODE   OFF(0x12b54)   /* gh30x_start_func_with_mode(mode) */
#define PUMP         OFF(0x136e0)   /* one sample in; a result every hundredth call */
#define EVT          OFF(0x11fe4)   /* gh30x_new_data_evt_handler() */
#define READ_CB      OFF(0x3e994)   /* the register-read callback everything goes through */
#define BUS_HANDLE   OFF(0x3d53c)
#define MODE_BYTE    OFF(0x3ec4a)
#define LOGGER       OFF(0x14268)   /* logs through a handle the daemon opens; we have none */

/* Thumb. A pointer without bit 0 set runs the same bytes as ARM instructions, which is a fault
 * somewhere unrelated rather than an error. */
#define FN(base, off) ((void *)(((unsigned long)(base) + (off)) | 1))

static int wave1[24000], wave2[24000];
static int wave_n, wave_at, reads;
static unsigned unknown[32];
static int unknown_hits[32], unknown_n;
static int chan_toggle;

/* What to answer for register 0x0022.
 *
 * Their start-up reads it once and then stops, so a zero answer fails a check and everything after
 * it never runs. Which value it wants is not written down anywhere we have, but there are only 256
 * of them and the harness can say how far start got - so sweep it and watch the read count rather
 * than reason about it.
 */
static unsigned reg22 = 0;
static int trace;

/* And what to answer for 0x0016.
 *
 * The trace says start-up reads 0x0022 then 0x0016, which is the FUN_00016ee0(0x16) whose result
 * goes straight into 0x8000 / v. Zero makes that divide produce nonsense, the nonsense becomes a
 * length, and the length is the address libc faults on. So this one is a divisor rather than a
 * flag, and worth sweeping separately.
 */
static unsigned reg16 = 0;
static unsigned long g_base;

/* Answer a register read the way the chip would. 0x004a is the level, 0xaaaa the FIFO - three
 * bytes big-endian per sample, channels alternating. Anything else gets a harmless answer, since
 * a plausible one keeps the code walking forward and a refusal stops it somewhere uninformative. */
static int fake_read(void *handle, unsigned char *addr, int alen,
                     unsigned char *out, int olen)
{
    unsigned int reg;
    (void) handle;
    if (!addr || alen < 2 || !out || olen < 1) return -1;
    reg = ((unsigned) addr[0] << 8) | addr[1];
    reads++;
    {
        int j;
        for (j = 0; j < unknown_n; j++)
            if (unknown[j] == reg) { unknown_hits[j]++; break; }
        if (j == unknown_n && unknown_n < 32) {
            unknown[unknown_n] = reg; unknown_hits[unknown_n] = 1; unknown_n++;
        }
    }
    memset(out, 0, olen);

    /* The order the registers are asked for, not just the tally.
     *
     * Sweeping the answer to 0x0022 showed only odd values get past - it is a bit-0 test - and an
     * odd answer buys exactly one more read before a fault. Counting registers cannot say what
     * that second read wants; the sequence can, and the first few reads of start-up are short
     * enough to print in full.
     */
    if (trace && reads <= 24)
        fprintf(stderr, "  read %2d: reg 0x%04x, %d bytes\n", reads, reg, olen);

    if (reg == 0x004a) {
        int left = wave_n - wave_at;
        int lvl = left > 64 ? 64 : (left < 0 ? 0 : left);
        out[0] = (unsigned char)(lvl >> 8);
        if (olen > 1) out[1] = (unsigned char) lvl;
        return 0;
    }
    /* Register zero is the sample read too.
     *
     * Their reader takes the register number out of a global that start-up fills in, and start-up
     * bailed early here because there is no chip to talk to - so it asks for register zero, 2501
     * times, once per sample. That is the sample read with an address that was never filled in
     * rather than a different request, and the algorithm does not care where the number came from.
     * Serving it the waveform is what a working sensor would have done.
     */
    if (reg == 0xaaaa || reg == 0x0000) {
        /* One sample per read, not a block.
         *
         * The first working run fed 2501 samples in and got the same answer 25 times, which is
         * what a constant input looks like. The reader asks for four bytes at a time - a single
         * sample - and the block loop here needed six before it advanced, so every read returned
         * sample zero of channel one and the waveform never moved. The reads were arriving all
         * along; there was nothing behind them.
         *
         * Their side masks with 0x1ffff, so only the low seventeen bits are consulted and the
         * byte order of the top byte does not matter. Big-endian in the low three, which is how
         * the FIFO delivers.
         */
        if (olen <= 4) {
            int v = wave_at < wave_n ? wave1[wave_at] : 0;
            if (chan_toggle) v = wave_at < wave_n ? wave2[wave_at] : 0;
            out[0] = 0;
            if (olen > 1) out[1] = (unsigned char)((v >> 16) & 0xff);
            if (olen > 2) out[2] = (unsigned char)((v >> 8) & 0xff);
            if (olen > 3) out[3] = (unsigned char)(v & 0xff);
            if (chan_toggle) wave_at++;
            chan_toggle = !chan_toggle;
            return 0;
        }
        {
            int i;
            for (i = 0; i + 2 < olen && wave_at < wave_n; i += 3) {
                int v = ((i / 3) & 1) ? wave2[wave_at] : wave1[wave_at];
                out[i]     = (unsigned char)((v >> 16) & 0xff);
                out[i + 1] = (unsigned char)((v >> 8) & 0xff);
                out[i + 2] = (unsigned char)(v & 0xff);
                if ((i / 3) & 1) wave_at++;
            }
        }
        return 0;
    }
    if (reg == 0x0022) { out[0] = (unsigned char)(reg22 >> 8); if (olen > 1) out[1] = (unsigned char) reg22; return 0; }
    if (reg == 0x0016) { out[0] = (unsigned char)(reg16 >> 8); if (olen > 1) out[1] = (unsigned char) reg16; return 0; }
    if (reg == 0x0008) { out[0] = 0x00; if (olen > 1) out[1] = 0x02; return 0; }
    if (reg == 0x0028) { out[0] = 0x00; if (olen > 1) out[1] = 0x31; return 0; }

    return 0;
}

/* Load a snapshot of the running daemon over our copy of its data.
 *
 * Every pointer this harness filled in by hand exists already, correct, inside the daemon - so
 * copying its data and bss wholesale replaces the whole reconstruction. Their measurement starts
 * on a wear event rather than a command, so the snapshot has to be taken while someone is wearing
 * the watch; waitdump does that, and the state it caught has the context and the config both live
 * and both inside the image.
 *
 * The catch is that a snapshot is full of absolute addresses valid where it was taken, and dlopen
 * puts our copy somewhere else. Any word pointing into the image is moved by the difference.
 *
 * Only the image range can be fixed this way. A pointer into their heap or stack refers to memory
 * this process does not have, and rewriting it would aim a valid-looking pointer at whatever
 * happens to sit at that address here - worse than leaving it, because it would fault somewhere
 * unrelated to the mistake. Those are counted and left alone.
 */
static int load_dump(const char *path, unsigned long base, unsigned long from,
                     unsigned long theirs, unsigned long dump_from)
{
    FILE *f = fopen(path, "rb");
    static unsigned char buf[0x8000];
    unsigned long n, i, moved = 0, foreign = 0;
    unsigned char *dst = (unsigned char *)(base + from);
    long ps = sysconf(_SC_PAGESIZE);
    unsigned long lo = (unsigned long) dst & ~(ps - 1);

    if (!f) { printf("no snapshot at %s\n", path); return 0; }
    n = fread(buf, 1, sizeof buf, f);
    fclose(f);
    if (n < 0x1000) { printf("snapshot too small (%lu bytes)\n", n); return 0; }

    /* Only the part of it that is theirs to give.
     *
     * Loading the whole snapshot jumped to a libc address from the process it was taken in,
     * reached through a function pointer this process had perfectly well relocated until the copy
     * overwrote it. The snapshot is authoritative about the algorithm state it allocated, and
     * wrong about every slot the loader owns.
     *
     * The two separate cleanly by address: the callback slots, the bus handle and the relocated
     * tables sit below 0x3f000, while the context, the config and everything they point at sit
     * above it. Take the top; leave the bottom as dlopen built it.
     */
    {
        unsigned long skip = from - dump_from;
        if (skip >= n) { printf("the snapshot does not reach that far\n"); return 0; }
        memmove(buf, buf + skip, n - skip);
        n -= skip;
    }

    for (i = 0; i + 4 <= n; i += 4) {
        unsigned long v;
        memcpy(&v, buf + i, 4);
        if (v >= theirs && v < theirs + 0x36000) {
            v = base + (v - theirs);
            memcpy(buf + i, &v, 4);
            moved++;
        } else if (v > 0x10000 && v < 0xc0000000) {
            foreign++;
        }
    }

    if (mprotect((void *) lo, (size_t)((unsigned long) dst + n - lo + ps),
                 PROT_READ | PROT_WRITE) != 0)
        printf("warning: could not make the whole range writable\n");
    memcpy(dst, buf, n);
    printf("loaded %lu bytes of live state: %lu pointers moved into place, "
           "%lu left alone as outside the image\n", n, moved, foreign);
    return 1;
}

/* Say where it died, in the addresses the decompiler uses. */
static void on_segv(int sig, siginfo_t *si, void *uc)
{
    ucontext_t *u = (ucontext_t *) uc;
    (void) sig;
    fprintf(stderr, "\nfaulted touching %p\n", si ? si->si_addr : 0);
#ifdef __arm__
    {
        unsigned long pc = (unsigned long) u->uc_mcontext.arm_pc;
        unsigned long lr = (unsigned long) u->uc_mcontext.arm_lr;
        if (g_base && pc > g_base && pc - g_base < 0x60000)
            fprintf(stderr, "  at Ghidra 0x%lx\n", pc - g_base + GHIDRA_BASE);
        else
            fprintf(stderr, "  at pc 0x%lx (outside the image - libc)\n", pc);
        if (g_base && lr > g_base && lr - g_base < 0x60000)
            fprintf(stderr, "  called from Ghidra 0x%lx\n", lr - g_base + GHIDRA_BASE);
    }
#else
    (void) u;
#endif
    fprintf(stderr, "  after %d register reads, %d/%d samples consumed\n", reads, wave_at, wave_n);
    _exit(3);
}

static unsigned long base_of(const char *needle)
{
    FILE *f = fopen("/proc/self/maps", "r");
    char line[512];
    unsigned long best = 0;
    if (!f) return 0;
    while (fgets(line, sizeof line, f)) {
        unsigned long lo;
        if (!strstr(line, needle)) continue;
        lo = strtoul(line, NULL, 16);
        if (!best || lo < best) best = lo;
    }
    fclose(f);
    return best;
}

static void make_writable(void *p)
{
    long ps = sysconf(_SC_PAGESIZE);
    mprotect((void *)((unsigned long) p & ~(ps - 1)), ps, PROT_READ | PROT_WRITE);
}

/* Make a function return without doing anything.
 *
 * Their code logs constantly, and the logger writes through a handle the daemon opens at start-up.
 * We have no daemon, so it is null, and the first log line faults before any of the algorithm has
 * run. The logging is not what we came for, and giving it a real handle means reconstructing their
 * socket setup - so the cheaper move is to make the log call do nothing at all.
 *
 * Thumb bx lr is 0x4770. Patching the entry point is enough; the rest of the body never runs.
 */
static void stub_out(unsigned long base, unsigned long off, const char *what)
{
    unsigned char *p = (unsigned char *)(base + off);
    long ps = sysconf(_SC_PAGESIZE);
    void *page = (void *)((unsigned long) p & ~(ps - 1));

    if (mprotect(page, ps, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        printf("could not stub %s\n", what);
        return;
    }
    p[0] = 0x70; p[1] = 0x47;                    /* bx lr */
    printf("stubbed %s\n", what);
}

int main(int argc, char **argv)
{
    void *h;
    unsigned long base;
    double fs = 0;
    int mode = argc > 2 ? atoi(argv[2]) : 7;
    int quiet = argc > 5;
    int have_dump = 0;
    int i, results = 0;
    FILE *w;
    struct sigaction sa;
    void (*start_mode)(int);
    unsigned (*pump)(unsigned, unsigned, unsigned, unsigned);

    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) { printf("usage: vendorrun <waveform> [mode]\n"); return 1; }

    w = fopen(argv[1], "r");
    if (!w) { printf("cannot read %s\n", argv[1]); return 1; }
    if (fscanf(w, "%lf", &fs) != 1) { fclose(w); printf("not a waveform\n"); return 1; }
    while (wave_n < 24000 && fscanf(w, "%d %d", &wave1[wave_n], &wave2[wave_n]) == 2) wave_n++;
    fclose(w);
    printf("waveform: %d samples at %.1f Hz\n", wave_n, fs);
    if (wave_n < 200) { printf("too short to be worth running\n"); return 1; }

    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = on_segv;
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);

    h = dlopen(VENDOR, RTLD_NOW);
    if (!h) { printf("dlopen refused: %s\n", dlerror()); return 1; }
    base = g_base = base_of("gh3011_service.real");
    if (!base) { printf("no mapping found\n"); return 1; }
    printf("mapped at 0x%lx\n", base);

    /* Their live state first, if a snapshot exists, before anything here writes over it. */
    {
        /* Which snapshot. One per mode gets captured, since each routine needs the config that
         * its own measurement builds, so the caller has to say which one it wants. */
        const char *snap = getenv("SNAPSHOT");
        if (!snap) snap = "/data/local/tmp/live.bin";
        printf("snapshot: %s\n", snap);
        have_dump = load_dump(snap, base, 0x2f000, 0x7f622000, 0x2c000);
    }

    /* The one thing that must be in place before their code runs: register reads have to go
     * somewhere. Everything else start-up will do for itself. */
    {
        void **cb = (void **)(base + READ_CB);
        void **handle = (void **)(base + BUS_HANDLE);
        make_writable(cb);
        make_writable(handle);
        *cb = (void *) fake_read;
        *handle = (void *) 0x28;
        printf("read callback installed\n");
    }
    stub_out(base, LOGGER, "the logger");

    /* The two bytes their start-up refuses to proceed without.
     *
     * gh30x_spo2_start kept returning an error and reading not one register, which is a refusal
     * before the bus rather than a failure on it. FUN_000188ec explains it: it answers -7 unless
     * the byte at 0x3ec4e is non-zero, and -6 unless the byte at 0x3ec49 is 1, both before any
     * chip is addressed. The daemon sets them when it configures a frame; nothing here had.
     *
     * 0x3ec4e is the frame width, which this project already had to fill in to stop a divide by
     * zero. Past both gates the start reaches FUN_000236c4, which is the allocator setup - so
     * these two bytes are what stands between here and their own initialised state.
     */
    {
        unsigned char *width = (unsigned char *)(base + OFF(0x3ec4e));
        unsigned char *ready = (unsigned char *)(base + OFF(0x3ec49));
        make_writable(width);
        make_writable(ready);
        printf("frame width was %u, ready flag was %u", *width, *ready);
        if (!*width) *width = 2;
        *ready = 1;
        printf("; now %u and %u\n", *width, *ready);
    }

    start_mode = (void (*)(int)) FN(base, START_MODE);
    pump = (unsigned (*)(unsigned, unsigned, unsigned, unsigned)) FN(base, PUMP);

    if (argc > 3) reg22 = (unsigned) strtoul(argv[3], NULL, 0);
    if (argc > 4) reg16 = (unsigned) strtoul(argv[4], NULL, 0);
    trace = !quiet;
    /* Run their start-up even with a snapshot loaded, because the snapshot is of the wrong mode.
     *
     * The daemon was doing wear detection when it was caught - its gate byte read 1 - so the two
     * things the measurement routines actually take, the config pointer at 0x3d5ac and the
     * register at 0x3d5a8, are both still zero in it. What the snapshot does give is the heap and
     * the context, which is what start-up needed and never had here.
     *
     * So the two halves fit together: their state from the snapshot, their configuration from
     * their own start-up running on top of it.
     */
    {
        unsigned long *cfg = (unsigned long *)(base + OFF(0x3d5ac));
        unsigned short *rg = (unsigned short *)(base + OFF(0x3d5a8));

        printf("\nbefore start: config 0x%08lx, register 0x%04x\n", *cfg, *rg);
        printf("calling gh30x_start_func_with_mode(%d) with 0x0022 answering 0x%x...\n",
               mode, reg22);
        start_mode(mode);
        printf("after start:  config 0x%08lx, register 0x%04x\n", *cfg, *rg);
    }
    printf("start state ready; %d register reads so far\n", reads);
    if (quiet) { printf("SWEEP reg22=0x%04x startreads=%d\n", reg22, reads); return 0; }

    /* Put the callback back, because their start-up takes it away.
     *
     * The first run got through the whole pipeline and read not one register. The slot the reader
     * goes through is the one installed here - resolving it confirmed 0x3e994 and the handle at
     * 0x3d53c - so the only way to call the pump a thousand times and read nothing is for the
     * pointer to have been cleared in between. Their start-up registers its own bus, finds no
     * device, and leaves the slot null.
     *
     * So install it after start rather than before, and say what is actually in the slot rather
     * than assuming the write stuck.
     */
    {
        void **cb = (void **)(base + READ_CB);
        void **handle = (void **)(base + BUS_HANDLE);
        unsigned char *mb = (unsigned char *)(base + MODE_BYTE);

        printf("the gate byte now reads %u\n", *mb);
        printf("the read callback slot holds %p after start\n", *cb);
        make_writable(cb);
        make_writable(handle);
        *cb = (void *) fake_read;
        *handle = (void *) 0x28;
        make_writable(mb);
        if (*mb == 0) { *mb = (unsigned char) mode; printf("gate byte forced to %d\n", mode); }
        printf("reinstalled; slot now holds %p\n", *cb);
    }

    /* Their pump: one sample a call, a result every hundredth. 0xff means not yet. */
    printf("\nfeeding %d samples through their pipeline...\n", wave_n);

    /* Give the three trailing arguments somewhere to write.
     *
     * Feeding real samples changed the answer from 2 to 1 and then held it there, which is what a
     * status code does - the pipeline is live and reacting to data, but a saturation is not a
     * small integer that stays put. The last argument is passed straight through to the
     * computation, so it is where a result would be written, and passing zero for it has been
     * throwing that away every run so far.
     */
    {
        static unsigned char outa[256], outb[256], outc[256];
        for (i = 0; i < wave_n; i++) {
            unsigned r;
            memset(outa, 0, sizeof outa);
            memset(outb, 0, sizeof outb);
            memset(outc, 0, sizeof outc);
            r = pump((unsigned) mode, (unsigned)(unsigned long) outa,
                     (unsigned)(unsigned long) outb, (unsigned)(unsigned long) outc);
            if (r != 0xff) {
                int k, nz = 0;
                printf("  sample %5d: returned %u  ", i, r);
                for (k = 0; k < 32; k++) if (outc[k]) nz = 1;
                printf("out:");
                for (k = 0; k < 16; k++) printf(" %02x", outc[k]);
                if (!nz) printf("  (nothing written)");
                printf("\n");
                if (++results > 12) { printf("  ...\n"); break; }
            }
        }
    }
    printf("\n%d results from %d samples, %d register reads\n", results, wave_n, reads);
    {
        int j;
        printf("registers it asked for:\n");
        for (j = 0; j < unknown_n; j++)
            printf("   0x%04x  %d times\n", unknown[j], unknown_hits[j]);
    }
    if (!results)
        printf("no result: the pump never filled, so reads are not reaching the waveform\n");

    /* Now the saturation routine itself, with their start-up behind it.
     *
     * The pump above turned out to be their contact check rather than their measurement:
     * FUN_00032e48 filters a hundred samples, takes an amplitude and a level, and answers 1 when
     * the level sits outside the window its config expects. Useful - it says our recorded level is
     * not what their config wants - but it is not a saturation.
     *
     * FUN_0001b7c0 is. Calling it has failed before for want of initialised state, and every
     * attempt to supply that state by hand hit the next uninitialised pointer. What is different
     * now is that their own start-up has run first, so the state is theirs rather than mine.
     */
    {
        typedef void (*spo2_fn)(void *, int, unsigned, unsigned char *, unsigned char *,
                                unsigned char *, unsigned char *, unsigned char *,
                                unsigned short *, void *, unsigned short *);
        static int mixed[48000];
        static unsigned char scratch[1024];
        unsigned char res = 0, lvl = 0, st = 0, a = 0, b = 0;
        unsigned short c = 0, e = 0;
        spo2_fn spo2 = (spo2_fn) FN(base, OFF(0x1b7c0));
        int j, n = wave_n > 24000 ? 24000 : wave_n;

        for (j = 0; j < n; j++) { mixed[j * 2] = wave1[j]; mixed[j * 2 + 1] = wave2[j]; }
        memset(scratch, 0, sizeof scratch);

        /* The guard byte, once more, immediately before the call.
         *
         * The routine returns without computing unless this reads 1 or 7. It does not any more:
         * their start-up now runs far enough to set it itself, and what it sets is 8. So the
         * earlier "force it only if zero" is exactly wrong now - zero was the symptom of start-up
         * not running, and start-up running is what changed.
         */
        {
            unsigned char *mb = (unsigned char *)(base + MODE_BYTE);
            make_writable(mb);
            printf("\ngate byte reads %u; the routine wants 1 or 7\n", *mb);
            *mb = (unsigned char) mode;
        }
        /* The config the lookup counts entries out of.
         *
         * FUN_00032948 does *(byte *)(*piVar14 + 8) * 0x18 to size an allocation, and piVar14
         * points at a slot their start-up fills with a config block. Ours is still null - start
         * gets far enough to set the mode and size a frame, not far enough to load a config - so
         * the count read lands on address 8. Byte 8 is the number of 0x18-byte entries.
         */
        {
            static unsigned char cfg[512];
            void **slot = (void **)(base + OFF(0x3f688));
            make_writable(slot);
            printf("config slot holds %p", *slot);
            if (!*slot) {
                memset(cfg, 0, sizeof cfg);
                cfg[8] = 1;                        /* one entry */
                *slot = cfg;
                printf(", now %p with one entry", *slot);
            }
            printf("\n");
        }
        /* Somewhere for the answer to land.
         *
         * The dispatcher finishes by memcpying 0x1c bytes out of **(0x3ceb0). The outer pointer is
         * a real data slot; only the inner one is missing, so the copy reads from address zero.
         * The earlier harness hit this too and the fix is the same - fill the inner pointer only,
         * and leave the outer alone.
         */
        {
            void **outer = *(void ***)(base + OFF(0x3ceb0));
            static unsigned char resbuf[128];
            if (outer) {
                make_writable(outer);
                printf("result buffer holds %p", *outer);
                if (!*outer) { *outer = resbuf; printf(", now %p", *outer); }
                printf("\n");
            } else {
                printf("the result slot itself is null\n");
            }
        }
        /* The context the computation carries between windows.
         *
         * FUN_00030b20 reads offset 0x354 of a pointer that is null, and their start-up does not
         * fill it because it stops three reads in. Of every data address that function touches,
         * 0x3cee0 is the only pointer slot reading zero, and nothing in the binary writes it
         * through a recorded reference - which is what a pointer written through a computed
         * address looks like.
         *
         * This is the m_stContext their strings name in g_pstHbamainExternBss2Heap. Its real size
         * is unknown, so give it far more than 0x354 and zero it: a zeroed context is a plausible
         * starting state for something that accumulates, and if 0x3cee0 is the wrong slot the
         * fault will not move.
         */
        {
            static unsigned char ctx[16384];
            void **slot = (void **)(base + OFF(0x3cee0));
            make_writable(slot);
            printf("context slot holds %p", *slot);
            if (!*slot) {
                memset(ctx, 0, sizeof ctx);
                *slot = ctx;
                printf(", now %p (%u bytes)", *slot, (unsigned) sizeof ctx);
            }
            printf("\n");
        }
        /* Call them the way gh30x_fifo_evt_handler does.
         *
         * Every call before this passed our samples as the first argument, because a routine that
         * measures a waveform ought to take one. It does not. The handler passes the config at
         * 0x3d5ac and the register at 0x3d5a8, and the routine reads its own samples through the
         * bus - which is why the fake bus matters and why the sample pointer never did. The zeros
         * that came back were not a measurement of anything.
         *
         *     mode 2   heart rate    FUN_0001b7c0   11 arguments
         *     mode 7   saturation    FUN_00018f38   18 arguments
         *
         * Both need a config that only a real measurement builds, so both are gated on a snapshot
         * taken while one was running.
         */
        {
            typedef unsigned (*hr_fn)(void *, unsigned short, int,
                                      unsigned char *, unsigned char *, unsigned char *,
                                      unsigned char *, unsigned char *, unsigned short *,
                                      void *, unsigned short *);
            typedef unsigned (*sat_fn)(void *, unsigned short, int,
                                       unsigned char *, unsigned char *, unsigned char *,
                                       unsigned char *, void *, void *, void *, void *,
                                       unsigned char *, unsigned char *, unsigned short *,
                                       unsigned char *, void *, unsigned short *,
                                       unsigned char *);
            void *cfg = *(void **)(base + OFF(0x3d5ac));
            unsigned short rg = *(unsigned short *)(base + OFF(0x3d5a8));
            unsigned short count = 0xcd;

            printf("\nconfig 0x%p, register 0x%04x\n", cfg, rg);
            if (!cfg) {
                /* Their configure step dereferences this, so a zeroed block gets further than a
                 * null one. It is not the real config and nothing it returns is trustworthy, but
                 * it says whether anything past the gate runs at all. */
                static unsigned char stub[512];
                printf("no measurement config in this snapshot - using an empty one, so treat\n");
                printf("anything below as a smoke test rather than a measurement\n");
                cfg = stub;
            }
            if (mode == 7) {
                /* The saturation routine wants the gate byte to read 8, not 7.
                 *
                 * FUN_00018f38 opens with a compare against 8 and returns otherwise, while the
                 * heart rate routine wants 1 or 7. So 7 selects which start-up runs and 8 is what
                 * that start-up leaves behind - which is why calling their start with mode 7
                 * here left the byte reading 8 and looked like a mistake at the time.
                 */
                unsigned char *mb = (unsigned char *)(base + MODE_BYTE);
                unsigned char sat = 0, q1 = 0, q2 = 0, q3 = 0, q4 = 0, q5 = 0, stat = 0, q6 = 0;
                unsigned short s1 = 0, s2 = 0, s3 = 0, s4 = 0;
                unsigned r;
                make_writable(mb);
                *mb = 8;
                printf("gate byte set to 8; calling their saturation routine at Ghidra 0x18f38...\n");
                r = ((sat_fn) FN(base, OFF(0x18f38)))(
                        cfg, rg, 2, &sat, &q1, &q2, &q3, &s1, &s2, &s3, &s4,
                        &q4, &q5, &e, &stat, scratch, &count, &q6);
                if (sat == 0xff) sat = 0;
                printf("  saturation=%u%%  status=%u  returned=%u\n", sat, stat, r);
                printf("  extras: %u %u %u %u %u  shorts: %u %u %u %u  count=%u\n",
                       q1, q2, q3, q4, q5, s1, s2, s3, s4, count);
            } else {
                unsigned char bpm = 0, q1 = 0, q2 = 0, q3 = 0, q4 = 0;
                unsigned short s1 = 0;
                unsigned r;
                printf("calling their heart rate routine at Ghidra 0x1b7c0...\n");
                r = ((hr_fn) FN(base, OFF(0x1b7c0)))(
                        cfg, rg, 2, &bpm, &q1, &q2, &q3, &q4, &s1, scratch, &count);
                if (bpm == 0xff) bpm = 0;
                printf("  heart rate=%u bpm  returned=%u\n", bpm, r);
                printf("  extras: %u %u %u %u  short=%u  count=%u\n", q1, q2, q3, q4, s1, count);
            }
        }
        (void) spo2; (void) mixed; (void) res; (void) lvl; (void) st; (void) a; (void) b; (void) c;
    }

    dlclose(h);
    return 0;
}
