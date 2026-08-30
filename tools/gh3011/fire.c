/* Drive the chip with its own start command.
 *
 * The daemon's traffic showed the session bracketed by "dd dd c0" and "dd dd c4" - an 8-bit
 * write to 0xdddd - which is the chip's run/stop control. Every earlier attempt configured the
 * part and then asked the *driver* to start it, which is only a request to the kernel; this
 * writes the start the chip itself understands.
 *
 * Also exercises the indirect read the daemon used: select a channel in 0x0064, pulse 0x006a,
 * read the result from 0x006c.
 *
 * Stops the chip on every exit path.
 */
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <signal.h>
#include <stdlib.h>

#define XFER 0xc0084704u
#define CMD  0x40184709u
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
static int wr16(unsigned short reg, unsigned short val)
{
    unsigned char p[4];
    p[0] = reg >> 8; p[1] = reg; p[2] = val >> 8; p[3] = val;
    return wr(p, 4);
}
static int wr8(unsigned short reg, unsigned char val)
{
    unsigned char p[3];
    p[0] = reg >> 8; p[1] = reg; p[2] = val;
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
    *v = (b[0] << 8) | b[1];
    return 0;
}

static void stop_chip(void)
{
    unsigned int w[6];
    if (fd < 0) return;
    wr8(0xdddd, 0xc4);
    memset(w, 0, sizeof w); w[0] = 6; ioctl(fd, CMD, w);
}
static void bail(int s) { (void)s; stop_chip(); _exit(2); }

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

int main(int argc, char **argv)
{
    double secs = argc > 1 ? atof(argv[1]) : 12.0;
    struct timeval t0, t;
    unsigned short v;
    int i, ok = 0, sel;

    setvbuf(stdout, NULL, _IONBF, 0);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { perror("open"); return 1; }
    atexit(stop_chip);
    signal(SIGTERM, bail); signal(SIGINT, bail);
    signal(SIGSEGV, bail); signal(SIGALRM, bail);
    alarm((int)secs + 20);
    { unsigned int w[6]; memset(w, 0, sizeof w); w[0] = 4; ioctl(fd, CMD, w); puts("rail powered (driver mode 4)"); usleep(400000); }

    if (rd16(0x0028, &v) == 0) printf("chip id 0x0028 = %04x\n", v);
    if (rd16(0x0016, &v) == 0) printf("         0x0016 = %04x\n", v);

    printf("stop, configure, then start with dd dd c0\n");
    wr8(0xdddd, 0xc4);
    usleep(50000);
    for (i = 0; i < NCFG; i++) if (wr16(cfg[i][0], cfg[i][1]) >= 0) ok++;
    printf("  %d/%d config writes\n", ok, NCFG);
    printf("  start rc=%d\n", wr8(0xdddd, 0xc0));
    sleep(1);

    /* the daemon's indirect read: select, pulse, collect */
    for (sel = 0x20; sel <= 0x26; sel += 2) {
        unsigned short got = 0;
        wr16(0x0064, sel); wr16(0x006a, 1); wr16(0x006a, 0);
        rd16(0x006c, &got);
        printf("  indirect sel=%02x -> %u\n", sel, got);
    }

    printf("\nsampling:\n");
    gettimeofday(&t0, 0);
    for (;;) {
        unsigned char b[16];
        double el;
        gettimeofday(&t, 0);
        el = (t.tv_sec - t0.tv_sec) + (t.tv_usec - t0.tv_usec) / 1e6;
        if (el > secs) break;
        rdn(0x0086, b, 8);
        printf("%6.2f  0086: %02x%02x %02x%02x %02x%02x %02x%02x", el,
               b[0],b[1],b[2],b[3],b[4],b[5],b[6],b[7]);
        rd16(0x001a, &v); printf("   001a=%u", v);
        rdn(0x00a0, b, 6);
        printf("   00a0: %02x%02x %02x%02x %02x%02x\n", b[0],b[1],b[2],b[3],b[4],b[5]);
        usleep(100000);
    }
    stop_chip();
    printf("stopped\n");
    return 0;
}
