/* ppgd - take one heart rate measurement without any vendor code.
 *
 * Starts the GH3011 itself, reads its FIFO on the chip's own interrupt, and prints a single
 * line the launcher can parse:
 *
 *     hr=61 conf=0.36 hz=103.2 samples=4000 windows=3
 *     hr=0 reason=<why> ...      when nothing trustworthy came out
 *
 * The whole start sequence lives in seq.h, generated from a capture of the vendor daemon doing
 * it. Hand-picking a subset of those writes never worked: the daemon issues 129 writes before
 * its first burst, and leaving out the configuration table leaves the chip awake but idle.
 *
 * The vendor daemon must not be running - both would drive the same chip. The caller is
 * responsible for that; this only refuses to make things worse by always stopping the sensor on
 * the way out, including on a signal. Leaving it lit on someone's wrist is the one failure here
 * that costs the wearer something.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/time.h>

#define PWR  0x40044702u    /* GH_IOC_ENABLE_POWER, immediate 1/0 - not a pointer */
#define IRQ  0x40044707u    /* GH_IOC_ENABLE_IRQ                                  */
#define WAIT 0x00004701u    /* blocks until the FIFO reaches the 0x0044 watermark */
#define XFER 0xc0084704u    /* i2c passthrough                                    */
#define MODE 0x40184709u    /* _IOW(G,9,24): 4 = green only, 5 = red + IR         */
#define ADDR 0x14
#define MAXS 12000

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };

static int fd = -1;
static void on_alarm(int s) { (void)s; }        /* no SA_RESTART: unblocks a stuck wait */

#include "seq.h"

static int wr(unsigned char *p, int n)
{ struct msg m; struct rdwr r; m.addr=ADDR; m.flags=0; m.len=n; m.buf=p; r.msgs=&m; r.n=1;
  return ioctl(fd, XFER, &r); }
static int wr16(unsigned short g, unsigned short v)
{ unsigned char p[4]; p[0]=g>>8; p[1]=g; p[2]=v>>8; p[3]=v; return wr(p,4); }
static int wr8(unsigned short g, unsigned char v)
{ unsigned char p[3]; p[0]=g>>8; p[1]=g; p[2]=v; return wr(p,3); }
static int rdn(unsigned short g, unsigned char *o, int n)
{
    unsigned char a[2];
    struct msg m[2]; struct rdwr r;
    a[0]=g>>8; a[1]=g;
    memset(o,0,n);
    m[0].addr=ADDR; m[0].flags=0; m[0].len=2; m[0].buf=a;
    m[1].addr=ADDR; m[1].flags=1; m[1].len=n; m[1].buf=o;
    r.msgs=m; r.n=2;
    return ioctl(fd, XFER, &r);
}
static int rd16(unsigned short g, unsigned short *v)
{ unsigned char b[2]; if (rdn(g,b,2)<0) return -1; *v=(unsigned short)((b[0]<<8)|b[1]); return 0; }

static void stop_chip(void)
{
    if (fd < 0) return;
    wr8(0xdddd, 0xc4);
    ioctl(fd, IRQ, 0);
    ioctl(fd, PWR, 0);
}
static void bail(int s) { (void)s; stop_chip(); _exit(2); }

static unsigned int ch1[MAXS], ch2[MAXS];
static int ns;

/* Subtract a one-second baseline and invert: this is a reflective sensor, so the count falls at
 * systole and the trace is upside down as read. */
static void detrend(double *out, int n, int w)
{
    static double tmp[MAXS];
    int i;
    for (i = 0; i < n; i++) {
        int a = i - w/2, b = i + w/2, k, cnt = 0;
        double s = 0;
        if (a < 0) a = 0;
        if (b > n) b = n;
        for (k = a; k < b; k++) { s += ch1[k]; cnt++; }
        tmp[i] = s / cnt - (double)ch1[i];
    }
    /* A light smooth. Without it the sample-to-sample noise rides on top of a pulse of a few
     * tens of counts and drags the autocorrelation confidence below any sensible threshold -
     * the same analysis scored 0.33-0.36 smoothed and rejected every window unsmoothed. */
    for (i = 0; i < n; i++) {
        int a = i - 2, b = i + 3, k, cnt = 0;
        double s = 0;
        if (a < 0) a = 0;
        if (b > n) b = n;
        for (k = a; k < b; k++) { s += tmp[k]; cnt++; }
        out[i] = s / cnt;
    }
}

