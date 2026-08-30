/* How many channels does the FIFO actually carry, and can green ride along with red?
 *
 * Everything here has assumed two. ppgd reads the FIFO level, multiplies by three because a
 * sample is 24 bits, and then walks the bytes six at a time - which is not a measurement, it is
 * an assumption that a frame is two samples wide.
 *
 * There are three slot registers. The captured sequence sets 0x0130 to 0x0346 and leaves 0x0132
 * and 0x0134 reading 0x0000, so only one of the three is configured, and yet two channels come
 * out. Written explicitly after the sequence, those two registers do stick. So either the chip
 * has more to give than we are taking, or the slots mean something other than what they look
 * like, and six bytes of raw FIFO settles which.
 *
 * The high byte of each slot changes with the mode - 03/04/05 for red and infrared, 07/03/02 for
 * green - which is the shape of an LED selector. If it is one, then a slot set to green and a
 * slot set to red would put both wavelengths in the same FIFO, interleaved rather than
 * simultaneous but from the same beat, the same contact and the same moment. That is worth more
 * than either alone: the strong green pulse would give the timing and the shape, and the red
 * would only have to supply its amplitude.
 *
 * So: configure, then print consecutive 24-bit samples with no frame assumption at all. A
 * two-channel FIFO alternates between two levels; a three-channel one repeats every third.
 *
 * The chip is stopped on every exit path including signals. This runs against a wrist.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/time.h>

#define PWR  0x40044702u
#define IRQ  0x40044707u
#define WAIT 0x00004701u
#define XFER 0xc0084704u
#define MODE 0x40184709u
#define ADDR 0x14

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };

static int fd = -1;
static void on_alarm(int s) { (void)s; }

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

/* One burst, as a flat run of 24-bit samples. No frame width is assumed. */
static int grab(unsigned int *out, int max)
{
    unsigned char buf[240];
    unsigned short lvl = 0;
    int n = 0, left, k;
    struct sigaction sa;

    wr8(0xdddd, 0xc3);
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_alarm;
    sigaction(SIGALRM, &sa, NULL);
    alarm(4);
    ioctl(fd, WAIT, 0);
    alarm(0);

    rd16(0x004a, &lvl);
    left = (int)lvl * 3;
    if (left > 1800) left = 1800;
    while (left > 0 && n < max) {
        int want = left > 240 ? 240 : left;
        if (rdn(0xaaaa, buf, want) < 0) break;
        for (k = 0; k + 2 < want && n < max; k += 3) {
            out[n++] = ((unsigned)buf[k]<<16) | (buf[k+1]<<8) | buf[k+2];
        }
        left -= want;
    }
    return n;
}

/* How well does the run repeat every p samples? Lower is a better fit. */
static double period_fit(const unsigned int *v, int n, int p)
{
    double sum = 0;
    int i, c = 0;
    for (i = p; i < n; i++) {
        double d = (double)v[i] - (double)v[i-p];
        sum += d < 0 ? -d : d;
        c++;
    }
    return c ? sum / c : 1e18;
}

