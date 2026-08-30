/* Why does our chip never reach the running state?
 *
 * The daemon's status register 0x0008 reads 0x0002 while sampling. Ours stays 0x0000 after the
 * same start sequence, and no interrupt ever fires - so the chip is configured and awake but not
 * running. The enable is register 0x0002, written 0xfe2e, and it is one of only two values in
 * the whole sequence the daemon computes rather than reading from a table.
 *
 * So: read it back after writing, and if it does not take, walk nearby values and watch whether
 * the status register ever comes alive. No blocking ioctl here - that one hung for ten minutes
 * with the sensor lit, and is not needed to answer this question.
 */
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>

#define PWR  0x40044702u
#define IRQ  0x40044707u
#define XFER 0xc0084704u
#define ADDR 0x14

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };
static int fd = -1;

static int wr(unsigned char *p, int n)
{ struct msg m; struct rdwr r; m.addr=ADDR; m.flags=0; m.len=n; m.buf=p; r.msgs=&m; r.n=1;
  return ioctl(fd, XFER, &r); }
static int wr16(unsigned short g, unsigned short v)
{ unsigned char p[4]; p[0]=g>>8; p[1]=g; p[2]=v>>8; p[3]=v; return wr(p,4); }
static int wr8(unsigned short g, unsigned char v)
{ unsigned char p[3]; p[0]=g>>8; p[1]=g; p[2]=v; return wr(p,3); }
static int rd16(unsigned short g, unsigned short *v)
{
    unsigned char a[2], d[2];
    struct msg m[2]; struct rdwr r;
    a[0]=g>>8; a[1]=g; d[0]=d[1]=0;
    m[0].addr=ADDR; m[0].flags=0; m[0].len=2; m[0].buf=a;
    m[1].addr=ADDR; m[1].flags=1; m[1].len=2; m[1].buf=d;
    r.msgs=m; r.n=2;
    if (ioctl(fd, XFER, &r) < 0) return -1;
    *v = (unsigned short)((d[0]<<8)|d[1]);
    return 0;
}

static void stop(void)
{
    if (fd < 0) return;
    wr8(0xdddd, 0xc4);
    ioctl(fd, IRQ, 0);
    ioctl(fd, PWR, 0);
}
static void bail(int s) { (void)s; stop(); _exit(2); }

int main(void)
{
    unsigned short v = 0, st = 0, lvl = 0, back = 0;
    int i;
    static const unsigned short tries[] = {
        0xfe2e, 0xfe30, 0xfe2f, 0xfe3e, 0xfe0e, 0xfe6e, 0xfeae, 0xff2e, 0x7e2e, 0xfe2c,
    };

    setvbuf(stdout, NULL, _IONBF, 0);
    signal(SIGTERM, bail); signal(SIGINT, bail); signal(SIGALRM, bail);
    alarm(90);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { perror("open"); return 1; }
    atexit(stop);

    ioctl(fd, PWR, 1); usleep(300000);
    ioctl(fd, IRQ, 1); usleep(100000);
    wr8(0xdddd, 0xc0); usleep(20000);

    /* the start, as transcribed */
    wr16(0x0182,0x84db); wr16(0x0180,0x008d); rd16(0x00e4,&v);
    wr16(0x0194,0x0003); rd16(0x018a,&v); wr16(0x018a,0x08a4); wr16(0x018c,0x005d);
    wr16(0x00de,0x0000); wr16(0x00c0,0x0001);
    wr8(0xdddd,0xc4); wr8(0xdddd,0xc0); rd16(0x0022,&v);
    wr16(0x0084,0x0020); wr16(0x0118,0x2828); wr16(0x0136,0x0d20);
    wr16(0x0080,0x0205); wr16(0x0082,0x00c2); wr16(0x0186,0x0001);
    wr16(0x00c0,0x0001); wr16(0x0002,0xfe30);
    wr8(0xdddd,0xc1); wr8(0xdddd,0xc0);
    wr8(0xdddd,0xc4); wr8(0xdddd,0xc0); rd16(0x0022,&v);
    wr16(0x0084,0x0023); wr16(0x0118,0x9055); wr16(0x0136,0x0000);
    wr16(0x0080,0x0605); wr16(0x0082,0x01c6); wr16(0x0186,0x0406);
    rd16(0x0016,&v); wr16(0x0016,0x0147);
    wr16(0x0048,0x0001); wr16(0x0044,0x00c8);

    printf("before the enable:\n");
    rd16(0x0002,&back); rd16(0x0008,&st); rd16(0x004a,&lvl);
    printf("  0002=%04x  0008=%04x  004a=%u\n", back, st, lvl);

    printf("\nwriting the enable and reading it back:\n");
    for (i = 0; i < (int)(sizeof tries / sizeof tries[0]); i++) {
        wr16(0x0002, tries[i]);
        wr8(0xdddd, 0xa1);
        usleep(400000);
        rd16(0x0002,&back); rd16(0x0008,&st); rd16(0x004a,&lvl);
        printf("  wrote %04x -> reads %04x   status=%04x  level=%-5u%s\n",
               tries[i], back, st, lvl,
               (st != 0 || (lvl > 0 && lvl < 1000)) ? "  <-- ALIVE" : "");
    }

    stop();
    printf("\nstopped\n");
    return 0;
}
