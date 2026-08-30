/* ppg - trigger a measurement and read the raw waveform out of the sensor.
 *
 * Register 0xaaaa is the GH3011's sample FIFO: 24-bit big-endian samples, two channels
 * interleaved, about 97 Hz. That is the whole waveform, and this reads it directly.
 *
 * Modes:
 *   -hr     green LED, driver mode 4. Heart rate and beat-to-beat intervals.
 *   -spo2   red/IR, driver mode 5. Two channels, which is what a ratio-of-ratios needs.
 *
 * How the chip is started:
 *   -hal    (default) enable gh30x_sensor through the sensor framework, which is what the app
 *           does and the only trigger known to reliably produce a complete measurement. The
 *           daemon is then also draining the FIFO, so some samples land there instead of here.
 *   -self   configure and start it ourselves: driver mode for the rail, the daemon's own
 *           48-register table, then the chip start command (0xdddd = 0xc0). No competitor.
 *
 * Writes CSV with -o. The chip is stopped on every exit path, signals included: it must never
 * be left lit on someone's wrist.
 */
#include <stdio.h>
#include <math.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <android/sensor.h>
#include <android/looper.h>

#define PWR  0x40044702u   /* _IOW(G,2,4) with 1: powers the chip. Immediate, not a pointer. */
#define XFER 0xc0084704u
#define CMD  0x40184709u
#define ADDR 0x14
#define FIFO 0xaaaa
#define CAP  240
#define MAXS 60000

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };

static int fd = -1;
static int self_drive;
static int use_hal = 1;
static int spo2_mode;
static ASensorEventQueue *q;
static const ASensor *sens;

static unsigned int ch1[MAXS], ch2[MAXS];
static int ns;

static int wr(unsigned char *p, int n)
{
    struct msg m; struct rdwr r;
    m.addr = ADDR; m.flags = 0; m.len = n; m.buf = p;
    r.msgs = &m; r.n = 1;
    return ioctl(fd, XFER, &r);
}

static int wr16(unsigned short reg, unsigned short v)
{
    unsigned char p[4];
    p[0] = reg >> 8; p[1] = reg; p[2] = v >> 8; p[3] = v;
    return wr(p, 4);
}

static int wr8(unsigned short reg, unsigned char v)
{
    unsigned char p[3];
    p[0] = reg >> 8; p[1] = reg; p[2] = v;
    return wr(p, 3);
}

static int rdn(unsigned short reg, unsigned char *o, int n)
{
    unsigned char a[2];
    struct msg m[2]; struct rdwr r;
    a[0] = reg >> 8; a[1] = reg;
    memset(o, 0, n);
    m[0].addr = ADDR; m[0].flags = 0; m[0].len = 2; m[0].buf = a;
    m[1].addr = ADDR; m[1].flags = 1; m[1].len = n; m[1].buf = o;
    r.msgs = m; r.n = 2;
    return ioctl(fd, XFER, &r);
}

static int rd16(unsigned short reg, unsigned short *v)
{
    unsigned char b[2];
    if (rdn(reg, b, 2) < 0) return -1;
    *v = (unsigned short)((b[0] << 8) | b[1]);
    return 0;
}

static void stop_all(void)
{
    unsigned int w[6];
    if (q && sens) ASensorEventQueue_disableSensor(q, sens);
    if (fd < 0) return;
    if (self_drive) { wr8(0xdddd, 0xc4); }
    memset(w, 0, sizeof w);
    w[0] = 6;
    ioctl(fd, CMD, w);
}

static void bail(int s) { (void)s; stop_all(); _exit(2); }

/* the daemon's own configuration, read out of its binary at 0x25aa6 */
static const unsigned short cfg[][2] = {
    {0x0044,0x0001},{0x0048,0x0001},{0x0100,0xf530},{0x0102,0x4e20},
    {0x0104,0xf530},{0x0106,0x4e20},{0x0108,0xf530},{0x010a,0x2710},
    {0x010c,0xf148},{0x010e,0x57e4},{0x0110,0xf148},{0x0112,0x57e4},
    {0x0114,0xf148},{0x0116,0x30d4},{0x011c,0x01ff},{0x011e,0x01ff},
    {0x0120,0x01ff},{0x0126,0x0202},{0x0128,0x0002},{0x0130,0x0346},
    {0x0132,0x0446},{0x0134,0x0546},{0x0016,0x0147},{0x0080,0x0605},
    {0x0082,0x01c6},{0x0084,0x0023},{0x0118,0x9055},{0x011a,0x0000},
    {0x012e,0x0000},{0x0136,0x0000},{0x0186,0x0406},{0x0180,0x004d},
    {0x012a,0x0303},{0x012c,0x0003},{0x00c2,0xffff},{0x00c4,0x0528},
    {0x00c6,0xffff},{0x00c8,0x0528},{0x00ca,0x00a0},{0x00cc,0x006e},
    {0x00ce,0x042e},{0x00d0,0x0000},{0x00d4,0x042e},{0x00d6,0x0000},
    {0x00d8,0x0303},{0x00da,0x0101},{0x00dc,0x0101},{0x00de,0x0000},
};
#define NCFG ((int)(sizeof cfg / sizeof cfg[0]))

