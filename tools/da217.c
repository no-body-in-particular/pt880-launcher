/* Read the step counter off the i2c bus, since nothing above it reports one.
 *
 * The counter is a DA217 at 2-0026. Its input device is enabled and produces no events at all -
 * twenty-five seconds of walking, zero - so the framework was never the problem and neither is the
 * launcher's registration. This goes under the driver to see whether the chip is counting.
 *
 * The register map is not guessed. Dump, walk, dump again, and whatever moved by roughly the
 * number of steps taken is the counter. That is how the wear bit was found, after three wrong
 * answers from reading a decompiler instead of the part.
 *
 *   da217              chip id and a full register dump
 *   da217 -d N         dump, wait N seconds, dump again, print what changed
 *   da217 -w R         watch register R (and R+1 as a 16-bit pair) once a second
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>

#define I2C_SLAVE 0x0703
/* The da217 driver has the address bound, so the polite ioctl is refused with EBUSY. FORCE is
 * what i2cget -f uses and is safe enough here: everything this does is a read, and the driver
 * is not producing anything to be disturbed. */
#define I2C_SLAVE_FORCE 0x0706
#define ADDR      0x26
#define BUS       "/dev/i2c-2"

static int fd = -1;

static int rd(unsigned char reg, unsigned char *out, int n)
{
    if (write(fd, &reg, 1) != 1) return -1;
    if (read(fd, out, n) != n) return -1;
    return 0;
}

static int rd8(unsigned char reg, unsigned char *v)
{
    return rd(reg, v, 1);
}

static void dump(unsigned char *buf)
{
    int i;
    memset(buf, 0, 256);
    /* One register at a time: this part does not reliably auto-increment across the whole map,
     * and a burst that silently repeats a byte would invent a difference later. */
    for (i = 0; i < 256; i++) {
        unsigned char v = 0;
        if (rd8((unsigned char) i, &v) == 0) buf[i] = v;
    }
}

int main(int argc, char **argv)
{
    unsigned char id = 0, a[256], b[256];
    int i, secs = 0, watch = -1, changed = 0;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-d") == 0 && i + 1 < argc) secs = atoi(argv[++i]);
        else if (strcmp(argv[i], "-w") == 0 && i + 1 < argc) watch = (int) strtol(argv[++i], NULL, 0);
    }

    fd = open(BUS, O_RDWR);
    if (fd < 0) { printf("cannot open %s\n", BUS); return 1; }
    if (ioctl(fd, I2C_SLAVE_FORCE, ADDR) < 0) { printf("cannot address 0x%02x\n", ADDR); return 1; }

    if (rd8(0x01, &id) < 0) { printf("no answer from 0x%02x\n", ADDR); return 1; }
    printf("chip id (0x01) = 0x%02x%s\n", id, id == 0x13 ? "   DA217" : "");

    if (watch >= 0) {
        printf("watching 0x%02x - walk\n", watch);
        for (i = 0; i < 30; i++) {
            unsigned char lo = 0, hi = 0;
            rd8((unsigned char) watch, &lo);
            rd8((unsigned char)(watch + 1), &hi);
            printf("  %2d  0x%02x=%3u  0x%02x=%3u   16-bit %u\n",
                   i, watch, lo, watch + 1, hi, (unsigned)(lo | (hi << 8)));
            sleep(1);
        }
        close(fd);
        return 0;
    }

    dump(a);
    if (secs <= 0) {
        printf("registers:\n");
        for (i = 0; i < 256; i++) {
            if (i % 16 == 0) printf("  %02x:", i);
            printf(" %02x", a[i]);
            if (i % 16 == 15) printf("\n");
        }
        close(fd);
        return 0;
    }

    printf("dumped. walk for %d seconds\n", secs);
    sleep(secs);
    dump(b);

    printf("registers that changed:\n");
    for (i = 0; i < 256; i++) {
        if (a[i] == b[i]) continue;
        changed++;
        printf("  0x%02x  %02x -> %02x   (%d -> %d, delta %d)\n",
               i, a[i], b[i], a[i], b[i], (int) b[i] - (int) a[i]);
    }
    if (!changed) printf("  nothing changed - the chip is not counting\n");

    /* And the 16-bit pairs, because a step count that crosses 255 shows up as two bytes moving
     * and neither delta meaning anything on its own. */
    printf("16-bit pairs that changed:\n");
    for (i = 0; i < 255; i++) {
        unsigned before = (unsigned)(a[i] | (a[i + 1] << 8));
        unsigned after  = (unsigned)(b[i] | (b[i + 1] << 8));
        if (before != after && after > before && after - before < 1000)
            printf("  0x%02x/0x%02x  %u -> %u   (+%u)\n", i, i + 1, before, after, after - before);
    }
    close(fd);
    return 0;
}