/* Dominant period by autocorrelation. Peak-picking is defeated by the motion in an ordinary
 * recording; autocorrelation survives it, and the confidence says whether to believe the answer. */
static double period_bpm(const double *seg, int n, double fs, double *conf)
{
    double mean = 0, energy = 0, best = 0;
    int i, lag, blag = 0, lo = (int)(fs * 0.42), hi = (int)(fs * 1.45);
    /* The upper limit matters: extending the search to 1.6 s lets the first subharmonic of a
     * real pulse win, which is how a 65 bpm wearer was reported as 40. 1.45 s is 41 bpm, below
     * any resting rate this will meet. */
    for (i = 0; i < n; i++) mean += seg[i];
    mean /= n;
    for (i = 0; i < n; i++) energy += (seg[i]-mean) * (seg[i]-mean);
    if (energy <= 0 || hi >= n) return 0;
    for (lag = lo; lag < hi; lag++) {
        double s = 0;
        for (i = 0; i + lag < n; i++) s += (seg[i]-mean) * (seg[i+lag]-mean);
        s /= (n - lag);
        if (s > best) { best = s; blag = lag; }
    }
    if (!blag) return 0;
    *conf = best / (energy / n);
    return 60.0 * fs / blag;
}

static int cmp_d(const void *a, const void *b)
{ double x = *(const double*)a, y = *(const double*)b; return x < y ? -1 : (x > y); }

