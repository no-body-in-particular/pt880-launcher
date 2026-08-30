/* What is in the accelerometer FIFO, and is it worth having?
 *
 * The vendor daemon reads `_IOR('G', 10, 602)` four hundred and twenty times against six hundred
 * and fifty-two interrupt waits - two reads for every three interrupts, inside the measurement
 * loop rather than beside it. That is what a PPG algorithm does when it means to subtract the
 * arm's movement from the optical trace, and it is the most likely reason its heart rate survives
 * a moving wrist where ours refuses.
 *
 * Ours has never called it. Before it can be used the layout has to be known, and 602 bytes is
 * not self-explanatory: it could be a hundred three-axis samples at two bytes an axis with a
 * two-byte count in front, or ninety-eight with a longer header, or something else entirely.
 * So read it repeatedly while the sensor runs and look at what changes.
 *
 * The chip is started the same way a measurement starts, because an accelerometer FIFO that only
 * fills while the optical path runs would read empty otherwise.
 *
 * Stopped on every exit path including signals. This runs against a wrist.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/time.h>

#define PWR   0x40044702u
#define IRQ   0x40044707u
#define WAIT  0x00004701u
#define XFER  0xc0084704u
#define MODE  0x40184709u
#define ACCEL 0x825a470au      /* _IOR('G', 10, 602) */
#define ADDR 0x14

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };

static int fd = -1;
static void on_alarm(int s) { (void)s; }

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

/* Read the same bytes as three plausible layouts and print each, so the right one is obvious
 * from which produces numbers that behave like an accelerometer: three axes near zero, one of
 * them near gravity, all of them changing together when the arm moves. */
static void interpret(const unsigned char *b, int n)
{
    int i;
    printf("    head: ");
    for (i = 0; i < 12 && i < n; i++) printf("%02x ", b[i]);
    printf("\n    as i16 from 0:  ");
    for (i = 0; i + 1 < n && i < 18; i += 2)
        printf("%6d ", (short)((b[i+1] << 8) | b[i]));
    printf("\n    as i16 from 2:  ");
    for (i = 2; i + 1 < n && i < 20; i += 2)
        printf("%6d ", (short)((b[i+1] << 8) | b[i]));
    printf("\n");
}

int main(int argc, char **argv)
{
    unsigned char rep[608], prev[608];
    unsigned short v = 0;
    int rounds = argc > 1 ? atoi(argv[1]) : 12;
    int i, changed = 0;
    struct sigaction sa;

    setvbuf(stdout, NULL, _IONBF, 0);
    signal(SIGTERM, bail); signal(SIGINT, bail); signal(SIGSEGV, bail);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { printf("no device\n"); return 1; }
    atexit(stop_chip);

    /* Before the optical path is running, so a FIFO that only fills during a measurement can be
     * told from one that is always live. */
    memset(rep, 0, sizeof rep);
    printf("before the chip starts: rc=%d\n", ioctl(fd, ACCEL, rep));
    interpret(rep, 602);

    ioctl(fd, PWR, 1);
    ioctl(fd, IRQ, 1);
    { unsigned int w[6]; memset(w,0,sizeof w); w[0]=4; ioctl(fd, MODE, w); }
    usleep(300000);
    for (i = 0; i < NSEQ_HR; i++) {
        if (SEQ_HR[i].op == 0)      wr16(SEQ_HR[i].reg, SEQ_HR[i].val);
        else if (SEQ_HR[i].op == 1) wr8(SEQ_HR[i].reg, (unsigned char)SEQ_HR[i].val);
        else                        rd16(SEQ_HR[i].reg, &v);
    }
    printf("\nchip started in green mode; reading the FIFO after each burst\n\n");

    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_alarm;
    sigaction(SIGALRM, &sa, NULL);
    memset(prev, 0, sizeof prev);

    for (i = 0; i < rounds; i++) {
        int rc;
        wr8(0xdddd, 0xc3);
        alarm(4);
        ioctl(fd, WAIT, 0);
        alarm(0);

        memset(rep, 0, sizeof rep);
        rc = ioctl(fd, ACCEL, rep);
        if (rc < 0) { printf("burst %2d: ioctl failed rc=%d\n", i, rc); continue; }
        if (memcmp(rep, prev, 602) == 0) {
            printf("burst %2d: unchanged\n", i);
            continue;
        }
        changed++;
        printf("burst %2d: changed\n", i);
        interpret(rep, 602);
        memcpy(prev, rep, 602);
    }

    stop_chip();
    printf("\n%d of %d reads differed from the one before\n", changed, rounds);
    return 0;
}
