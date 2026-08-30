/* Put the driver in a given mode and leave it there.
 *
 * The daemon reads the mode with _IOWR('G',8,4) and configures the chip to match, so setting it
 * before the measurement is triggered is how to choose which LED runs. Mode 4 is green, 5 is
 * red and IR. Every capture so far has been mode 5 - each reported an spo2 value alongside the
 * rate - which is why the replayed start sequence always lights red.
 */
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <sys/ioctl.h>

int main(int argc, char **argv)
{
    unsigned int w[6];
    unsigned int got = 0;
    int fd = open("/dev/gh_tools", O_RDWR);
    int mode = argc > 1 ? atoi(argv[1]) : 4;
    if (fd < 0) { perror("open"); return 1; }
    memset(w, 0, sizeof w);
    w[0] = (unsigned int)mode;
    printf("set mode %d -> rc=%d\n", mode, ioctl(fd, 0x40184709u, w));
    if (ioctl(fd, 0xc0044708u, &got) >= 0) printf("driver reports mode %u\n", got);
    close(fd);
    return 0;
}
