/* Can we power the chip ourselves?
 *
 * The daemon issues ioctl(_IOW('G',2,4), 1) - with 1 as an immediate value, not a pointer -
 * before its first i2c write. Nothing we wrote ever did that, which is why every read taken with
 * the daemon stopped came back as zeros: the part had no power, and we were blaming the protocol.
 *
 * If the chip id reads back after this, driving the sensor without the vendor daemon is possible.
 */
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <stdlib.h>

#define PWR  0x40044702u
#define CMD  0x40184709u
#define XFER 0xc0084704u
#define ADDR 0x14

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };

static int fd = -1;

static int rd16(unsigned short reg, unsigned short *v)
{
    unsigned char a[2], d[2];
    struct msg m[2]; struct rdwr r;
    a[0] = reg >> 8; a[1] = reg;
    d[0] = d[1] = 0;
    m[0].addr = ADDR; m[0].flags = 0; m[0].len = 2; m[0].buf = a;
    m[1].addr = ADDR; m[1].flags = 1; m[1].len = 2; m[1].buf = d;
    r.msgs = m; r.n = 2;
    if (ioctl(fd, XFER, &r) < 0) return -1;
    *v = (unsigned short)((d[0] << 8) | d[1]);
    return 0;
}

int main(void)
{
    unsigned short id = 0, b = 0, c = 0;
    int i;
    setvbuf(stdout, NULL, _IONBF, 0);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { perror("open"); return 1; }

    printf("before power:  ");
    rd16(0x0028, &id); printf("0028=%04x\n", id);

    printf("power on rc=%d\n", ioctl(fd, PWR, 1));
    usleep(300000);

    for (i = 0; i < 5; i++) {
        rd16(0x0028, &id); rd16(0x0016, &b); rd16(0x0008, &c);
        printf("  0028=%04x  0016=%04x  0008=%04x%s\n", id, b, c,
               id == 0x0031 ? "   <- chip id, alive" : "");
        usleep(400000);
    }

    printf("power off rc=%d\n", ioctl(fd, PWR, 0));
    close(fd);
    return 0;
}
