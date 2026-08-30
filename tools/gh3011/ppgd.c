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

/* The code a channel reads with no light on it.
 *
 * Both channels floor at 0x300000 exactly: driven dark by a low gain, channel 1 read 3145747 and
 * 3145747 again at two different gains while channel 2 tracked the gain properly. So this is the
 * converter's zero-light offset and not part of the signal.
 *
 * It matters because R is (AC/DC) per channel, and dividing by a DC that is 98% pedestal makes
 * the ratio meaningless: channel 1 carries 3,916 counts of light and channel 2 carries 45,992,
 * a factor of twelve, but as raw codes they are 3,149,644 and 3,191,720 - within 1.3% of each
 * other. Every R printed before this was that comparison.
 */
#define DARK_CODE 3145728.0

/* Resting ratio-of-ratios for this sensor, measured with bin_amp on a still, healthy wrist.
 * See the SpO2 comment below: this sets the offset of the whole scale, so it is the one number
 * to revisit if saturation ever reads implausibly. */
#define R_REST 0.35

static int fd = -1;
static void on_alarm(int s) { (void)s; }        /* no SA_RESTART: unblocks a stuck wait */

#include "seq.h"
#include "seq_hr.h"

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
/* Which channel actually carries the pulse varies from run to run.
 *
 * One shared gain moves both channels together and they sit about 47,600 counts apart, so
 * whichever one the gain brings into range, the other is dark. Measured across runs: once
 * ch1=3,199,027 with 100 counts of pulse while ch2 sat dark at 3,210,576 with 4; the next run
 * ch2=3,197,361 with 70 counts while ch1 was dark at 3,149,730 with 8.
 *
 * Analysing ch1 unconditionally therefore reads a heart rate out of the dark channel's noise
 * whenever the gain lands the other way, which is the likeliest source of the low bias against
 * the vendor. Pick the channel with the larger pulsatile amplitude instead.
 */
static const unsigned int *pick_channel(int n)
{
    int i, blk = n / 8, k;
    double best1 = 0, best2 = 0;
    if (blk < 20) blk = n;
    for (i = 0; i + blk <= n; i += blk) {
        unsigned int lo1 = ch1[i], hi1 = ch1[i], lo2 = ch2[i], hi2 = ch2[i];
        for (k = i; k < i + blk; k++) {
            if (ch1[k] < lo1) lo1 = ch1[k];
            if (ch1[k] > hi1) hi1 = ch1[k];
            if (ch2[k] < lo2) lo2 = ch2[k];
            if (ch2[k] > hi2) hi2 = ch2[k];
        }
        best1 += hi1 - lo1;
        best2 += hi2 - lo2;
    }
    return best2 > best1 ? ch2 : ch1;
}

static const unsigned int *src = ch1;

