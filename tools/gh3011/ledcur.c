/* Which register drives the red LED's current?
 *
 * SpO2 needs two comparably driven channels. R is (AC_red/DC_red)/(AC_ir/DC_ir), and with red
 * pulsing 14 counts against infrared's 225 that ratio is set by whatever noise sits on the weaker
 * channel - which is why it came back 0.086 on one run and 0.061 on the next with the wearer
 * resting throughout. The single shared gain at 0x0118 cannot help: it moves both channels
 * together, and backing it off to bring one out of saturation buries the other.
 *
 * The two mode sequences differ at 0x0130, 0x0132 and 0x0134, holding 0x0346/0x0446/0x0546 for
 * red and infrared against 0x0746/0x0346/0x0246 for green. The high byte tracks the mode and the
 * low byte is 0x46 in every slot, which is the shape of "this slot drives LED n at current 0x46".
 *
 * chansweep swept these registers already and found nothing, but it halved and doubled the whole
 * 16-bit word - which moves the LED selector in the high byte along with the current, and a slot
 * pointed at no LED says nothing about current at all. So hold the high byte and vary only the
 * low one.
 *
 * Everything is put back as it goes and the chip is stopped on every exit path, signals included.
 * This runs against a wrist and must not leave the sensor lit.
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

/* One burst: each channel's mean and its peak-to-peak swing. Peak-to-peak is crude, but this is
 * only ever comparing one setting against another over the same second or two. */
static int sample_once(double *dc1, double *dc2, double *ac1, double *ac2)
{
    unsigned char buf[240];
    unsigned short lvl = 0;
    static unsigned int c1[400], c2[400];
    int n = 0, left, k;
    struct sigaction sa;

    wr8(0xdddd, 0xc3);
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_alarm;
    sigaction(SIGALRM, &sa, NULL);
    alarm(3);
    ioctl(fd, WAIT, 0);
    alarm(0);

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
    static const unsigned short regs[] = {0x0130, 0x0132, 0x0134};
    static const unsigned char cur[]  = {0x10, 0x46, 0x80, 0xc0, 0xff};
    double b1=0, b2=0, a1=0, a2=0;
    unsigned short v = 0;
    int i, ri, ci;

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

    for (i = 0; i < 4; i++) sample_once(&b1, &b2, &a1, &a2);
    printf("baseline dc1=%.0f dc2=%.0f ac1=%.0f ac2=%.0f\n\n", b1, b2, a1, a2);
    printf("reg   orig  ->new    dc1        dc2       ac1    ac2\n");

    for (ri = 0; ri < 3; ri++) {
        unsigned short orig = 0;
        rd16(regs[ri], &orig);
        for (ci = 0; ci < (int)(sizeof cur); ci++) {
            unsigned short test = (unsigned short)((orig & 0xff00) | cur[ci]);
            double d1=0, d2=0, s1=0, s2=0;
            if (test == orig) continue;
            wr16(regs[ri], test);
            sample_once(&d1, &d2, &s1, &s2);          /* discard the transition */
            if (!sample_once(&d1, &d2, &s1, &s2)) {
                printf("  %04x %04x -> %04x   (no data)\n", regs[ri], orig, test);
                wr16(regs[ri], orig);
                continue;
            }
            printf("  %04x %04x -> %04x  %9.0f %9.0f %6.0f %6.0f\n",
                   regs[ri], orig, test, d1, d2, s1, s2);
            wr16(regs[ri], orig);
            sample_once(&d1, &d2, &s1, &s2);          /* let it settle back */
        }
        printf("\n");
    }

    stop_chip();
    printf("stopped\n");
    return 0;
}
