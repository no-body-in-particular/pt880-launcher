/* Does the driver report a saturation of its own while we drive the chip?
 *
 * docs/vitals.md records that a reading arrives on /dev/input/event1 as a single REL_RX carrying
 * two bytes - heart rate in the high one, SpO2 in the low - and a once-a-second REL_RZ. That was
 * written from watching the vendor daemon work.
 *
 * If those events come from the kernel driver, they are available to anyone who brings the chip
 * up, and reimplementing the ratio here is unnecessary: the eight second cycle the vendor spends
 * is the chip converging, not a daemon computing. If they come from the daemon writing back into
 * the input device, then replacing the daemon took them away and the silence here will say so.
 *
 * Either answer is worth having, and the difference is one read of a file descriptor.
 *
 * This only listens. Something else has to be driving the sensor at the same time - the daemon
 * on its own cycle, or ppgd - or there is nothing to hear.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/time.h>
#include <sys/select.h>

/* struct input_event, as this kernel lays it out. */
struct iev {
    struct timeval time;
    unsigned short type;
    unsigned short code;
    int value;
};

#define EV_SYN 0x00
#define EV_REL 0x02
#define EV_ABS 0x03

static const char *relname(unsigned short c)
{
    switch (c) {
        case 0x00: return "REL_X";
        case 0x01: return "REL_Y";
        case 0x02: return "REL_Z";
        case 0x03: return "REL_RX";
        case 0x04: return "REL_RY";
        case 0x05: return "REL_RZ";
        default:   return "REL_?";
    }
}

int main(int argc, char **argv)
{
    const char *path = argc > 1 ? argv[1] : "/dev/input/event1";
    int secs = argc > 2 ? atoi(argv[2]) : 60;
    int fd, seen = 0;
    time_t end;
    struct iev e;

    setvbuf(stdout, NULL, _IONBF, 0);
    fd = open(path, O_RDONLY);
    if (fd < 0) { printf("cannot open %s: %s\n", path, strerror(errno)); return 1; }
    printf("listening on %s for %d seconds\n", path, secs);
    printf("(something else must be driving the sensor meanwhile)\n\n");

    end = time(NULL) + secs;
    while (time(NULL) < end) {
        fd_set r;
        struct timeval tv;
        FD_ZERO(&r);
        FD_SET(fd, &r);
        tv.tv_sec = 2;
        tv.tv_usec = 0;
        if (select(fd + 1, &r, NULL, NULL, &tv) <= 0) continue;
        if (read(fd, &e, sizeof e) != (int)sizeof e) continue;
        if (e.type == EV_SYN) continue;
        seen++;
        if (e.type == EV_REL) {
            /* The interesting one: two numbers packed into a single value. Both readings of the
             * packing are printed because which byte is which is recorded but not verified. */
            printf("%s value=%d   high byte=%d  low byte=%d\n",
                   relname(e.code), e.value,
                   (e.value >> 8) & 0xff, e.value & 0xff);
        } else {
            printf("type=%u code=%u value=%d\n", e.type, e.code, e.value);
        }
    }

    printf("\n%d events in %d seconds\n", seen, secs);
    if (!seen) {
        printf("nothing - so these events came from the vendor daemon, not the driver,\n");
        printf("and replacing it took them with it.\n");
    }
    close(fd);
    return 0;
}