/* Subtract a one-second moving baseline: that removes the DC level and the slow drift, and
 * leaves the pulse. Returns the peak-to-peak of the result in *ppk. */
static void detrend(const unsigned int *src, double *out, int n, int w, double *ppk)
{
    double lo = 1e18, hi = -1e18;
    int i;
    for (i = 0; i < n; i++) {
        int a = i - w / 2, b = i + w / 2, k, cnt = 0;
        double s = 0;
        if (a < 0) a = 0;
        if (b > n) b = n;
        for (k = a; k < b; k++) { s += src[k]; cnt++; }
        out[i] = (double)src[i] - s / cnt;
        if (out[i] < lo) lo = out[i];
        if (out[i] > hi) hi = out[i];
    }
    if (ppk) *ppk = hi - lo;
}

static double mean_of(const unsigned int *src, int n)
{
    double s = 0;
    int i;
    for (i = 0; i < n; i++) s += src[i];
    return n ? s / n : 0;
}

static int cmp_int(const void *a, const void *b)
{
    return *(const int *)a - *(const int *)b;
}

/* Beat detection on the detrended channel. Returns bpm, or 0 if nothing clean was found. */
static double beats(const double *d, int n, double fs, int *gaps, int *ngaps)
{
    double mx = 0, thr;
    int i, last = -1, ng = 0;
    for (i = 0; i < n; i++) if (d[i] > mx) mx = d[i];
    thr = mx * 0.25;
    for (i = 1; i < n - 1; i++) {
        if (d[i] >= d[i-1] && d[i] > d[i+1] && d[i] > thr) {
            if (last < 0) { last = i; continue; }
            if (i - last >= (int)(fs * 0.4)) {
                if (ng < 1024) gaps[ng++] = i - last;
                last = i;
            }
        }
    }
    *ngaps = ng;
    if (ng < 3) return 0;
    qsort(gaps, ng, sizeof(int), cmp_int);
    return 60.0 * fs / gaps[ng / 2];
}

