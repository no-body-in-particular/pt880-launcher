/* Which register drives which channel's LED?
 *
 * The single 16-bit gain at 0x0118 moves both channels together, so they cannot be balanced with
 * it: desaturating one leaves ch1 at 14 counts against ch2's 154, and a ratio of ratios needs
 * them comparable. The configuration table sets three registers to 0x01ff - 0x011c, 0x011e and
 * 0x0120 - which is the shape of a per-channel LED current.
 *
 * This starts the chip once, then walks each of those registers through a few values, measuring
 * both channels after each change. Whichever register moves one channel and not the other is the
 * per-channel control.
 *
 * One start, many probes: restarting per value would take a minute each and light the sensor far
 * more than necessary. The chip is stopped on every exit path.
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

/* Collect one burst and report each channel's DC and pulsatile amplitude. */
static int sample_once(double *dc1, double *dc2, double *ac1, double *ac2)
{
    unsigned char buf[240];
    unsigned short lvl = 0, st = 0;
    unsigned int c1[400], c2[400];
    int n = 0, left, k;
    struct sigaction sa;

    wr8(0xdddd, 0xc3);
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_alarm;
    sigaction(SIGALRM, &sa, NULL);
    alarm(3);
    ioctl(fd, WAIT, 0);
    alarm(0);

    rd16(0x0008, &st);
    rd16(0x004a, &lvl);
    left = (int)lvl * 3;
    if (left > 1200) left = 1200;
    while (left > 0 && n < 380) {
        int want = left > 240 ? 240 : left;
        if (rdn(0xaaaa, buf, want) < 0) break;
        for (k = 0; k + 5 < want && n < 380; k += 6) {
            c1[n] = ((unsigned)buf[k]<<16)|(buf[k+1]<<8)|buf[k+2];
            c2[n] = ((unsigned)buf[k+3]<<16)|(buf[k+4]<<8)|buf[k+5];
            n++;
        }
        left -= want;
    }
    if (n < 20) return 0;
    {
        unsigned int lo1=c1[0], hi1=c1[0], lo2=c2[0], hi2=c2[0];
        double s1=0, s2=0;
        for (k = 0; k < n; k++) {
            if (c1[k]<lo1) lo1=c1[k];
            if (c1[k]>hi1) hi1=c1[k];
            if (c2[k]<lo2) lo2=c2[k];
            if (c2[k]>hi2) hi2=c2[k];
            s1 += c1[k]; s2 += c2[k];
        }
        *dc1 = s1/n; *dc2 = s2/n;
        *ac1 = hi1-lo1; *ac2 = hi2-lo2;
    }
    return n;
}

int main(void)
{
    static const unsigned short regs[] = {0x011c, 0x011e, 0x0120};
    static const unsigned short vals[] = {0x01ff, 0x00c0, 0x03ff};
    unsigned short v = 0;
    int i, ri, vi;

    setvbuf(stdout, NULL, _IONBF, 0);
    signal(SIGTERM, bail); signal(SIGINT, bail); signal(SIGSEGV, bail);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { printf("no device\n"); return 1; }
    atexit(stop_chip);

    ioctl(fd, PWR, 1);
    ioctl(fd, IRQ, 1);
    { unsigned int w[6]; memset(w,0,sizeof w); w[0]=5; ioctl(fd, MODE, w); }
    usleep(300000);

    for (i = 0; i < NSEQ; i++) {
        if (SEQ[i].op == 0)      wr16(SEQ[i].reg, SEQ[i].val);
        else if (SEQ[i].op == 1) wr8(SEQ[i].reg, (unsigned char)SEQ[i].val);
        else                     rd16(SEQ[i].reg, &v);
    }

    /* Desaturate first, or every channel reads flat and nothing can be told apart. */
    {
        unsigned short gain = 0x9055;
        double d1, d2, a1, a2;
        int tries;
        for (tries = 0; tries < 8; tries++) {
            if (!sample_once(&d1, &d2, &a1, &a2)) continue;
            if (d1 <= 3200000.0 && d2 <= 3200000.0) break;
            gain = (unsigned short)(gain - (gain >> 3));
            wr16(0x0136, 0x0000);
            wr16(0x0118, gain);
        }
        printf("desaturated at gain %04x: dc1=%.0f dc2=%.0f ac1=%.0f ac2=%.0f\n\n",
               gain, d1, d2, a1, a2);
    }

    /* Dump the same registers the daemon reads during a working run, so the two states can be
     * compared directly. Our channels sit 47,500 counts apart where the daemon's sit together,
     * which is a configuration difference rather than anything one shared gain can fix. */
    {
        static const unsigned short want[] = {
            0x0008,0x0016,0x0022,0x0028,0x004a,0x006c,0x0080,0x0082,0x0084,0x00c0,
            0x00c2,0x00c4,0x00c6,0x00c8,0x00ca,0x00cc,0x00ce,0x00d0,0x00d4,0x00d6,
            0x00d8,0x00da,0x00dc,0x00de,0x00e4,0x0100,0x0102,0x0104,0x0106,0x0108,
            0x010a,0x010c,0x010e,0x0110,0x0112,0x0114,0x0116,0x0118,0x011a,0x011c,
            0x011e,0x0120,0x0122,0x0124,0x0126,0x0128,0x012a,0x012c,0x012e,0x0130,
            0x0132,0x0134,0x0136,0x0180,0x0186,0x018a,0x0194,
        };
        int wi;
        printf("OURS:\n");
        for (wi = 0; wi < (int)(sizeof want / sizeof want[0]); wi++) {
            unsigned short got = 0;
            rd16(want[wi], &got);
            printf("   %04x = %04x\n", want[wi], got);
        }
    }

    stop_chip();
    printf("stopped\n");
    return 0;
}
