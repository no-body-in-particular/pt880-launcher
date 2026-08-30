/* Does the driver hand out an answer, rather than a signal?
 *
 * The wearer asked the right question: how does the vendor get a saturation out of about eight
 * seconds when twenty-five will not do it here. One answer would be that it never computes one -
 * that the chip or the driver produces the numbers and gh3011_service only reads them.
 *
 * There is an ioctl for exactly that shape. Alongside the ones already used - power, the blocking
 * interrupt wait, the i2c passthrough, the mode - the driver declares:
 *
 *     _IOR('G', 10, 602)   0x825a470a   an accelerometer FIFO
 *     _IOR('G', 11,  24)   0x8018470b   twenty-four bytes of something derived
 *
 * Twenty-four bytes is not a signal. It is the size of a handful of results, and it was noted
 * early on and never called. If it carries a rate and a saturation then everything in ppgd is
 * reimplementing what the driver already knows, and the eight second cycle explains itself.
 *
 * So: bring the chip up exactly as a measurement does, then poll that ioctl and print what comes
 * back. Raw bytes, plus the plausible readings of them, because the layout is not documented
 * anywhere and a heart rate is recognisable when you see one.
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

#define PWR    0x40044702u
#define IRQ    0x40044707u
#define WAIT   0x00004701u
#define XFER   0xc0084704u
#define MODE   0x40184709u
#define REPORT 0x8018470bu      /* _IOR('G', 11, 24) */
#define ACCEL  0x825a470au      /* _IOR('G', 10, 602) */
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

/* Anything that is not all zeroes is worth looking at twice. */
static int interesting(const unsigned char *b, int n)
{
    int i;
    for (i = 0; i < n; i++) if (b[i]) return 1;
    return 0;
}

static void show(const unsigned char *b, int n)
{
    int i;
    printf("    raw ");
    for (i = 0; i < n; i++) printf("%02x", b[i]);
    printf("\n    as bytes  ");
    for (i = 0; i < n && i < 12; i++) printf("%4u", b[i]);
    printf("\n    as u16 be ");
    for (i = 0; i + 1 < n && i < 12; i += 2) printf("%7u", (b[i] << 8) | b[i+1]);
    printf("\n    as u16 le ");
    for (i = 0; i + 1 < n && i < 12; i += 2) printf("%7u", (b[i+1] << 8) | b[i]);
    printf("\n");
}

int main(int argc, char **argv)
{
    unsigned char rep[64], prev[64];
    unsigned short v = 0;
    int i, rounds = argc > 1 ? atoi(argv[1]) : 25;
    int mode = argc > 2 ? atoi(argv[2]) : 5;
    struct sigaction sa;

    setvbuf(stdout, NULL, _IONBF, 0);
    signal(SIGTERM, bail); signal(SIGINT, bail); signal(SIGSEGV, bail);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { printf("no device\n"); return 1; }
    atexit(stop_chip);

    /* Before the chip is even started - so a stale answer from the daemon can be told from one
     * this run produced. */
    memset(rep, 0, sizeof rep);
    printf("report ioctl before starting the chip: rc=%d%s\n",
           ioctl(fd, REPORT, rep), interesting(rep, 24) ? "" : "  (all zero)");
    if (interesting(rep, 24)) show(rep, 24);

    ioctl(fd, PWR, 1);
    ioctl(fd, IRQ, 1);
    { unsigned int w[6]; memset(w,0,sizeof w); w[0]=(unsigned)mode; ioctl(fd, MODE, w); }
    usleep(300000);

    for (i = 0; i < NSEQ; i++) {
        if (SEQ[i].op == 0)      wr16(SEQ[i].reg, SEQ[i].val);
        else if (SEQ[i].op == 1) wr8(SEQ[i].reg, (unsigned char)SEQ[i].val);
        else                     rd16(SEQ[i].reg, &v);
    }
    printf("chip started in mode %d, polling for %d bursts\n\n", mode, rounds);

    memset(prev, 0, sizeof prev);
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_alarm;
    sigaction(SIGALRM, &sa, NULL);

    for (i = 0; i < rounds; i++) {
        int rc;
        /* Let the chip gather a burst, the same way a measurement does. */
        wr8(0xdddd, 0xc3);
        alarm(4);
        ioctl(fd, WAIT, 0);
        alarm(0);

        memset(rep, 0, sizeof rep);
        rc = ioctl(fd, REPORT, rep);
        if (rc < 0) {
            printf("burst %2d: report ioctl failed (rc=%d)\n", i, rc);
            continue;
        }
        if (!interesting(rep, 24)) {
            printf("burst %2d: all zero\n", i);
            continue;
        }
        if (memcmp(rep, prev, 24) == 0) {
            printf("burst %2d: unchanged\n", i);
            continue;
        }
        printf("burst %2d: CHANGED\n", i);
        show(rep, 24);
        memcpy(prev, rep, 24);
    }

    stop_chip();
    printf("\nstopped\n");
    return 0;
}