static void detrend(double *out, int n, int w)
{
    static double tmp[MAXS];
    int i;
    for (i = 0; i < n; i++) {
        int a = i - w/2, b = i + w/2, k, cnt = 0;
        double s = 0;
        if (a < 0) a = 0;
        if (b > n) b = n;
        for (k = a; k < b; k++) { s += src[k]; cnt++; }
        tmp[i] = s / cnt - (double)src[i];
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
/* Pulsatile amplitude at one frequency, by Goertzel's recurrence.
 *
 * Returns the height of the component at f, having removed dc first. Only ever used as a ratio
 * between two channels measured the same way, so the factor between "amplitude of the
 * fundamental" and "peak-to-peak swing" cancels and does not need to be right in absolute terms.
 */
static int cmp_d(const void *a, const void *b)
{ double x = *(const double*)a, y = *(const double*)b; return x < y ? -1 : (x > y); }

static double bin_amp(const unsigned int *x, int n, double dc, double fs, double f)
{
    double w, c, s0, s1 = 0, s2 = 0, p;
    int i;

    if (n < 8 || f <= 0.0 || f >= fs / 2.0) return 0.0;
    w = 2.0 * 3.14159265358979323846 * f / fs;
    c = 2.0 * cos(w);
    for (i = 0; i < n; i++) {
        s0 = (x[i] - dc) + c * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    p = s1 * s1 + s2 * s2 - c * s1 * s2;
    return p > 0.0 ? 2.0 * sqrt(p) / n : 0.0;
}

/* The same amplitude, but averaged over short windows instead of integrated across the record.
 *
 * One bin over the whole forty seconds resolves 0.025 Hz, and a heart does not hold still to a
 * fortieth of a hertz - the breath alone moves it a couple of bpm. The pulse energy smears across
 * neighbouring bins and the single bin at the mean rate keeps almost none of it: measured that
 * way a channel visibly swinging 2700 counts reported an amplitude of 1.
 *
 * Six-second windows resolve 0.17 Hz, wide enough to hold the pulse wherever it wanders within
 * one window, and averaging magnitudes rather than complex values means separate windows are not
 * required to stay in phase with each other. Each window has its own mean removed, so a baseline
 * that drifts across the record does not leak into any of them.
 */
static double band_amp(const unsigned int *x, int n, double fs, double f)
{
    int w = (int)(fs * 6.0), step, i, k, nw = 0;
    double sum = 0.0, dc;

    if (w < 16) w = 16;
    if (w > n) w = n;
    step = w / 2;
    if (step < 1) step = 1;

    for (i = 0; i + w <= n; i += step) {
        dc = 0.0;
        for (k = i; k < i + w; k++) dc += x[k];
        dc /= w;
        sum += bin_amp(x + i, w, dc, fs, f);
        nw++;
    }
    return nw ? sum / nw : 0.0;
}

/* The strongest pulsatile amplitude anywhere a heart could plausibly be.
 *
 * band_amp needs a rate, and the rate is exactly what the failure paths do not have. Scanning
 * 40 to 180 bpm and keeping the best gives each channel's pulsatility without one - which is what
 * makes a failed run still worth something when the question being asked is "is this channel
 * carrying any signal at all", as it is while chasing the LED current behind the red channel.
 */
/* R over a record, as the median of the sub-windows it splits into.
 *
 * One ratio over one pass takes whatever happened during it. Eight seconds of a resting wrist
 * gave channel 1 amplitudes of 13, 16, 46 and 105 counts on consecutive runs - a factor of eight
 * from contact and small movements, not from anything in the blood - and a single R inherits all
 * of it. Measured that way R came back between 0.26 and 1.0 on a wearer who never moved.
 *
 * Splitting the pass and taking the middle answer fixes the part of that which is transient. A
 * few seconds of a shifting cuff or a swallow moves one or two windows and leaves the median
 * where it was, where an average would carry it. The spread between windows is returned too,
 * because it says whether the measurement held still enough to be worth anything - a median of
 * six windows that disagree wildly is not a better number than no number.
 *
 * Six-second windows, half overlapping: long enough for band_amp to resolve the pulse, short
 * enough that a 25 second pass yields seven of them.
 */
static int ratio_windows(const unsigned int *a, const unsigned int *b, int n, double fs,
                         double bpm, double *rmed, double *rspread)
{
    static double rs[64];
    int w = (int)(fs * 6.0), step, i, k, nr = 0;

    *rmed = 0;
    *rspread = 0;
    if (w < 16 || bpm < 30.0 || bpm > 210.0) return 0;
    if (w > n) w = n;
    step = w / 2;
    if (step < 1) step = 1;

    for (i = 0; i + w <= n && nr < 64; i += step) {
        double d1 = 0, d2 = 0, l1, l2, x1, x2;
        for (k = i; k < i + w; k++) { d1 += a[k]; d2 += b[k]; }
        d1 /= w;
        d2 /= w;
        l1 = d1 - DARK_CODE;
        l2 = d2 - DARK_CODE;
        if (l1 <= 100.0 || l2 <= 100.0) continue;
        x1 = band_amp(a + i, w, fs, bpm / 60.0);
        x2 = band_amp(b + i, w, fs, bpm / 60.0);
        if (x1 <= 0 || x2 <= 0) continue;
        rs[nr++] = (x1 / l1) / (x2 / l2);
    }
    if (nr < 3) return 0;
    qsort(rs, nr, sizeof rs[0], cmp_d);
    *rmed = rs[nr / 2];
    *rspread = rs[(nr * 3) / 4] - rs[nr / 4];      /* the middle half, not the extremes */
    return nr;
}

/* Both channels' pulse amplitude, measured against the shape of the pulse itself.
 *
 * band_amp asks how much of one sine wave is present, which throws away everything about a beat
 * that is not its fundamental - and a pulse is not a sine wave. On the strong channel that hardly
 * matters. On channel 1, which even balanced carries a fraction of the light, the difference
 * between using a quarter of the beat's energy and all of it is the difference between a ratio
 * and a coin toss.
 *
 * So build the beat first. The strong channel gives a clean ensemble average - the same one the
 * pressure is measured on - and that becomes a template. Projecting each channel onto it asks
 * "how much of this exact shape is here", which uses every harmonic the beat has and rejects
 * anything that does not look like a pulse, whatever frequency it sits at. That is a matched
 * filter, and for a known shape in noise there is nothing better.
 *
 * Per beat, then the median across beats: one bad beat cannot move it, and the answer does not
 * depend on where the pass happened to start.
 */
static int matched_amps(const double *strong, const double *w1, const double *w2,
                        int n, double fs, double bpm, double *o1, double *o2)
{
    enum { MAXB2 = 256, MAXW2 = 512 };
    static int pk[MAXB2];
    static double tpl[MAXW2], acc[MAXW2];
    static double p1[MAXB2], p2[MAXB2];
    int npk = 0, i, j, k, T, pre, post, wlen, used = 0, n1 = 0, n2 = 0;
    double mx = 0, thr, tt = 0;

    *o1 = 0;
    *o2 = 0;
    if (bpm < 30.0 || bpm > 210.0 || n < 64) return 0;
    T = (int)(fs * 60.0 / bpm);
    if (T < 8) return 0;
    pre = (int)(T * 0.35);
    post = (int)(T * 0.65);
    wlen = pre + post + 1;
    if (wlen > MAXW2 || wlen < 8) return 0;

    /* Peaks on the strong channel, against a threshold an artefact cannot lift: see the note in
     * pulse_shape on why this is the median of the local maxima and not the largest of them. */
    {
        static double tops[MAXB2];
        int nt = 0;
        for (i = 1; i < n - 1; i++) {
            if (strong[i] >= strong[i-1] && strong[i] > strong[i+1] && strong[i] > 0
                    && nt < MAXB2) tops[nt++] = strong[i];
        }
        if (nt < 4) return 0;
        qsort(tops, nt, sizeof tops[0], cmp_d);
        mx = tops[nt / 2];
    }
    thr = mx * 0.4;
    for (i = 1; i < n - 1; i++) {
        if (!(strong[i] >= strong[i-1] && strong[i] > strong[i+1] && strong[i] > thr)) continue;
        if (npk > 0 && i - pk[npk-1] < T / 2) continue;
        if (i - pre < 0 || i + post >= n) continue;
        if (npk < MAXB2) pk[npk++] = i;
    }
    if (npk < 4) return 0;

    /* The template: the average beat, normalised so the projection has a fixed meaning. */
    for (j = 0; j < wlen; j++) acc[j] = 0.0;
    for (k = 0; k < npk; k++) {
        const double *seg = strong + pk[k] - pre;
        double lo = seg[0], hi = seg[0], rng;
        for (j = 1; j < wlen; j++) {
            if (seg[j] < lo) lo = seg[j];
            if (seg[j] > hi) hi = seg[j];
        }
        rng = hi - lo;
        if (rng <= 0) continue;
        for (j = 0; j < wlen; j++) acc[j] += (seg[j] - lo) / rng;
        used++;
    }
    if (used < 4) return 0;
    {
        double m = 0;
        for (j = 0; j < wlen; j++) tpl[j] = acc[j] / used;
        for (j = 0; j < wlen; j++) m += tpl[j];
        m /= wlen;
        for (j = 0; j < wlen; j++) tpl[j] -= m;      /* zero mean: a DC offset is not a pulse */
        for (j = 0; j < wlen; j++) tt += tpl[j] * tpl[j];
        if (tt <= 0) return 0;
    }

    /* How much of that shape each channel carries, beat by beat. */
    for (k = 0; k < npk; k++) {
        int at = pk[k] - pre;
        double d1 = 0, d2 = 0;
        if (at < 0 || at + wlen > n) continue;
        for (j = 0; j < wlen; j++) {
            d1 += w1[at + j] * tpl[j];
            d2 += w2[at + j] * tpl[j];
        }
        if (n1 < MAXB2) p1[n1++] = d1 / tt;
        if (n2 < MAXB2) p2[n2++] = d2 / tt;
    }
    if (n1 < 4 || n2 < 4) return 0;
    qsort(p1, n1, sizeof p1[0], cmp_d);
    qsort(p2, n2, sizeof p2[0], cmp_d);
    *o1 = p1[n1 / 2];
    *o2 = p2[n2 / 2];
    return used;
}

/* One frequency's amplitude in an already-detrended signal, windowed as band_amp is.
 *
 * The unsigned-int version takes a raw channel and removes each window's mean, which handles a
 * level but not a slope. Over forty seconds the baseline wanders far more than the pulse moves,
 * and what leaks into the low end of the search is larger than anything the heart contributes -
 * so a spectrum run on the raw channel put its peak at the bottom of the range on every one of
 * twelve recordings. Detrending first is the fix, and detrending is already done for everything
 * else, so this only needs to accept the result.
 */
static double band_amp_d(const double *x, int n, double fs, double f)
{
    int w = (int)(fs * 6.0), step, i, k, nw = 0;
    double sum = 0.0, mean, wsum, c, s0, s1, s2, om, p;

    if (w < 16 || f <= 0.0 || f >= fs / 2.0) return 0.0;
    if (w > n) w = n;
    step = w / 2;
    if (step < 1) step = 1;
    om = 2.0 * 3.14159265358979323846 * f / fs;
    c = 2.0 * cos(om);

    for (i = 0; i + w <= n; i += step) {
        mean = 0.0;
        for (k = i; k < i + w; k++) mean += x[k];
        mean /= w;
        s1 = 0.0;
        s2 = 0.0;
        for (k = i; k < i + w; k++) {
            s0 = (x[k] - mean) + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        p = s1 * s1 + s2 * s2 - c * s1 * s2;
        wsum = p > 0.0 ? 2.0 * sqrt(p) / w : 0.0;
        sum += wsum;
        nw++;
    }
    return nw ? sum / nw : 0.0;
}

/* The rate, from the spectrum of a detrended record with harmonics counted in. */
static double spectral_bpm_d(const double *d, int n, double fs, double *conf)
{
    double best = 0, bestbpm = 0, second = 0, bpm;

    *conf = 0;
    if (n < 256 || fs <= 0) return 0;

    for (bpm = 32.0; bpm <= 220.0; bpm += 0.5) {
        double f = bpm / 60.0;
        double p = band_amp_d(d, n, fs, f);
        if (2.0 * f < fs / 2.0) p += 0.6 * band_amp_d(d, n, fs, 2.0 * f);
        if (3.0 * f < fs / 2.0) p += 0.3 * band_amp_d(d, n, fs, 3.0 * f);
        if (p > best) { second = best; best = p; bestbpm = bpm; }
        else if (p > second) second = p;
    }
    if (bestbpm <= 34.0 || bestbpm >= 218.0 || best <= 0) return 0;

    /* Half of what won scores well by borrowing its harmonics, so prefer the half only when it
     * genuinely competes. */
    if (bestbpm / 2.0 >= 36.0) {
        double h = bestbpm / 2.0, f = h / 60.0;
        double p = band_amp_d(d, n, fs, f);
        if (2.0 * f < fs / 2.0) p += 0.6 * band_amp_d(d, n, fs, 2.0 * f);
        if (3.0 * f < fs / 2.0) p += 0.3 * band_amp_d(d, n, fs, 3.0 * f);
        if (p > best * 0.85) bestbpm = h;
    }

    *conf = second > 0 ? best / second : 10.0;
    return bestbpm;
}

/* The rate, from the spectrum of the whole record with harmonics counted in.
 *
 * The windowed autocorrelation this sits beside estimates a rate in each window and refuses
 * unless the windows agree. That is safe and it is too conservative: motion makes windows
 * disagree, so a wrist that moved is told nothing at all rather than told approximately. A watch
 * meant to be worn running cannot decline every time the arm swings.
 *
 * A spectrum is steadier because it uses the whole record at once rather than asking several
 * short pieces to concur. Harmonics are what make it discriminating: a pulse is not a sine wave,
 * so it puts energy at its rate and again at twice and three times it, while a movement artefact
 * generally does not line up that way. Adding a candidate to its own harmonics therefore lifts
 * real rates above artefacts that happen to be larger at any single frequency.
 *
 * Half-rate errors are the classic failure of harmonic summing - f/2 scores well because its
 * harmonics land on f - so a candidate only wins against its own double if it beats it clearly.
 */
static double spectral_bpm(const unsigned int *x, int n, double fs, double *conf)
{
    double best = 0, bestbpm = 0, second = 0, bpm, dc = 0;
    int i;

    *conf = 0;
    if (n < 256 || fs <= 0) return 0;
    for (i = 0; i < n; i++) dc += x[i];
    dc /= n;

    /* The search runs wider than the answer is allowed to be.
     *
     * A peak search returns its boundary whenever the truth lies outside, or whenever there is no
     * peak at all - the edge wins by default because nothing beyond it competes. That is how the
     * autocorrelation once reported 146 with every window agreeing, and the same trap caught this
     * one: replayed over twelve recordings it answered 40 on six of them, the bottom of its
     * range, on a wearer whose rate was 50 to 53 throughout.
     *
     * So it searches 32 to 220 and refuses anything that lands within two of either end. A rate
     * outside 34 to 218 is not one this sensor should be believing anyway.
     */
    for (bpm = 32.0; bpm <= 220.0; bpm += 0.5) {
        double f = bpm / 60.0;
        double p = band_amp(x, n, fs, f);
        if (2.0 * f < fs / 2.0) p += 0.6 * band_amp(x, n, fs, 2.0 * f);
        if (3.0 * f < fs / 2.0) p += 0.3 * band_amp(x, n, fs, 3.0 * f);
        if (p > best) { second = best; best = p; bestbpm = bpm; }
        else if (p > second) second = p;
    }
    if (bestbpm <= 0 || best <= 0) return 0;
    if (bestbpm <= 34.0 || bestbpm >= 218.0) return 0;      /* the edge, not an answer */

    /* Guard the octave. If half of what won scores nearly as well, the true rate is the half:
     * the harmonics of f/2 include f, so f can only win by borrowing them. */
    if (bestbpm / 2.0 >= 36.0) {
        double h = bestbpm / 2.0, f = h / 60.0;
        double p = band_amp(x, n, fs, f);
        if (2.0 * f < fs / 2.0) p += 0.6 * band_amp(x, n, fs, 2.0 * f);
        if (3.0 * f < fs / 2.0) p += 0.3 * band_amp(x, n, fs, 3.0 * f);
        if (p > best * 0.85) bestbpm = h;
    }

    /* How far the winner stands above the rest of the band, which is what says whether to
     * believe it. */
    *conf = second > 0 ? best / second : 10.0;
    return bestbpm;
}

static double best_amp(const unsigned int *x, int n, double fs)
{
    double best = 0.0, bpm, a;
    for (bpm = 40.0; bpm <= 180.0; bpm += 2.0) {
        a = band_amp(x, n, fs, bpm / 60.0);
        if (a > best) best = a;
    }
    return best;
}

static double period_bpm(const double *seg, int n, double fs, double *conf)
{
    static double corr[512];
    double mean = 0, energy = 0, best = 0;
    int i, lag, blag = 0, lo = (int)(fs * 0.42), hi = (int)(fs * 1.45);
    /* The upper limit matters: extending the search to 1.6 s lets the first subharmonic of a
     * real pulse win, which is how a 65 bpm wearer was reported as 40. 1.45 s is 41 bpm, below
     * any resting rate this will meet. */
    for (i = 0; i < n; i++) mean += seg[i];
    mean /= n;
    for (i = 0; i < n; i++) energy += (seg[i]-mean) * (seg[i]-mean);
    if (energy <= 0 || hi >= n) return 0;
    if (hi - lo >= 512) hi = lo + 511;
    for (lag = lo; lag < hi; lag++) {
        double s = 0;
        for (i = 0; i + lag < n; i++) s += (seg[i]-mean) * (seg[i+lag]-mean);
        s /= (n - lag);
        corr[lag - lo] = s;
        if (s > best) { best = s; blag = lag; }
    }
    if (!blag) return 0;
    /* A win at either end of the search range is not a peak, it is the correlation still rising
     * or falling as it runs out of room. Those produce a confident-looking nonsense - 146 bpm,
     * the shortest lag searched, with every window agreeing on it exactly so the agreement check
     * passes. Reject the boundary rather than report it. */
    if (blag <= lo + 1 || blag >= hi - 2) return 0;
    *conf = best / (energy / n);
    {
        /* Interpolate the peak between lag steps. In green mode the sensor runs at 25 Hz, so one
         * lag step is 40 ms and the rate quantises to about 1.7 bpm near a resting pulse - large
         * enough to show up as disagreement between windows that actually agree. Fitting the
         * peak's neighbours recovers the position between samples. */
        double y0 = corr[blag - lo - 1], y1 = corr[blag - lo], y2 = corr[blag - lo + 1];
        double den = y0 - 2 * y1 + y2;
        double adj = den != 0.0 ? 0.5 * (y0 - y2) / den : 0.0;
        if (adj < -0.5 || adj > 0.5) adj = 0.0;
        return 60.0 * fs / (blag + adj);
    }
}

/* Shape of the average beat: how fast it rises, and how much reflected wave rides on it.
 *
 * These are what a pressure estimate keys on - stiffer arteries rise faster and reflect more.
 * The foot is looked for only in the 350 ms before a peak: searching the whole preceding interval
 * finds the trough after the *previous* beat and reports an upstroke of most of a second, which
 * no artery does. */
static int shape_beats = 0;    /* beats in the last ensemble, for the report */
static double shape_raw_sut = 0, shape_raw_ai = 0;   /* before the gate, for the report */

static void pulse_shape(const double *d, int n, double fs, double bpm, double *sut, double *ai)
{
    /* Shape measured on an ensemble-averaged beat rather than on each beat separately.
     *
     * A single wrist beat carries too little signal to measure a reflected wave on. Most of the
     * effort here used to go into discarding bad ones - a beat whose reflected point landed in a
     * drift trough scored an augmentation index of -5 - and even after filtering, a third of
     * measurements had too few survivors left to report anything at all.
     *
     * Averaging inverts that. Beats are aligned on their peaks and scaled to a common height, so
     * one strong beat cannot outvote three ordinary ones, and then averaged: noise that is not
     * time-locked to the pulse falls away while the pulse itself survives. The foot, the upstroke
     * and the reflected wave are measured once, on that.
     *
     * Beats are still dropped, but on an honest criterion - how well each matches the ensemble -
     * rather than on whether the number it produced was one we wanted to see.
     */
    enum { MAXB = 256, MAXW = 512 };
    static int pk[MAXB];
    static double ens[MAXW], acc[MAXW];
    int npk = 0, i, j, k, T, pre, post, wlen, used;
    double mx = 0, thr;

    *sut = 0;
    *ai = 0;
    shape_beats = 0;
    if (bpm < 30.0 || bpm > 210.0 || n < 64) return;

    T = (int)(fs * 60.0 / bpm);
    if (T < 8) return;
    pre  = (int)(T * 0.45);
    post = (int)(T * 0.75);
    wlen = pre + post + 1;
    if (wlen > MAXW || wlen < 8) return;

    /* A threshold the beats can actually clear.
     *
     * This was a fraction of the largest value in the record, which hands the decision to
     * whichever artefact happened to be biggest: one arm movement puts the maximum an order of
     * magnitude above any pulse, the bar goes with it, and every real beat is rejected. That is
     * how a run with a clean 53 bpm and ninety counts of amplitude reported beats=0.
     *
     * The median of the local maxima cannot be dragged that way. Half the peaks lie above it
     * however extreme the outliers are, so a handful of artefacts move it barely at all, and
     * taking 40% of it keeps the smaller genuine beats without admitting the baseline.
     */
    {
        static double tops[MAXB];
        int nt = 0;
        for (i = 1; i < n - 1; i++) {
            if (d[i] >= d[i-1] && d[i] > d[i+1] && d[i] > 0 && nt < MAXB) tops[nt++] = d[i];
        }
        if (nt < 4) return;
        qsort(tops, nt, sizeof tops[0], cmp_d);
        mx = tops[nt / 2];
        thr = mx * 0.4;
    }

    /* Peaks, with a refractory gap of half a period so one beat cannot be counted twice. */
    for (i = 1; i < n - 1; i++) {
        if (!(d[i] >= d[i-1] && d[i] > d[i+1] && d[i] > thr)) continue;
        if (npk > 0 && i - pk[npk-1] < T / 2) continue;
        if (i - pre < 0 || i + post >= n) continue;
        if (npk < MAXB) pk[npk++] = i;
    }
    if (npk < 4) return;

    /* First pass: every beat, each normalised to its own range. */
    for (j = 0; j < wlen; j++) acc[j] = 0.0;
    used = 0;
    for (k = 0; k < npk; k++) {
        const double *seg = d + pk[k] - pre;
        double lo = seg[0], hi = seg[0], rng;
        for (j = 1; j < wlen; j++) {
            if (seg[j] < lo) lo = seg[j];
            if (seg[j] > hi) hi = seg[j];
        }
        rng = hi - lo;
        if (rng <= 0) continue;
        for (j = 0; j < wlen; j++) acc[j] += (seg[j] - lo) / rng;
        used++;
    }
    if (used < 4) return;
    for (j = 0; j < wlen; j++) ens[j] = acc[j] / used;

    /* Second pass: keep only the beats that look like the ensemble, and rebuild it from those.
     * Correlation, not amplitude - a beat can be small and still be the right shape.
     *
     * Two thresholds, tried in order. 0.8 is what a clean recording should meet, but a wrist is
     * not a clean recording, and demanding it of every beat left too few survivors to average -
     * which is a measurement thrown away for being imperfect rather than for being wrong. If
     * fewer than four beats clear 0.8, the same test runs again at 0.5, and only then does it
     * give up. */
    {
        double cut;
        for (cut = 0.8; cut >= 0.5; cut -= 0.3) {
        double em = 0;
        for (j = 0; j < wlen; j++) acc[j] = 0.0;
        used = 0;
        for (j = 0; j < wlen; j++) em += ens[j];
        em /= wlen;
        for (k = 0; k < npk; k++) {
            const double *seg = d + pk[k] - pre;
            double lo = seg[0], hi = seg[0], rng, sm = 0, num = 0, ds = 0, de = 0, c;
            for (j = 1; j < wlen; j++) {
                if (seg[j] < lo) lo = seg[j];
                if (seg[j] > hi) hi = seg[j];
            }
            rng = hi - lo;
            if (rng <= 0) continue;
            for (j = 0; j < wlen; j++) sm += (seg[j] - lo) / rng;
            sm /= wlen;
            for (j = 0; j < wlen; j++) {
                double a = (seg[j] - lo) / rng - sm, b = ens[j] - em;
                num += a * b;
                ds  += a * a;
                de  += b * b;
            }
            if (ds <= 0 || de <= 0) continue;
            c = num / sqrt(ds * de);
            if (c < cut) continue;                 /* not this pulse: motion, or a missed beat */
            /* Line each beat up with the ensemble before adding it.
             *
             * Peaks are detected to the nearest sample, and a peak is a broad, flat thing: the
             * detected index wanders a few samples either side of where the beat really sits.
             * Averaging on those indices smears the upstroke, so the average reads a slower rise
             * than any of the beats in it - 321 ms where the same beats measured singly gave 240
             * to 260, which then failed a gate that was right about the beats and wrong about
             * the average.
             *
             * So slide each beat against the ensemble and add it where it fits best. The search
             * is a twentieth of a cycle either way, which covers detection jitter and cannot
             * reach the neighbouring beat.
             */
            {
                int shift, bestsh = 0;
                double bestc = -2.0;
                int room = T / 20 + 1;
                for (shift = -room; shift <= room; shift++) {
                    double nu = 0, ds = 0, de = 0, cc;
                    int idx = pk[k] - pre + shift;
                    if (idx < 0 || idx + wlen > n) continue;
                    for (j = 0; j < wlen; j++) {
                        double x = (d[idx + j] - lo) / rng - sm, y = ens[j] - em;
                        nu += x * y;
                        ds += x * x;
                        de += y * y;
                    }
                    if (ds <= 0 || de <= 0) continue;
                    cc = nu / sqrt(ds * de);
                    if (cc > bestc) { bestc = cc; bestsh = shift; }
                }
                if (pk[k] - pre + bestsh >= 0 && pk[k] - pre + bestsh + wlen <= n) {
                    seg = d + pk[k] - pre + bestsh;
                }
            }
            for (j = 0; j < wlen; j++) acc[j] += (seg[j] - lo) / rng;
            used++;
        }
        if (used >= 4) break;
        }
    }
    if (used < 4) return;
    for (j = 0; j < wlen; j++) ens[j] = acc[j] / used;
    shape_beats = used;

    /* Measure once, on the average. The peak sits at pre by construction. */
    {
        int foot = -1, refl, lo;
        double amp, a, up, best = -1e18;

        /* The foot is where the trace turns upward hardest, not the first dip going backwards.
         *
         * Scanning back for the first local minimum works on a clean beat and fails on a flat
         * one: where the ensemble runs level for a while before the upstroke there is no local
         * minimum to find, the scan carries on into the previous beat, and the upstroke comes out
         * a whole beat too long. That is the bimodal split in the log - the good measurements
         * gave 130 to 271 ms and the failures 311, 321, 351, 401, 492, 502, which are not slower
         * upstrokes but upstrokes measured from the wrong place.
         *
         * Maximum second difference is the textbook definition and has no such failure: the
         * sharpest upward turn is a real feature of the waveform whether or not the approach to
         * it is flat. The search is limited to a third of a cycle before the peak, which is
         * longer than any upstroke a heart produces and shorter than the distance to the
         * previous beat.
         */
        lo = pre - (int)(T * 0.34);
        if (lo < 1) lo = 1;
        for (k = pre - 2; k > lo; k--) {
            double d2 = ens[k-1] - 2.0 * ens[k] + ens[k+1];
            if (d2 > best) { best = d2; foot = k; }
        }
        if (foot < 0) foot = lo;
        amp = ens[pre] - ens[foot];
        if (amp <= 0) return;

        refl = pre + (int)(fs * 0.25);
        if (refl >= wlen) refl = wlen - 1;

        up = (pre - foot) / fs * 1000.0;
        a  = (ens[refl] - ens[foot]) / amp;

        /* The gate catches a misdetected foot, not an unusual wearer. 300 ms because this one has
         * Ehlers-Danlos: more compliant arteries and a slower upstroke are what that predicts. */
        /* Kept whether or not the gate lets them through, so a rejected shape can be told
         * apart from one that was never found. sut=0 with beats=7 says nothing about which. */
        shape_raw_sut = up;
        shape_raw_ai = a;
        if (up > 80.0 && up < 300.0 && a > -0.2 && a < 1.5) {
            *sut = up;
            *ai = a;
        }
    }
}


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
    /* Match the whole word. "redo" and "ratio" both start with an r, so testing the first
     * letter sent every redo down the ratio path, where it tried to measure for zero seconds
     * and returned nothing - and the ratio on the reply stayed the guess it was meant to
     * replace. */
    const char *mode = argc > 3 ? argv[3] : "";
    int want_ratio = (strcmp(mode, "ratio") == 0);
    int want_redo  = (strcmp(mode, "redo") == 0);
    /*
     * Re-measure the pulse shape of a waveform kept earlier: ppgd 0 <file> shape <bpm>
     *
     * The point is to be able to answer "does this change help" against recordings rather than
     * against the next few beats of a live wrist. A detector that looks better on one fresh
     * measurement has shown nothing; the same detector run over twenty kept waveforms, against
     * what the old one made of them, has.
     */
    int want_shape = (strcmp(mode, "shape") == 0);
    /* Run both rate estimators over a kept waveform and print what each made of it, so a change
     * can be judged against recordings rather than against the next few beats of a live wrist:
     * ppgd 0 <file> rate */
    int want_rate = (strcmp(mode, "rate") == 0);

    if (want_rate) {
        FILE *rf = argc > 2 ? fopen(argv[2], "r") : NULL;
        double rfs = 0, conf = 0, sp = 0, ac = 0;
        static double dw[MAXS];
        unsigned int v1, v2;

        if (!rf) { printf("no such waveform\n"); return 1; }
        if (fscanf(rf, "%lf", &rfs) != 1) { fclose(rf); printf("bad waveform\n"); return 1; }
        ns = 0;
        while (ns < MAXS && fscanf(rf, "%u %u", &v1, &v2) == 2) {
            ch1[ns] = v1; ch2[ns] = v2; ns++;
        }
        fclose(rf);
        if (ns < 400 || rfs <= 0) { printf("waveform too short: %d\n", ns); return 1; }

        src = pick_channel(ns);
        detrend(dw, ns, (int)(rfs * 2.0));
        ac = period_bpm(dw, ns, rfs, &conf);
        sp = spectral_bpm_d(dw, ns, rfs, &conf);
        /* The band itself, so a zero answer can be read rather than guessed at. */
        {
            double b;
            printf("spectrum:");
            for (b = 36.0; b <= 120.0; b += 6.0)
                printf(" %.0f=%.1f", b, band_amp_d(dw, ns, rfs, b / 60.0));
            printf("\n");
        }
        printf("autocorrelation=%.0f  spectral=%.0f  margin=%.2f  samples=%d hz=%.1f\n",
               ac, sp, conf, ns, rfs);
        return 0;
    }

    if (want_shape) {
        FILE *sf = argc > 2 ? fopen(argv[2], "r") : NULL;
        double sfs = 0, sbpm = argc > 4 ? atof(argv[4]) : 0, sut = 0, ai = 0;
        static double dw[MAXS];
        unsigned int v1, v2;

        if (!sf) { printf("no such waveform\n"); return 1; }
        if (fscanf(sf, "%lf", &sfs) != 1) { fclose(sf); printf("bad waveform\n"); return 1; }
        ns = 0;
        while (ns < MAXS && fscanf(sf, "%u %u", &v1, &v2) == 2) {
            ch1[ns] = v1;
            ch2[ns] = v2;
            ns++;
        }
        fclose(sf);
        if (ns < 400 || sfs <= 0) { printf("waveform too short: %d samples\n", ns); return 1; }

        /* Whichever channel carries more pulse, as the live path chooses it. */
        /* Drop the first two seconds, as the live path drops its settling period. Without that
         * the gain transient dominates everything and the replay finds no rate at all where the
         * measurement it came from found one - which is a difference between the replay and the
         * thing being replayed, and makes the whole exercise worthless. */
        {
            int skip2 = (int)(sfs * 2.0);
            if (skip2 > 0 && ns > skip2 + 400) {
                int q;
                for (q = 0; q + skip2 < ns; q++) { ch1[q] = ch1[q+skip2]; ch2[q] = ch2[q+skip2]; }
                ns -= skip2;
            }
        }
        src = pick_channel(ns);
        detrend(dw, ns, (int)(sfs * 2.0));
        if (sbpm < 30.0 || sbpm > 210.0) {
            double conf = 0;
            /* The spectrum rather than the autocorrelation: it answers on recordings the
             * autocorrelation refuses, which is most of the interesting ones. */
            sbpm = spectral_bpm_d(dw, ns, sfs, &conf);
        }
        pulse_shape(dw, ns, sfs, sbpm, &sut, &ai);
        printf("bpm=%.0f beats=%d raw=%.0f/%.2f sut=%.0f ai=%.2f", sbpm, shape_beats,
               shape_raw_sut, shape_raw_ai, sut, ai);
        if (sut > 0) {
            printf(" sbp=%.0f dbp=%.0f",
                   100.0 + 0.28 * sbpm - 0.055 * sut + 11.0 * ai,
                   60.0 + 0.19 * sbpm - 0.030 * sut + 6.5 * ai);
        }
        printf(" samples=%d hz=%.1f\n", ns, sfs);
        return 0;
    }
    int want_spo2  = (strcmp(mode, "spo2") == 0 || want_ratio || want_redo);
    /*
     * The ratio pass: short, balanced, and after nothing but R.
     *
     * The vendor firmware measures in two passes - about eight seconds for the saturation, then
     * thirty or forty for the rate and the pressure - and the reason is visible in our own
     * numbers. The two want opposite configurations. R needs both channels carrying signal, which
     * means zeroing 0x0180 to bring channel 1 up from two counts of pulse to thirty; the pressure
     * needs channel 2 at full strength, and that same change drops it from 190-260 counts to
     * 34-95, where the pulse shape cannot be found at all.
     *
     * So do what the vendor does. This pass balances the channels and reports the ratio, and the
     * long pass that follows runs unbalanced for the rate and the shape. Neither has to be
     * compromised for the other.
     *
     * Eight seconds is too short to settle a heart rate by window agreement, and this does not
     * try: the amplitude is taken at the strongest cardiac frequency found on the better channel
     * and then measured at that same frequency on both. One frequency for both channels is the
     * point - taking each channel's own best would let them lock onto different things and the
     * ratio would compare two unrelated numbers.
     */
    /*
     * Re-read a kept ratio pass at a rate measured afterwards.
     *
     * The short pass cannot settle a heart rate: eight seconds of a wandering pulse gave 41, 45,
     * 49 and 59 bpm on four consecutive runs of a resting wrist, and R measured at the wrong
     * frequency is R measured on noise. The long pass that follows settles the rate properly by
     * window agreement, but by then the balanced samples are gone.
     *
     * So keep them. The short pass writes what it collected, the long pass finds the rate, and
     * then this reads the samples back and measures both channels at the rate that was actually
     * there. Nothing is re-measured on the wearer and no extra sensor time is spent - it is the
     * same eight seconds, read once the answer is known.
     */

    if (want_redo) {
        /* ppgd 0 <file> redo <bpm> */
        FILE *rf = argc > 2 ? fopen(argv[2], "r") : NULL;
        double rfs = 0, rbpm = argc > 4 ? atof(argv[4]) : 0;
        double d1 = 0, d2 = 0, l1, l2, a1 = 0, a2 = 0, r = 0, rmatch = 0;
        int mbeats = 0;
        unsigned int v1, v2;
        int k;

        if (!rf) { printf("r=0 reason=no_kept_pass\n"); return 1; }
        if (fscanf(rf, "%lf", &rfs) != 1) { fclose(rf); printf("r=0 reason=bad_kept_pass\n"); return 1; }
        ns = 0;
        while (ns < MAXS && fscanf(rf, "%u %u", &v1, &v2) == 2) {
            ch1[ns] = v1;
            ch2[ns] = v2;
            ns++;
        }
        fclose(rf);
        if (ns < 200 || rfs <= 0 || rbpm < 30.0 || rbpm > 210.0) {
            printf("r=0 reason=kept_pass_unusable samples=%d hz=%.1f bpm=%.0f\n", ns, rfs, rbpm);
            return 1;
        }
        for (k = 0; k < ns; k++) { d1 += ch1[k]; d2 += ch2[k]; }
        d1 /= ns;
        d2 /= ns;
        l1 = d1 - DARK_CODE;
        l2 = d2 - DARK_CODE;
        a1 = band_amp(ch1, ns, rfs, rbpm / 60.0);
        a2 = band_amp(ch2, ns, rfs, rbpm / 60.0);
        {
            double rmed = 0, rsp = 0;
            int nw = ratio_windows(ch1, ch2, ns, rfs, rbpm, &rmed, &rsp);
            /* The whole pass, not the median of its windows.
             *
             * The median was meant to shrug off a bad second and it did the opposite: six
             * seconds is too short to measure channel 1 at all steadily, so every window was
             * noisy and the middle one was no better than the rest. Medians of seven such
             * windows came out 1.354 and 1.462 where the same passes measured whole gave figures
             * near 0.9 - and the amplitude ratio behind them, ac1/ac2, sat between 0.42 and 0.55
             * on every single run. Averaging over the whole 25 seconds is what makes that ratio
             * hold still.
             *
             * The windows still earn their place as a quality measure. How far they disagree
             * says whether the pass held together, which is worth knowing even when the number
             * to report comes from the pass as a whole.
             */
            if (l1 > 100.0 && l2 > 100.0 && a2 > 0) r = (a1 / l1) / (a2 / l2);

            /* And the same thing measured against the beat instead of against a sine wave. */
            {
                static double dw1[MAXS], dw2[MAXS];
                double m1 = 0, m2 = 0;
                int nb;
                src = ch1; detrend(dw1, ns, (int)(rfs * 2.0));
                src = ch2; detrend(dw2, ns, (int)(rfs * 2.0));
                nb = matched_amps(l2 > l1 ? dw2 : dw1, dw1, dw2, ns, rfs, rbpm, &m1, &m2);
                if (nb >= 4 && l1 > 100.0 && l2 > 100.0 && m2 > 0) {
                    rmatch = (m1 / l1) / (m2 / l2);
                }
                mbeats = nb;
            }
            printf("rmatch=%.3f mbeats=%d "
                   "r=%.3f rmed=%.3f spread=%.3f windows=%d acr=%.3f at=%.0f dc1=%.0f dc2=%.0f"
                   " ac1=%.1f ac2=%.1f samples=%d hz=%.1f redone=1\n",
                   rmatch, mbeats,
                   r, rmed, rsp, nw, a2 > 0 ? a1 / a2 : 0.0, rbpm, d1, d2, a1, a2, ns, rfs);
        }
        return 0;
    }

    setvbuf(stdout, NULL, _IONBF, 0);
    signal(SIGTERM, bail); signal(SIGINT, bail); signal(SIGSEGV, bail);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { printf("hr=0 reason=no_device\n"); return 1; }
    atexit(stop_chip);

    if (ioctl(fd, PWR, 1) < 0) { printf("hr=0 reason=power_failed\n"); return 1; }
    ioctl(fd, IRQ, 1);

    usleep(300000);

    /* Replay the sequence for the mode being asked for. These are not interchangeable: the
     * green start differs from the red one in 26 registers, and replaying the red sequence in
     * what was meant to be heart-rate mode is why the LED stayed red however the mode ioctl was
     * ordered. */
    {
        int nseq = want_spo2 ? NSEQ : NSEQ_HR;
        for (i = 0; i < nseq; i++) {
            unsigned char op = want_spo2 ? SEQ[i].op  : SEQ_HR[i].op;
            unsigned short rg = want_spo2 ? SEQ[i].reg : SEQ_HR[i].reg;
            unsigned short vl = want_spo2 ? SEQ[i].val : SEQ_HR[i].val;
            if (op == 0)      wr16(rg, vl);
            else if (op == 1) wr8(rg, (unsigned char)vl);
            else              rd16(rg, &v);
        }

        /* Balance the channels, for the ratio pass only. See the long note below on why this is
         * wrong for the pass that measures a pressure. */
        if (want_ratio) {
            wr16(0x0180, 0x0000);
            wr8(0xdddd, 0xc1);
        }

        /* On 0x0180, and why it is not applied to the long pass.
         *
         * The captured sequence leaves 0x0180 at 0x004d, and under that the two channels receive
         * wildly different amounts of light: 4,200 counts against 53,000, a factor of twelve.
         * Channel 1 then carries about two counts of pulsatile amplitude, and R - which is
         * (AC/DC) on one channel over the other - is two counts divided by itself. Four resting
         * measurements gave R of 2.10, 2.38, 4.75 and 0.824, which is noise wearing the shape of
         * a saturation.
         *
         * Zeroing it moves the light to 18,500 against 32,800 and channel 1 to thirty or forty
         * counts of pulse. Two consecutive runs then gave R of 0.730 and 0.741 - a spread of one
         * and a half percent where it used to be a factor of five.
         *
         * Found by setting each configuration register to zero in turn and watching the light
         * each channel received, which is a search that only became possible once the DC pedestal
         * was subtracted: against a raw code of 3.14 million, quadrupling channel 1 looks like a
         * rounding error. Only 0x0180 and 0x0110 moved it, and 0x0110 overshoots - it puts
         * channel 2 below channel 1.
         *
         * It is not the default, because balancing costs the thing that already works. Channel 2
         * carries the pulse shape the pressure is derived from, and zeroing 0x0180 drops its
         * amplitude from 190-260 counts to 34-95: six runs in that state found no usable beats
         * at all and returned no pressure. Nor is R yet steady enough to be worth that - five
         * runs gave 0.96 to 1.32, and an earlier pair gave 0.73, so it moves between sessions as
         * well as within one.
         *
         * A working pressure is not worth trading for a saturation that still is not measuring.
         * Pass it as an override to carry the work on: ppgd 45 "" spo2 0180=0000
         */
        /* Optional register overrides, applied after the captured sequence and before the chip
         * is armed: ppgd 45 "" spo2 0130=03ff,0132=03ff
         *
         * These have to go in here rather than mid-stream. Writing 0x0130 to a running chip
         * stopped it producing bursts altogether - every sample after the change came back empty
         * - so a value only means anything if it was in place when the chip started.
         *
         * The point of it is the red channel: it pulses 5 counts against infrared's 64 on an
         * unsaturated DC, so R is noise, and no shared gain can separate them. If one of these
         * registers is the per-slot LED current then raising it is the whole fix.
         */
        if (argc > 4 && argv[4][0]) {
            const char *p = argv[4];
            while (*p) {
                unsigned int rg2 = 0, vl2 = 0;
                if (sscanf(p, "%x=%x", &rg2, &vl2) == 2) {
                    wr16((unsigned short)rg2, (unsigned short)vl2);
                }
                while (*p && *p != ',') p++;
                if (*p == ',') p++;
            }
            wr8(0xdddd, 0xc1);            /* commit, as the sequence itself does */
            /* Read each one back. The captured sequence writes 0x0132 and 0x0134 but both read
             * 0x0000 afterwards, so only one LED slot is actually configured - which is the best
             * explanation yet for channel 1 sitting at a constant 0x300000 with no pulse in it.
             * Whether a write sticks is the thing worth knowing here. */
            p = argv[4];
            while (*p) {
                unsigned int rg3 = 0, vl3 = 0;
                unsigned short back = 0;
                if (sscanf(p, "%x=%x", &rg3, &vl3) == 2) {
                    rd16((unsigned short)rg3, &back);
                    fprintf(stderr, "%04x: wrote %04x read %04x\n", rg3, vl3, back);
                }
                while (*p && *p != ',') p++;
                if (*p == ',') p++;
            }
        }
        gain = want_spo2 ? 0x9055 : 0x1f69;     /* whichever that sequence applied */
    }

    /* Set the mode AFTER the register sequence, not before.
     *
     * The sequence is 242 operations replayed from a capture, and it configures the LEDs itself -
     * both captures it came from were SpO2 runs, reporting spo2 alongside the rate, so what it
     * replays is a red+IR configuration. Setting the mode first and then replaying it put the
     * chip straight back into red, which the wearer could see: the LED stayed red in what was
     * meant to be heart-rate mode.
     */
    {
        unsigned int w[6];
        memset(w, 0, sizeof w);
        w[0] = want_spo2 ? 5 : 4;
        ioctl(fd, MODE, w);
        usleep(200000);
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
                /* 0x0118 is a single 16-bit value, not two per-channel bytes. The daemon writes
                 * it six bytes at a time - "W 01 18 4f 3c 00 00" is 0x0118=0x4f3c together with
                 * 0x011a=0x0000 - and the configuration table lists them as separate registers.
                 * Splitting it into halves produced values like 0x8419, which are nowhere on the
                 * daemon's path. */
                double dc1 = 0, dc2 = 0;
                unsigned short newgain = gain;
                int k4;

                for (k4 = before; k4 < ns; k4++) { dc1 += ch1[k4]; dc2 += ch2[k4]; }
                dc1 /= (ns - before);
                dc2 /= (ns - before);
                (void)hi1; (void)lo1; (void)hi2; (void)lo2;

                /* Key on both channels, not just the first.
                 *
                 * Stopping as soon as ch1 is in range leaves ch2 dark, because the two sit about
                 * 47,600 counts apart under one shared gain. Keeping on until neither is above
                 * the threshold is what a ratio of ratios needs - one run reached it by accident
                 * (ac1=986 ac2=652 at gain 7e4b) where the others stopped early at 9055 with ch2
                 * flat at 3 counts. In SpO2 mode go for both; for heart rate ch1 alone is enough
                 * and driving further only costs signal. */
                if (gain > 0x1000 &&
                    (dc1 > 3200000.0 || (want_spo2 && dc2 > 3200000.0)))
                    newgain = (unsigned short)(gain - (gain >> 3));   /* back off about 12% */

                if (newgain != gain) {
                    /* Every gain change steps the DC by about 9500 counts, two orders of
                     * magnitude more than the pulse, so the settling period is unusable and has
                     * to be dropped rather than filtered. Analysis starts after the gain stops
                     * moving; including the transient put the estimate at the edge of its
                     * search range. */
                    settled_at = ns;
                    gain = newgain;
                    wr16(0x0136, 0x0000);
                    wr16(0x0118, gain);
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

    if ((ns < 600 && !want_ratio) || ns < 200 || elapsed <= 0) {
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

    /* The ratio pass answers here and goes no further: no window agreement, no pulse shape,
     * nothing that needs a settled rate. */
    if (want_ratio) {
        double d1 = 0, d2 = 0, l1, l2, a1 = 0, a2 = 0, r = 0, bpm, best = 0, bestf = 0;
        int k, skip = settled_at + (int) fs;
        int n = ns - skip;

        if (n < 200 || fs <= 0) {
            printf("r=0 reason=too_short samples=%d\n", ns);
            return 1;
        }
        for (k = skip; k < ns; k++) { d1 += ch1[k]; d2 += ch2[k]; }
        d1 /= n;
        d2 /= n;
        l1 = d1 - DARK_CODE;
        l2 = d2 - DARK_CODE;

        /* One frequency, chosen on whichever channel carries more, then applied to both. */
        for (bpm = 40.0; bpm <= 180.0; bpm += 1.0) {
            double q = band_amp(l2 > l1 ? ch2 + skip : ch1 + skip, n, fs, bpm / 60.0);
            if (q > best) { best = q; bestf = bpm; }
        }
        if (bestf > 0) {
            a1 = band_amp(ch1 + skip, n, fs, bestf / 60.0);
            a2 = band_amp(ch2 + skip, n, fs, bestf / 60.0);
        }
        if (l1 > 100.0 && l2 > 100.0 && a2 > 0) r = (a1 / l1) / (a2 / l2);

        /* Keep the samples so the rate the long pass settles can be applied to them. The
         * frequency guessed here is only a fallback for when that never arrives. */
        if (csvpath && csvpath[0]) {
            FILE *kf = fopen(csvpath, "w");
            if (kf) {
                int q;
                fprintf(kf, "%.4f\n", fs);
                for (q = skip; q < ns; q++) fprintf(kf, "%u %u\n", ch1[q], ch2[q]);
                fclose(kf);
            }
        }

        printf("r=%.3f at=%.0f dc1=%.0f dc2=%.0f ac1=%.1f ac2=%.1f samples=%d hz=%.1f\n",
               r, bestf, d1, d2, a1, a2, ns, fs);
        return 0;
    }


    /* Keep the waveform, both channels and the rate it was sampled at.
     *
     * This used to write channel 1 alone with no header, which is enough to look at and not
     * enough to re-measure: every later question about the pulse shape - was the foot found in
     * the right place, does a change to the detector help or hurt - needs the same input the
     * measurement had. Without that, a change can only be judged by running it on the wearer
     * again and hoping the next few beats resemble the last few, which is not a test.
     *
     * Same layout as the ratio pass keeps, so one replay mode reads either.
     */
    if (csvpath && csvpath[0]) {
        FILE *f = fopen(csvpath, "w");
        if (f) {
            fprintf(f, "%.4f\n", fs);
            for (i = 0; i < ns; i++) fprintf(f, "%u %u\n", ch1[i], ch2[i]);
            fclose(f);
        }
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
    src = pick_channel(ns);
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
        {
            /* Say what the signal looked like, not just that it failed. A bare refusal cannot be
             * told apart from a dark channel, a saturated one, or a wearer who moved. */
            double lo = d[0], hi = d[0];
            int q;
            for (q = 0; q < ns; q++) { if (d[q] < lo) lo = d[q]; if (d[q] > hi) hi = d[q]; }
            {
                /* Report each channel even though the rate did not survive: a failed run is
                 * still evidence about signal strength. */
                /* From after the gain settled, never the whole record. Every gain step moves
                 * the DC by thousands of counts, so a scan over the raw record finds the AGC
                 * transient and calls it a pulse - which is how a first attempt reported an
                 * amplitude of 3900 on a channel that carries 5. */
                double q1 = 0, q2 = 0, e1 = 0, e2 = 0;
                int qi, qs = settled_at + (int)fs, qn = ns - qs;
                if (qn > 32) {
                    for (qi = qs; qi < ns; qi++) { q1 += ch1[qi]; q2 += ch2[qi]; }
                    q1 /= qn; q2 /= qn;
                    e1 = best_amp(ch1 + qs, qn, fs);
                    e2 = best_amp(ch2 + qs, qn, fs);
                }
                printf("dc1=%.0f dc2=%.0f amp1=%.1f amp2=%.1f ", q1, q2, e1, e2);
            }
            printf("hr=0 reason=no_agreement windows=%d samples=%d hz=%.1f gain=%04x"
                   " swing=%.0f used=%s\n",
                   nrates, ns, fs, gain, hi - lo, src == ch2 ? "ch2" : "ch1");
        }
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
        {
            /* Both channels' pulsatile amplitude, and the ratio of ratios.
             *
             * No percentage is printed. Turning R into a saturation needs a calibration for a
             * reflective wrist sensor, and the textbook SpO2 = 110 - 25R is fitted for
             * transmissive fingertip oximeters - applying it to this sensor gave 81% where the
             * watch itself said 100%. What matters here is whether both channels are pulsatile
             * at all, which is the precondition for any of it and is what the gain fix was for.
             */
            /* Measure both channels where the pulse actually is.
             *
             * This used to be peak-to-peak within one-second blocks, which takes whatever the
             * largest excursion in each second happens to be - a movement artefact, a swallow,
             * the baseline drifting underneath - and calls it the pulse. The ratio then divides
             * one contaminated number by another, which is the most likely reason a wrist that
             * was resting throughout gave R of 0.248 on one run and 0.361 on the next with the
             * saturation obviously unchanged.
             *
             * The rate is already known to a fraction of a bpm by this point, so ask each channel
             * for its amplitude at that frequency and nowhere else. Baseline drift sits below the
             * bin, motion spreads across the rest of the spectrum, and what comes back is the
             * pulsatile amplitude the ratio of ratios is actually defined in terms of.
             */
            double a1 = 0, a2 = 0, dc1 = 0, dc2 = 0, r = 0;
            {
                int j2;
                for (j2 = 0; j2 < ns; j2++) { dc1 += ch1[j2]; dc2 += ch2[j2]; }
                /* The mean of the whole record. The old code summed ch1[j2] once per block, so
                 * one sample in every hundred stood in for the DC level. */
                dc1 /= ns;
                dc2 /= ns;
                a1 = band_amp(ch1, ns, fs, med / 60.0);
                a2 = band_amp(ch2, ns, fs, med / 60.0);
            }
            {
                /* Against the light each channel actually received, not the raw code. */
                double l1 = dc1 - DARK_CODE, l2 = dc2 - DARK_CODE;
                if (l1 > 100.0 && l2 > 100.0 && a2 > 0) r = (a1 / l1) / (a2 / l2);
            }
            /* SpO2 from R, anchored on this sensor rather than on the vendor's.
             *
             * The anchor used to be the vendor's own capture: its two channels at 73 and 69
             * counts on a DC of 3,194,500 - an R of 1.06 - while it displayed 99%. Borrowing that
             * was a mistake. Our channels do not sit near each other the way the vendor's did
             * (450 against 158 on a resting wrist), so our R lands near R_REST, and
             * 100 - 25*(0.35 - 1.06) comes to 117, which the clamp turns into 100 every single
             * time. The 100% this printed on every run was the ceiling, not a measurement of
             * anything, and it would have gone on reading 100 through a real desaturation.
             *
             * So anchor on this sensor's own resting R instead. The wearer is healthy and still
             * when R_REST was measured, so true saturation was ~98%. That is a one-point
             * calibration: it fixes the offset and borrows the textbook slope, which means it
             * reads ~98 at rest by construction and only movement away from that carries
             * information. A fall is meaningful; the absolute number is an assumption.
             *
             * Reported only when both channels are genuinely pulsatile. In green mode the second
             * channel is not infrared at all and R lands between 1.5 and 3.5, meaning nothing.
             */
            double spo2 = 0, sut = 0, ai = 0, sbp = 0, dbp = 0;

            /* No percentage. R is printed because it is a real measurement and worth watching;
             * turning it into a saturation is what there is no basis for.
             *
             * Three consecutive resting runs gave R of 2.10, 2.38 and 4.75, and a fourth gave
             * 0.824 - that spread is channel 1's two counts of pulsatile amplitude being divided
             * by itself, not anyone's saturation moving. With R_REST as the anchor the 0.824 run
             * printed 86%, which is both alarming and meaningless: a healthy wearer at rest, told
             * they are hypoxic by a number with no measurement behind it.
             *
             * This stays off until channel 1 receives enough light to carry a pulse - see
             * docs/vitals.md. The vendor's fabricated pressures are the reason this project
             * exists; printing a fabricated saturation instead would be no better. */
            (void)R_REST;

            /* Pressure from pulse shape and rate. The coefficients are placeholders, not a
             * calibration - see docs/vitals.md. Reported so a trend is visible and so a cuff can
             * be fitted against it later; it is not a measurement of anyone's pressure yet. */
            /* Only at the red mode's 100 Hz. At the green mode's 25 Hz one sample is
             * 40 ms, so a 150 ms upstroke is under four samples and the shape cannot
             * be resolved - which is why green reported 316 ms upstrokes. */
            if (fs > 60.0) pulse_shape(d, ns, fs, med, &sut, &ai);
            /* Only when the shape is physiological. A real systolic upstroke is 80-250 ms and
             * the augmentation index is positive - a negative one means the foot and the peak
             * were found in the wrong order, and any pressure computed from that is arithmetic
             * on noise. Publishing it anyway would be the vendor firmware's own failure dressed
             * up, which is the thing this whole exercise exists to replace. */
            /* 300 ms, not 250. The gate exists to catch a misdetected foot, not to enforce a
             * textbook range: this wearer has Ehlers-Danlos, so more compliant arteries and a
             * slower upstroke are expected rather than suspicious. A 258 ms upstroke with a
             * sound augmentation index was being thrown away, which is the gate deciding
             * physiology instead of detection. */
            if (sut > 80 && sut < 300 && ai > 0.0 && ai < 1.5) {
                /* The intercepts now carry a cuff correction.
                 *
                 * Both were placeholders picked to land in a plausible range, which was all
                 * there was to go on without a reference. There is one now: twelve cuff readings
                 * across a quiet afternoon, against seven resting measurements from this code,
                 * three of which were taken in the same minutes as the cuff rather than merely
                 * the same afternoon.
                 *
                 *              ours     cuff     difference
                 *   systolic   116.1    110.7    +5.5 mmHg
                 *   diastolic   74.1     68.6    +5.5 mmHg
                 *
                 * The paired three read 112/70, 116/72 and 117/72 while the cuff read 111/68,
                 * 107/68, 109/67, 110/70 and 110/69, which is the same gap seen close up.
                 *
                 * The cuff's own 88 is left out of its diastolic mean - the other eleven sit
                 * between 66 and 71, and a cuff diastolic is the least repeatable number either
                 * device produces. Two of ours are left out too, at 95 and 112 bpm, because a
                 * pulse that high says the wearer was moving and it was not a resting
                 * measurement whatever it printed.
                 *
                 * Two things this is not. It is not paired: no measurement here was taken at the
                 * Seven points against twelve is a thin basis, so it moves the offset only -
                 * every coefficient below is untouched and still unfitted. And an offset fitted
                 * on one wearer on one afternoon is exactly that.
                 *
                 * The part worth noting is that they agreed to within six before any correction
                 * at all. The shape features came from the literature rather than from tuning
                 * against this wearer, and they landed close on the first real test they have
                 * had.
                 */
                sbp = 100.0 + 0.28 * med - 0.055 * sut + 11.0 * ai;
                dbp = 60.0 + 0.19 * med - 0.030 * sut + 6.5 * ai;
            }

            printf("hr=%.0f spread=%.0f hz=%.1f samples=%d windows=%d gain=%04x"
                   " dc1=%.0f dc2=%.0f ac1=%.0f ac2=%.0f r=%.3f spo2=%.0f beats=%d raw=%.0f/%.2f sut=%.0f ai=%.2f"
                   " sbp=%.0f dbp=%.0f used=%s\n",
                   med, spread, fs, ns, nrates, gain, dc1, dc2, a1, a2, r, spo2, shape_beats, shape_raw_sut, shape_raw_ai, sut, ai, sbp, dbp,
                   src == ch2 ? "ch2" : "ch1");
        }
    }
    return 0;
}