int main(int argc, char **argv)
{
    static double d1[MAXS], d2[MAXS];
    static int gaps[1024];
    double secs = 30.0, fs, bpm, ac1, ac2;
    const char *out = NULL;
    unsigned char b[CAP];
    struct timeval t0, t;
    int i, reads = 0, empty = 0, ng = 0;

    setvbuf(stdout, NULL, _IONBF, 0);
    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-self")) { self_drive = 1; use_hal = 0; }
        else if (!strcmp(argv[i], "-hal")) { self_drive = 0; use_hal = 1; }
        /* -both: hold the sensor enabled through the framework so the driver keeps the rail and
         * the bus alive, but drive the chip ourselves. This is the test of whether the daemon is
         * needed for anything beyond that. */
        else if (!strcmp(argv[i], "-both")) { self_drive = 1; use_hal = 1; }
        else if (!strcmp(argv[i], "-spo2")) spo2_mode = 1;
        else if (!strcmp(argv[i], "-hr")) spo2_mode = 0;
        else if (!strcmp(argv[i], "-o") && i + 1 < argc) out = argv[++i];
        else secs = atof(argv[i]);
    }

    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { perror("/dev/gh_tools"); return 1; }
    signal(SIGTERM, bail); signal(SIGINT, bail);
    signal(SIGSEGV, bail); signal(SIGALRM, bail);
    atexit(stop_all);
    alarm((int)secs + 30);

    {
        unsigned int w[6];
        memset(w, 0, sizeof w);
        w[0] = spo2_mode ? 5 : 4;
        ioctl(fd, CMD, w);
        usleep(400000);
    }

    if (use_hal) {

        ASensorManager *mgr = ASensorManager_getInstance();
        ASensorList list;
        ALooper *lp;
        int n, want = -1;
        n = ASensorManager_getSensorList(mgr, &list);
        for (i = 0; i < n; i++) if (ASensor_getType(list[i]) == 21) want = i;
        if (want < 0) { printf("no heart rate sensor in the list\n"); return 1; }
        sens = list[want];
        lp = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
        q = ASensorManager_createEventQueue(mgr, lp, 3, NULL, NULL);
        ASensorEventQueue_enableSensor(q, sens);
        ASensorEventQueue_setEventRate(q, sens, 10000);
        printf("framework: %s enabled\n", ASensor_getName(sens));
    }

    if (self_drive) {
        /* The daemon's actual start, transcribed from its own i2c traffic. It does NOT write
         * the 48-register table at runtime - that table is loaded elsewhere and writing it here
         * only reset the chip. What starts sampling is the last line: read 0x0022 and set bit 0.
         *
         * 0xdddd is wake (0xc0) and sleep (0xc4); 0xa1 arms the sample stream. */
        unsigned short v = 0, id = 0, ver = 0, st = 0;
        int sel;

        /* Power the part first. ioctl(_IOW('G',2,4), 1) - and the 1 is an immediate value, not
         * a pointer, which is why logging this call by dereferencing it crash-looped the daemon.
         * Without this every register reads zero and it looks like the passthrough is broken;
         * with it, 0x0028 returns 0031 with no daemon running at all. */
        printf("self-drive: power rc=%d\n", ioctl(fd, PWR, 1));
        usleep(300000);

        wr8(0xdddd, 0xc0);
        usleep(20000);
        rd16(0x0028, &id);
        rd16(0x0016, &ver);
        rd16(0x0008, &st);
        printf("self-drive: chip id %04x ver %04x status %04x\n", id, ver, st);

        wr16(0x0182, 0x84db);
        wr16(0x0180, 0x008d);
        rd16(0x00e4, &v);

        for (sel = 0x20; sel <= 0x24; sel += 2) {   /* the calibration reads it does */
            unsigned short got = 0;
            wr16(0x0064, sel);
            wr16(0x006a, 0x0001);
            wr16(0x006a, 0x0000);
            rd16(0x006c, &got);
        }

        rd16(0x0194, &v);
        wr16(0x0194, 0x0003);
        rd16(0x018a, &v);
        wr16(0x018a, 0x08a4);
        wr16(0x018c, 0x005d);

        rd16(0x0084, &v); rd16(0x0118, &v); rd16(0x0136, &v);
        rd16(0x0080, &v); rd16(0x0082, &v); rd16(0x0186, &v);

        wr16(0x0020, 0x0000);
        rd16(0x0022, &v);

        /* The start is two passes, not one, and that is why every single-pass replay failed.
         *
         * Transcribed from the 46 lines before the first `R aaaa` in a capture that produced 77
         * events. The daemon configures the chip twice with *different* values for the same
         * registers - the two sets are two of the tables in its binary, at 0x25aa6 and 0x25c3c -
         * commits the first with `dddd c1`, checks status, then sleeps, wakes, writes the second
         * set and arms with `dddd a1`.
         *
         * Only two values in the whole sequence are absent from every table, so these are the
         * ones it computes: 0x0044 (the FIFO watermark, 200 - exactly what 0x004a reports back)
         * and 0x0002 (the enable, 0xfe30 for the first pass and 0xfe2e for the second).
         *
         * `dddd c1` is the command we never sent. c0 wakes, c4 sleeps, a1 arms the stream,
         * c3 arms a FIFO read - and c1 commits a configuration. */

        /* first pass */
        wr16(0x00de, 0x0000);
        rd16(0x00de, &v);
        wr16(0x00c0, 0x0001);
        rd16(0x0084, &v); rd16(0x0118, &v); rd16(0x0136, &v);
        rd16(0x0080, &v); rd16(0x0082, &v); rd16(0x0186, &v);
        wr8(0xdddd, 0xc4);
        wr8(0xdddd, 0xc0);
        rd16(0x0022, &v);
        wr16(0x0084, 0x0020);
        wr16(0x0118, 0x2828);
        wr16(0x0136, 0x0d20);
        wr16(0x0080, 0x0205);
        wr16(0x0082, 0x00c2);
        wr16(0x0186, 0x0001);
        rd16(0x00c0, &v);
        wr16(0x00c0, 0x0001);
        wr16(0x0002, 0xfe30);
        wr8(0xdddd, 0xc1);                          /* commit */
        wr8(0xdddd, 0xc0);
        rd16(0x0008, &v);
        rd16(0x00c0, &v);
        printf("self-drive: after first pass status=%04x\n", v);

        /* second pass: the values that actually sample */
        wr8(0xdddd, 0xc4);
        wr8(0xdddd, 0xc0);
        rd16(0x0022, &v);
        wr16(0x0084, 0x0023);
        wr16(0x0118, 0x9055);
        wr16(0x0136, 0x0000);
        wr16(0x0080, 0x0605);
        wr16(0x0082, 0x01c6);
        wr16(0x0186, 0x0406);
        rd16(0x0016, &v);
        rd16(0x0016, &v);
        wr16(0x0016, 0x0147);
        wr16(0x0048, 0x0001);
        wr16(0x0044, 0x00c8);                       /* FIFO watermark, 200 samples */
        wr16(0x0002, 0xfe2e);                       /* enable */
        wr8(0xdddd, 0xa1);                          /* arm the sample stream */
        usleep(300000);
        {
            unsigned short st2 = 0, lvl = 0, r22 = 0;
            rd16(0x0008, &st2);
            rd16(0x004a, &lvl);
            rd16(0x0022, &r22);
            printf("self-drive: after start status=%04x fifo level=%u 0x0022=%04x\n",
                   st2, lvl, r22);
        }
    }

    sleep(1);

    printf("%s mode, reading the FIFO for %.0fs - hold still\n",
           spo2_mode ? "spo2 (red/IR)" : "heart rate (green)", secs);
    gettimeofday(&t0, 0);
    for (;;) {
        double el;
        int any = 0;
        gettimeofday(&t, 0);
        el = (t.tv_sec - t0.tv_sec) + (t.tv_usec - t0.tv_usec) / 1e6;
        if (el > secs) break;
        /* The daemon's preamble: arm the read at 0xdddd, check the status, then ask 0x004a how
         * many samples are waiting. Reading the FIFO without this returns one register value
         * over and over, which is what an earlier version of this did for 7763 samples. */
        {
            unsigned short st = 0, lvl = 0;
            int want;
            wr8(0xdddd, 0xc3);
            rd16(0x0008, &st);
            rd16(0x004a, &lvl);
            want = (int)lvl * 3;
            if (want <= 0 || want > 8192) want = CAP;
            while (want > 0) {
                int chunk = want > CAP ? CAP : want;
                if (rdn(FIFO, b, chunk) < 0) { printf("fifo read failed\n"); want = 0; break; }
                reads++;
                for (i = 0; i + 5 < chunk; i += 6) {
                    unsigned int a = ((unsigned)b[i] << 16) | (b[i+1] << 8) | b[i+2];
                    unsigned int c = ((unsigned)b[i+3] << 16) | (b[i+4] << 8) | b[i+5];
                    if (!a && !c) continue;
                    any = 1;
                    if (ns < MAXS) { ch1[ns] = a; ch2[ns] = c; ns++; }
                }
                want -= chunk;
            }
        }
        if (!any) empty++;
        usleep(200000);
    }

    printf("%d reads, %d empty, %d sample pairs\n", reads, empty, ns);
    if (ns < 200) {
        printf("not enough data. on the wrist? and the sensor only measures once per boot\n");
        stop_all();
        return 1;
    }

    fs = ns / secs;
    printf("sample rate about %.1f Hz\n", fs);
    detrend(ch1, d1, ns, (int)fs, &ac1);
    detrend(ch2, d2, ns, (int)fs, &ac2);
    printf("ch1 dc %.0f ac %.0f   ch2 dc %.0f ac %.0f\n",
           mean_of(ch1, ns), ac1, mean_of(ch2, ns), ac2);

    bpm = beats(d1, ns, fs, gaps, &ng);
    if (bpm > 0) {
        double sd = 0, m = 0;
        int k;
        for (k = 0; k < ng; k++) m += gaps[k] / fs * 1000.0;
        m /= ng;
        for (k = 0; k < ng; k++) {
            double v = gaps[k] / fs * 1000.0 - m;
            sd += v * v;
        }
        sd = ng > 1 ? sqrt(sd / ng) : 0;
        printf("heart rate %.1f bpm  (%d intervals, mean %.0f ms, SDNN %.1f ms)\n",
               bpm, ng, m, sd);
    } else {
        printf("no clean beats - too much movement, or the window was too short\n");
    }

    if (spo2_mode) {
        /* Ratio of ratios. R = (AC1/DC1) / (AC2/DC2); the usual linear approximation is
         * SpO2 = 110 - 25R. This needs calibrating against a real oximeter before it means
         * anything - it is printed so the number can be compared, not trusted. */
        double dc1 = mean_of(ch1, ns), dc2 = mean_of(ch2, ns);
        if (dc1 > 0 && dc2 > 0 && ac2 > 0) {
            double R = (ac1 / dc1) / (ac2 / dc2);
            printf("R = %.3f -> spo2 about %.0f %% (UNCALIBRATED)\n", R, 110.0 - 25.0 * R);
        }
    }

    if (out) {
        FILE *f = fopen(out, "w");
        if (f) {
            fprintf(f, "ch1,ch2\n");
            for (i = 0; i < ns; i++) fprintf(f, "%u,%u\n", ch1[i], ch2[i]);
            fclose(f);
            printf("wrote %s\n", out);
        }
    }

    stop_all();
    printf("stopped\n");
    return 0;
}