int main(int argc, char **argv)
{
    static double d[MAXS], rates[64];
    double secs = argc > 1 ? atof(argv[1]) : 30.0, fs, elapsed;
    struct timeval t0, t1;
    unsigned short v = 0, st = 0, lvl = 0;
    unsigned char buf[240];
    int i, nrates = 0, rounds = 0, timeouts = 0;
    static double burst_hz[512];
    int nburst = 0;
    unsigned short gain = 0x9055;   /* the value the start sequence applies */
    int settled_at = 0;             /* first sample after the gain stopped moving */
    struct timeval tprev;
    const char *csvpath = argc > 2 ? argv[2] : NULL;
    int want_spo2 = (argc > 3 && argv[3][0] == 's');

    setvbuf(stdout, NULL, _IONBF, 0);
    signal(SIGTERM, bail); signal(SIGINT, bail); signal(SIGSEGV, bail);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { printf("hr=0 reason=no_device\n"); return 1; }
    atexit(stop_chip);

    if (ioctl(fd, PWR, 1) < 0) { printf("hr=0 reason=power_failed\n"); return 1; }
    ioctl(fd, IRQ, 1);

    /* Set the driver mode before the register sequence. The SpO2 and heart-rate starts are
     * byte-identical - 242 operations, not one differing write - so what selects one LED or two
     * is not in the registers at all: it is this ioctl. Leaving it unset inherits whatever the
     * last measurement used, which is how one run read 52 and the next 59 with no code change.
     * Mode 4 is green only and leaves the second channel flat at 5 counts; mode 5 drives red and
     * IR, which is what a ratio of ratios needs. */
    {
        unsigned int w[6];
        memset(w, 0, sizeof w);
        w[0] = want_spo2 ? 5 : 4;
        ioctl(fd, MODE, w);
    }
    usleep(300000);

    for (i = 0; i < NSEQ; i++) {
        if (SEQ[i].op == 0)      wr16(SEQ[i].reg, SEQ[i].val);
        else if (SEQ[i].op == 1) wr8(SEQ[i].reg, (unsigned char)SEQ[i].val);
        else                     rd16(SEQ[i].reg, &v);
    }

    gettimeofday(&t0, 0);
    tprev = t0;
    for (;;) {
        struct sigaction sa;
        int left, k, before;

        gettimeofday(&t1, 0);
        elapsed = (t1.tv_sec-t0.tv_sec) + (t1.tv_usec-t0.tv_usec)/1e6;
        if (elapsed > secs || ns >= MAXS - 400) break;

        before = ns;
        wr8(0xdddd, 0xc3);
        memset(&sa, 0, sizeof sa);
        sa.sa_handler = on_alarm;
        sigaction(SIGALRM, &sa, NULL);
        alarm(3);
        if (ioctl(fd, WAIT, 0) < 0) timeouts++;
        alarm(0);

        rd16(0x0008, &st);
        rd16(0x004a, &lvl);
        left = (int)lvl * 3;
        if (left > 3000) left = 3000;
        while (left > 0 && ns < MAXS) {
            int want = left > 240 ? 240 : left;
            if (rdn(0xaaaa, buf, want) < 0) { left = 0; break; }
            for (k = 0; k + 5 < want && ns < MAXS; k += 6) {
                unsigned int c1 = ((unsigned)buf[k]<<16)|(buf[k+1]<<8)|buf[k+2];
                unsigned int c2 = ((unsigned)buf[k+3]<<16)|(buf[k+4]<<8)|buf[k+5];
                if (c1) { ch1[ns] = c1; ch2[ns] = c2; ns++; }
            }
            left -= want;
        }
        /* Gain control, which the daemon does after every burst and we did not do at all.
         *
         * It reads the applied gain back from 0x0122 and writes a new one to 0x0118, walking it
         * 9055 -> 2828 -> 9055 -> 4f3c -> 4645 across one measurement. The two bytes look like
         * one LED current per channel, which fits our second channel sitting flat at 5 counts
         * while the vendor's moves by 70: pinning the gain at its initial value leaves that LED
         * underdriven for the whole measurement.
         *
         * Goodix's own control law is not recoverable from the trace, so this is a plain
         * proportional step towards a target amplitude - enough to keep the signal in range,
         * which is all the rate estimate needs.
         */
        if (ns > before + 20) {
            unsigned int lo1 = ch1[before], hi1 = ch1[before];
            unsigned int lo2 = ch2[before], hi2 = ch2[before];
            int k3;
            for (k3 = before; k3 < ns; k3++) {
                if (ch1[k3] < lo1) lo1 = ch1[k3];
                if (ch1[k3] > hi1) hi1 = ch1[k3];
                if (ch2[k3] < lo2) lo2 = ch2[k3];
                if (ch2[k3] > hi2) hi2 = ch2[k3];
            }
            {
                /* Drive the DC level down out of saturation, not the amplitude up.
                 *
                 * At the gain the start sequence applies, both channels sit railed near
                 * 3,210,580 and read almost flat - two or three counts. That looks exactly like
                 * "not enough gain", and an earlier version of this responded by raising it,
                 * which drove them further into the rail. It is the opposite: the vendor's AGC
                 * *lowers* the gain (9055 -> 4f3c -> 4645), the DC falls to about 3,194,400, and
                 * the pulse appears - the second channel going from 3 counts to 68 within one
                 * burst of the change.
                 *
                 * So saturation is judged by the DC level, which is unambiguous, rather than by
                 * amplitude, which reads the same whether a channel is dark or clipped.
                 */
                double dc1 = 0, dc2 = 0;
                int a1 = (int)(hi1 - lo1), a2 = (int)(hi2 - lo2);
                int g1 = (gain >> 8) & 0xff, g2 = gain & 0xff;
                int k4;
                for (k4 = before; k4 < ns; k4++) { dc1 += ch1[k4]; dc2 += ch2[k4]; }
                dc1 /= (ns - before);
                dc2 /= (ns - before);

                /* One-way: back off out of saturation and then stop.
                 *
                 * Bracketing the vendor's operating point was tried and is worse. One gain step
                 * moves the DC by about 9500 counts, so any deadband narrow enough to hold that
                 * point is narrower than a single step, and the loop oscillates - the usable
                 * window fell from 3400 samples to 1200. This settles about 33,000 counts below
                 * where the daemon sits, which costs some signal, but it settles. */
                if (dc1 > 3200000.0 && g1 > 0x20) g1 -= 12;
                else if (dc1 < 3150000.0 && a1 < 40 && g1 < 0xe0) g1 += 6;
                if (dc2 > 3200000.0 && g2 > 0x20) g2 -= 12;
                else if (dc2 < 3150000.0 && a2 < 40 && g2 < 0xe0) g2 += 6;

                {
                    unsigned short newgain = (unsigned short)((g1 << 8) | g2);
                    if (newgain != gain) {
                        /* Every gain change steps the DC by about 9500 counts - two orders of
                         * magnitude more than the pulse - so the settling period is unusable and
                         * has to be dropped rather than filtered. Analysis starts after the gain
                         * has stopped moving; including the transient put the rate estimate at
                         * the edge of its search range. */
                        settled_at = ns;
                        gain = newgain;
                        wr16(0x0136, 0x0000);
                        wr16(0x0118, gain);
                    }
                }
            }
        }

        /* The chip's rate, measured from how fast one burst follows the last. Deriving it from
         * total samples over total time is wrong the moment a burst is missed: the rate collapses
         * and, because bpm is 60*fs/lag, it drags the heart rate down with it. That is how one
         * run reported 49 where the wearer's pulse was 65. */
        {
            struct timeval tb;
            double gap, got;
            gettimeofday(&tb, 0);
            gap = (tb.tv_sec-tprev.tv_sec) + (tb.tv_usec-tprev.tv_usec)/1e6;
            got = (double)(ns - before);
            if (gap > 0.05 && gap < 5.0 && got > 20 && nburst < 512)
                burst_hz[nburst++] = got / gap;
            tprev = tb;
        }
        rounds++;
        if (timeouts > 4) break;                  /* the chip is not producing bursts */
    }
    gettimeofday(&t1, 0);
    elapsed = (t1.tv_sec-t0.tv_sec) + (t1.tv_usec-t0.tv_usec)/1e6;
    stop_chip();

    if (ns < 600 || elapsed <= 0) {
        printf("hr=0 reason=too_few_samples samples=%d rounds=%d timeouts=%d\n",
               ns, rounds, timeouts);
        return 1;
    }
    /* Measured, never assumed: the loop is paced by the chip's interrupt, and an assumed rate
     * scales the answer in direct proportion. */
    if (nburst >= 3) {
        qsort(burst_hz, nburst, sizeof burst_hz[0], cmp_d);
        fs = burst_hz[nburst/2];              /* median burst cadence: robust to a missed burst */
    } else {
        fs = ns / elapsed;
    }

    if (csvpath) {
        FILE *f = fopen(csvpath, "w");
        if (f) { for (i = 0; i < ns; i++) fprintf(f, "%u\n", ch1[i]); fclose(f); }
    }

    /* A two-second baseline, not one. Subtracting a one-second moving average is a high-pass
     * at about 1 Hz, and a 65 bpm pulse is 1.08 Hz - so it was attenuating the pulse itself and
     * leaving the slow drift behind, which biases the autocorrelation toward long lags. That is
     * why three runs on a 65-70 bpm wearer returned 58, 46 and 42. */
    {
        /* Drop the settling period, plus a second for the baseline filter to fill. */
        int skip = settled_at + (int)fs;
        if (skip > 0 && ns - skip > (int)(fs * 12)) {
            memmove(ch1, ch1 + skip, (ns - skip) * sizeof ch1[0]);
            memmove(ch2, ch2 + skip, (ns - skip) * sizeof ch2[0]);
            ns -= skip;
        }
    }
    detrend(d, ns, (int)(fs * 2));
    {
        /* Select by confidence, not by stillness. Picking the calmest windows sounds right and
         * is wrong here: the stillest stretches of a real recording have a spread of two counts
         * because there is no pulse in them at all, and they score 0.09-0.16. The windows that
         * actually carry the pulse have far more amplitude - motion included - and score 0.25.
         * Measured on one recording: quietest-first found nothing, this finds 63, 63, 63 bpm
         * against a wearer counting 65-70. */
        int win = (int)(fs * 10), step = win / 4, s;

        for (s = 0; s + win < ns && nrates < 64; s += step) {
            double conf = 0;
            double bpm = period_bpm(d + s, win, fs, &conf);
            /* 0.04, not 0.20. The pulse here is tens of counts on a drifting baseline, so a
             * correct peak scores about 0.07 - measured off the full correlation curve, whose
             * maximum sat at exactly the wearer's rate while every threshold above 0.1
             * rejected it. What separates signal from noise is the agreement check below, not
             * the height of any single peak. */
            if (bpm >= 40 && bpm <= 180 && conf > 0.04) rates[nrates++] = bpm;
        }
    }
    if (nrates < 3) {
        printf("hr=0 reason=no_agreement windows=%d samples=%d hz=%.1f\n", nrates, ns, fs);
        return 1;
    }
    qsort(rates, nrates, sizeof rates[0], cmp_d);
    {
        /* Take the largest cluster of windows that agree, not the median of everything.
         *
         * A moving wrist produces some windows that lock onto the pulse and some that lock onto
         * the movement, and the second group is not noise around the first - it is a different
         * answer entirely. Averaging the two gives a number belonging to neither: three runs on
         * the same wearer gave medians of 66, 47 and 53 while the true rate was steady.
         *
         * A cluster is windows within 10% of each other; the biggest one wins, and it has to
         * hold at least a third of the windows for the reading to stand.
         */
        int bi = 0, bn = 0, a2, b2;
        for (a2 = 0; a2 < nrates; a2++) {
            int cnt = 0;
            for (b2 = a2; b2 < nrates && rates[b2] <= rates[a2] * 1.10; b2++) cnt++;
            if (cnt > bn) { bn = cnt; bi = a2; }
        }
        if (bn * 3 < nrates || bn < 3) {
            printf("hr=0 reason=no_cluster best=%d of %d windows median=%.0f hz=%.1f samples=%d\n",
                   bn, nrates, rates[nrates/2], fs, ns);
            return 1;
        }
        {
            double cl[64];
            int c2;
            for (c2 = 0; c2 < bn; c2++) cl[c2] = rates[bi + c2];
            for (c2 = 0; c2 < bn; c2++) rates[c2] = cl[c2];
            nrates = bn;
        }
    }
    {
        double med = rates[nrates/2];
        /* Interquartile spread, so one bad window cannot veto the rest, but genuine
         * disagreement still refuses. Without this the tool printed 42 bpm from four windows
         * that disagreed by 41 - a median of noise, and worse than no answer, because a
         * confident wrong pulse gets believed. */
        double q1 = rates[nrates/4], q3 = rates[(3*nrates)/4 < nrates ? (3*nrates)/4 : nrates-1];
        double iqr = q3 - q1;
        double spread = rates[nrates-1] - rates[0];
        double tol = med * 0.12 > 6.0 ? med * 0.12 : 6.0;

        if (iqr > tol) {
            printf("hr=0 reason=windows_disagree median=%.0f iqr=%.0f spread=%.0f windows=%d"
                   " hz=%.1f samples=%d\n", med, iqr, spread, nrates, fs, ns);
            return 1;
        }
        printf("hr=%.0f spread=%.0f hz=%.1f samples=%d windows=%d rounds=%d timeouts=%d\n",
               med, spread, fs, ns, nrates, rounds, timeouts);
    }
    return 0;
}
