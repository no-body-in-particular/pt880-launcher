/* Print what the driver emits on /dev/input/event1 while a measurement runs.
 *
 * This is the ground truth for "did a measurement actually happen": REL_RX carries
 * (hr << 8) | spo2 and REL_RZ ticks progress. Without it an empty i2c log is ambiguous - it
 * could mean the chip is driven somewhere we cannot see, or simply that nothing measured.
 *
 * poll() rather than a blocking read, so a quiet device still exits and reports "0 events"
 * instead of being killed with nothing printed.
 */
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdlib.h>
#include <poll.h>
#include <sys/time.h>

struct ev { long sec, usec; unsigned short type, code; int value; };

int main(int argc, char **argv)
{
    struct pollfd p;
    struct timeval t0, t;
    struct ev e;
    double secs = argc > 1 ? atof(argv[1]) : 15.0;
    int n = 0;

    setvbuf(stdout, NULL, _IONBF, 0);
    p.fd = open("/dev/input/event1", O_RDONLY);
    p.events = POLLIN;
    if (p.fd < 0) { perror("open"); return 1; }
    gettimeofday(&t0, 0);
    for (;;) {
        double el;
        gettimeofday(&t, 0);
        el = (t.tv_sec - t0.tv_sec) + (t.tv_usec - t0.tv_usec) / 1e6;
        if (el > secs) break;
        if (poll(&p, 1, 500) <= 0) continue;
        if (read(p.fd, &e, sizeof e) != sizeof e) break;
        if (e.type == 0) continue;
        printf("%6.2f  type=%d code=%d value=%d", el, e.type, e.code, e.value);
        if (e.type == 2 && e.code == 3)
            printf("   hr=%d spo2=%d", (e.value >> 8) & 0xff, e.value & 0xff);
        printf("\n");
        n++;
    }
    printf("%d events\n", n);
    return 0;
}
