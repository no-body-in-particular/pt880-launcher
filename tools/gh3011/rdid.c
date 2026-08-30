/* Read-only: can we read the chip's id ourselves while it is definitely running?
 *
 * Everything about driving the sensor ourselves rests on the i2c passthrough working, and that
 * has never actually been demonstrated - every read we have taken was either of an idle chip or
 * came back as block-constant junk. The daemon reads 0x0028 as 0x0031. If we read the same value
 * while it is measuring, the passthrough is sound and only the power-and-config sequence is
 * missing. If we do not, that is the real blocker and nothing else matters.
 *
 * Writes nothing, so it cannot disturb the measurement it is observing.
 */
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <stdlib.h>

#define XFER 0xc0084704u
#define ADDR 0x14

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };

static int fd = -1;
static int split;

static int rd16(unsigned short reg, unsigned short *v)
{
    unsigned char a[2], d[2];
    struct msg m[2]; struct rdwr r;
    a[0] = reg >> 8; a[1] = reg;
    d[0] = d[1] = 0xdd;                 /* poison, so a missing copy is visible */
    m[0].addr = ADDR; m[0].flags = 0; m[0].len = 2; m[0].buf = a;
    m[1].addr = ADDR; m[1].flags = 1; m[1].len = 2; m[1].buf = d;
    if (split) {
        /* Some drivers only honour one message per call, which would explain three different
         * registers all returning the same bytes: the address write never happens. */
        struct rdwr w1, w2;
        w1.msgs = &m[0]; w1.n = 1;
        w2.msgs = &m[1]; w2.n = 1;
        if (ioctl(fd, XFER, &w1) < 0) return -1;
        if (ioctl(fd, XFER, &w2) < 0) return -1;
        *v = (unsigned short)((d[0] << 8) | d[1]);
        return 0;
    }
    r.msgs = m; r.n = 2;
    if (ioctl(fd, XFER, &r) < 0) return -1;
    *v = (unsigned short)((d[0] << 8) | d[1]);
    return 0;
}

int main(int argc, char **argv)
{
    int n = argc > 1 ? atoi(argv[1]) : 10;
    split = argc > 2;
    int i;
    setvbuf(stdout, NULL, _IONBF, 0);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { perror("open"); return 1; }
    printf("expecting 0028=0031 0016=051e 0008=0020 if the passthrough works\n");
    for (i = 0; i < n; i++) {
        unsigned short a = 0, b = 0, c = 0;
        int ra = rd16(0x0028, &a), rb = rd16(0x0016, &b), rc = rd16(0x0008, &c);
        printf("  0028=%04x(%d)  0016=%04x(%d)  0008=%04x(%d)\n", a, ra, b, rb, c, rc);
        usleep(700000);
    }
    close(fd);
    return 0;
}
