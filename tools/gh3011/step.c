/* Step through the second pass, checking the chip after every write.
 *
 * The first pass completes correctly - 0x00c0 reads back 0001, matching the daemon - and then
 * the second pass leaves the part unresponsive. Rather than guess which write does it, read the
 * chip id after each one: while it answers 0031 the chip is alive, and the first step that stops
 * it is the culprit.
 */
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>

#define PWR  0x40044702u
#define IRQ  0x40044707u
#define WAIT   0x00004701u   /* _IO(G,1): blocks until the chip has data - the interrupt wait */
#define BIGBUF 0x825a470au   /* _IOR(G,10,602): read every burst, alongside the FIFO */
#define XFER 0xc0084704u
#define ADDR 0x14

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };
static int fd = -1;

static int wr(unsigned char *p, int n)
{
    struct msg m; struct rdwr r;
    m.addr = ADDR; m.flags = 0; m.len = n; m.buf = p;
    r.msgs = &m; r.n = 1;
    return ioctl(fd, XFER, &r);
}
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

static void check(const char *what)
{
    unsigned short id = 0, r22 = 0, st = 0;
    rd16(0x0028, &id); rd16(0x0022, &r22); rd16(0x0008, &st);
    printf("  %-22s id=%04x 0022=%04x 0008=%04x%s\n", what, id, r22, st,
           id == 0x0031 ? "" : "   <-- chip stopped answering");
}

int main(int argc, char **argv)
{
    unsigned short v = 0;
    setvbuf(stdout, NULL, _IONBF, 0);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { perror("open"); return 1; }
    /* GH_IOC_ENABLE_POWER. The daemon's strings name the whole set - INIT, ENABLE_IRQ,
     * DISABLE_IRQ, ENABLE_POWER, DISABLE_POWER, EXIT, REMOVE - and power is one ioctl taking
     * 1 or 0, so the IRQ pair is almost certainly the same shape on the next command number. */
    printf("  enable power rc=%d\n", ioctl(fd, PWR, 1));
    usleep(300000);

    /* GH_IOC_ENABLE_IRQ, on the assumption that G,7 is to the interrupt what G,2 is to power.
     * Without the driver's interrupt enabled nothing services the chip, which would explain a
     * FIFO that fills exactly once and then stays empty. */
    printf("  enable irq   rc=%d\n", ioctl(fd, IRQ, 1));
    usleep(100000);

    /* One of the three commands the probe found valid, before anything else. GH_IOC_INIT is
     * the one call the daemon makes that we never have, and it is the most likely reason the
     * FIFO latches a single value instead of streaming. */
    if (argc > 1) {
        unsigned int req = 0;
        if (!strcmp(argv[1], "io1"))  req = 0x00004701u;
        if (!strcmp(argv[1], "iow3")) req = 0x40044703u;
        if (!strcmp(argv[1], "iow6")) req = 0x40044706u;
        if (req) printf("  candidate %s (%08x) rc=%d\n", argv[1], req,
                        ioctl(fd, req, (unsigned long)1));
        usleep(200000);
    }

    wr8(0xdddd, 0xc0); usleep(20000);
    check("after wake");

    /* first pass, condensed - it is known to work */
    wr16(0x0182,0x84db); wr16(0x0180,0x008d); rd16(0x00e4,&v);
    wr16(0x0194,0x0003); rd16(0x018a,&v); wr16(0x018a,0x08a4); wr16(0x018c,0x005d);
    wr16(0x00de,0x0000); wr16(0x00c0,0x0001);
    wr8(0xdddd,0xc4); wr8(0xdddd,0xc0); rd16(0x0022,&v);
    wr16(0x0084,0x0020); wr16(0x0118,0x2828); wr16(0x0136,0x0d20);
    wr16(0x0080,0x0205); wr16(0x0082,0x00c2); wr16(0x0186,0x0001);
    wr16(0x00c0,0x0001); wr16(0x0002,0xfe30);
    wr8(0xdddd,0xc1);
    wr8(0xdddd,0xc0);
    check("after first pass");

    /* second pass, one step at a time */
    wr8(0xdddd,0xc4);            check("dddd c4 (sleep)");
    wr8(0xdddd,0xc0);            check("dddd c0 (wake)");
    wr16(0x0084,0x0023);         check("0084=0023");
    wr16(0x0118,0x9055);         check("0118=9055");
    wr16(0x0136,0x0000);         check("0136=0000");
    wr16(0x0080,0x0605);         check("0080=0605");
    wr16(0x0082,0x01c6);         check("0082=01c6");
    wr16(0x0186,0x0406);         check("0186=0406");
    wr16(0x0016,0x0147);         check("0016=0147");
    wr16(0x0048,0x0001);         check("0048=0001");
    wr16(0x0044,0x00c8);         check("0044=00c8");
    wr16(0x0002,0xfe2e);         check("0002=fe2e");
    wr8(0xdddd,0xa1);            check("dddd a1 (arm)");

    usleep(500000);
    rd16(0x004a, &v);
    printf("  fifo level after 0.5s: %u\n", v);

    /* Drain it. If these come back as 24-bit values around three million, that is the waveform,
     * read with no vendor daemon anywhere in the picture. */
    {
        int round, i;
        unsigned char buf[240];
        static unsigned char fifo602[608];
        for (round = 0; round < 8; round++) {
            unsigned short lvl = 0;
            unsigned char a[2];
            struct msg m[2];
            struct rdwr r;
            /* The daemon's actual per-burst loop, taken from a capture that logs every ioctl
             * rather than three whitelisted ones:
             *
             *     W dddd c3          arm the read
             *     _IOW('G',9,24)     publish the previous result
             *     _IO ('G',1)        blocks ~1s  <- the interrupt wait
             *     _IOR('G',10,602)   read the 602-byte buffer
             *     R 004a             level
             *     R aaaa xN          drain
             *
             * The wait is the piece every previous attempt was missing. Polling the FIFO without
             * it reads a latched register, which is why the same value came back every time. */
            wr8(0xdddd, 0xc3);
            ioctl(fd, WAIT, 0);              /* blocks until the chip has a burst ready */
            ioctl(fd, BIGBUF, fifo602);      /* the daemon reads this every burst */
            rd16(0x004a, &lvl);
            rd16(0x0008, &v);
            a[0] = 0xaa; a[1] = 0xaa;
            memset(buf, 0, sizeof buf);
            m[0].addr = ADDR; m[0].flags = 0; m[0].len = 2; m[0].buf = a;
            m[1].addr = ADDR; m[1].flags = 1; m[1].len = 240; m[1].buf = buf;
            r.msgs = m; r.n = 2;
            if (ioctl(fd, XFER, &r) < 0) { printf("  fifo read failed\n"); break; }
            printf("  round %d level=%u :", round, lvl);
            for (i = 0; i < 4; i++) {
                unsigned int c1 = ((unsigned)buf[i*6] << 16) | (buf[i*6+1] << 8) | buf[i*6+2];
                unsigned int c2 = ((unsigned)buf[i*6+3] << 16) | (buf[i*6+4] << 8) | buf[i*6+5];
                printf("  %u/%u", c1, c2);
            }
            printf("\n");
            usleep(250000);
        }
    }

    wr8(0xdddd, 0xc4);
    ioctl(fd, PWR, 0);
    close(fd);
    return 0;
}