int main(int argc, char **argv)
{
    static unsigned int v[900];
    unsigned short g = 0;
    int i, n, p;
    const char *slots = argc > 1 ? argv[1] : NULL;

    setvbuf(stdout, NULL, _IONBF, 0);
    signal(SIGTERM, bail); signal(SIGINT, bail); signal(SIGSEGV, bail);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { printf("no device\n"); return 1; }
    atexit(stop_chip);

    ioctl(fd, PWR, 1);
    ioctl(fd, IRQ, 1);
    /* The mode ioctl is what actually chooses the LEDs - 4 is green, 5 is red and infrared - so
     * it is the only thing that could put two different wavelengths in the two channels. The
     * slot registers do not: their high byte looked like an LED selector and moving it changes
     * neither channel's light. */
    { unsigned int w[6]; int md = argc > 2 ? atoi(argv[2]) : 5;
      memset(w,0,sizeof w); w[0]=(unsigned)md; ioctl(fd, MODE, w);
      printf("mode %d\n", md); }
    usleep(300000);

    for (i = 0; i < NSEQ; i++) {
        if (SEQ[i].op == 0)      wr16(SEQ[i].reg, SEQ[i].val);
        else if (SEQ[i].op == 1) wr8(SEQ[i].reg, (unsigned char)SEQ[i].val);
        else                     rd16(SEQ[i].reg, &g);
    }

    /* "0130=0346,0132=0446,0134=0546" - whatever is to be tried this run. */
    if (slots && slots[0]) {
        const char *s = slots;
        while (*s) {
            unsigned int rg = 0, vl = 0;
            if (sscanf(s, "%x=%x", &rg, &vl) == 2) wr16((unsigned short)rg, (unsigned short)vl);
            while (*s && *s != ',') s++;
            if (*s == ',') s++;
        }
        wr8(0xdddd, 0xc1);
    }

    printf("slots as the chip now holds them:\n");
    { unsigned short a=0,b=0,c=0;
      rd16(0x0130,&a); rd16(0x0132,&b); rd16(0x0134,&c);
      printf("  0130=%04x  0132=%04x  0134=%04x\n\n", a, b, c); }

    /* Bring both channels off the rail before measuring anything.
     *
     * Without this every channel reads its ceiling and swings two or three counts, and a sweep
     * of LED assignments comes back identical whatever is assigned - which is what the first run
     * of this did. The gain is shared, so this only has to get the brighter of the two below the
     * rail; ppgd's own loop does the same thing during a measurement.
     */
    {
        unsigned short gain = 0x9055;
        int tries;
        for (tries = 0; tries < 12; tries++) {
            double m0 = 0, m1 = 0;
            int c, c0 = 0, c1 = 0;
            n = grab(v, 900);
            if (n < 40) continue;
            for (c = 0; c < n; c++) {
                if (c % 2 == 0) { m0 += v[c]; c0++; } else { m1 += v[c]; c1++; }
            }
            if (c0) m0 /= c0;
            if (c1) m1 /= c1;
            if (m0 <= 3200000.0 && m1 <= 3200000.0) break;
            gain = (unsigned short)(gain - (gain >> 3));
            wr16(0x0136, 0x0000);
            wr16(0x0118, gain);
        }
        printf("desaturated at gain %04x\n\n", gain);
    }

    grab(v, 900);                       /* discard the first, it carries the start transient */
    n = grab(v, 900);
    if (n < 60) { printf("no data (%d samples)\n", n); stop_chip(); return 1; }

    /* Split on the two-sample frame the fit confirms, and report what each channel received.
     * 0x300000 is the zero-light pedestal - both channels sit on it with no light on them - and
     * a difference of a few thousand counts is invisible against the raw code. */
    {
        double m0 = 0, m1 = 0, lo0 = 1e18, hi0 = -1e18, lo1 = 1e18, hi1 = -1e18;
        int c0 = 0, c1 = 0;
        for (i = 0; i < n; i++) {
            double x = v[i];
            if (i % 2 == 0) { m0 += x; c0++; if (x < lo0) lo0 = x; if (x > hi0) hi0 = x; }
            else            { m1 += x; c1++; if (x < lo1) lo1 = x; if (x > hi1) hi1 = x; }
        }
        if (c0) m0 /= c0;
        if (c1) m1 /= c1;
        printf("channel A: light %8.0f  swing %6.0f%s\n",
               m0 - 3145728.0, hi0 - lo0, m0 > 3200000.0 ? "   SATURATED" : "");
        printf("channel B: light %8.0f  swing %6.0f%s\n\n",
               m1 - 3145728.0, hi1 - lo1, m1 > 3200000.0 ? "   SATURATED" : "");
    }

    printf("first 12 samples, unframed:\n ");
    for (i = 0; i < 12 && i < n; i++) printf(" %u", v[i]);
    printf("\n\nhow well the run repeats at each frame width:\n");
    for (p = 1; p <= 4; p++) {
        printf("  every %d sample%s: mean step %.0f%s\n", p, p == 1 ? " " : "s",
               period_fit(v, n, p),
               p == 2 ? "   <- what everything assumes" : "");
    }
    printf("\n%d samples\n", n);

    stop_chip();
    printf("stopped\n");
    return 0;
}
