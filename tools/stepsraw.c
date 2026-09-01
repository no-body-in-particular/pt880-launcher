/* Read the step counter without asking the sensor framework.
 *
 * The launcher gets steps through SensorManager and has never had a number out of it. The counter
 * is a DA217 which dumpsys marks on-demand, so both a listener and a trigger are registered and
 * neither delivers - the last= column stays at zero where gh30x_sensor, asked the same two ways,
 * carries real values.
 *
 * The HAL does not do anything privileged to reach it. sensors.sl8521e.so finds an input device by
 * name, controls it through sysfs, and reads input events:
 *
 *     /sys/class/input/input<N>/name         "DA217 Step Counter"
 *     /sys/class/input/input<N>/enable       1 to run
 *     /sys/class/input/input<N>/enable_sc    the step counter specifically
 *     /sys/class/input/input<N>/delay        sampling period, ms
 *     /sys/class/input/input<N>/clear_step   zero the count
 *     /dev/input/event<N>                    where the count arrives
 *
 * So this does the same and prints what turns up. If steps arrive here and not through the
 * framework, the fault is above the driver and the launcher can read the device directly; if
 * nothing arrives here either, the counter is not running and no amount of registering will help.
 *
 *   stepsraw            find it, show its sysfs, enable it, read for 30 seconds
 *   stepsraw -t N       read for N seconds
 *   stepsraw -n         do not enable, just watch - to see whether something else already has it
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <errno.h>
#include <sys/time.h>
#include <sys/select.h>

/* struct input_event, spelled out rather than included: the NDK header disagrees with this
 * kernel's timeval width often enough to be worth not arguing about. */
struct iev {
    long tv_sec;
    long tv_usec;
    unsigned short type;
    unsigned short code;
    int value;
};

#define EV_SYN 0x00
#define EV_KEY 0x01
#define EV_REL 0x02
#define EV_ABS 0x03
#define EV_MSC 0x04

static int read_line(const char *path, char *out, int n)
{
    int fd = open(path, O_RDONLY), got;
    if (fd < 0) return -1;
    got = read(fd, out, n - 1);
    close(fd);
    if (got < 0) return -1;
    out[got] = 0;
    while (got > 0 && (out[got - 1] == '\n' || out[got - 1] == '\r')) out[--got] = 0;
    return 0;
}

static int write_str(const char *path, const char *val)
{
    int fd = open(path, O_WRONLY), n;
    if (fd < 0) return -1;
    n = write(fd, val, strlen(val));
    close(fd);
    return n < 0 ? -1 : 0;
}

/* Anything whose name mentions steps. Three chips appear in the HAL - DA217, QMAX981, SC7A21 -
 * and which one this watch carries is not worth hardcoding. */
static int find_step_input(char *sysdir, int n, char *name, int nn)
{
    DIR *d = opendir("/sys/class/input");
    struct dirent *e;
    int found = -1;

    if (!d) return -1;
    while ((e = readdir(d))) {
        char path[256], nm[128];
        if (strncmp(e->d_name, "input", 5) != 0) continue;
        snprintf(path, sizeof path, "/sys/class/input/%s/name", e->d_name);
        if (read_line(path, nm, sizeof nm) < 0) continue;
        if (strstr(nm, "tep") == NULL && strstr(nm, "TEP") == NULL) continue;
        snprintf(sysdir, n, "/sys/class/input/%s", e->d_name);
        snprintf(name, nn, "%s", nm);
        found = atoi(e->d_name + 5);
        break;
    }
    closedir(d);
    return found;
}

static void show_attrs(const char *sysdir)
{
    static const char *attrs[] = {
        "enable", "enable_sc", "delay", "chip_info", "clear_step", "step_count", NULL
    };
    int i;
    printf("sysfs %s\n", sysdir);
    for (i = 0; attrs[i]; i++) {
        char path[320], val[128];
        snprintf(path, sizeof path, "%s/%s", sysdir, attrs[i]);
        if (access(path, F_OK) != 0) continue;
        if (read_line(path, val, sizeof val) == 0) printf("  %-12s = %s\n", attrs[i], val);
        else printf("  %-12s   present, unreadable (%s)\n", attrs[i], strerror(errno));
    }
}

int main(int argc, char **argv)
{
    char sysdir[256], name[128], evpath[64];
    int i, secs = 30, enable = 1, num, fd, events = 0;
    struct timeval start, now;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-t") == 0 && i + 1 < argc) secs = atoi(argv[++i]);
        else if (strcmp(argv[i], "-n") == 0) enable = 0;
    }

    num = find_step_input(sysdir, sizeof sysdir, name, sizeof name);
    if (num < 0) {
        printf("no input device with \"step\" in its name\n");
        return 1;
    }
    printf("found input%d: %s\n", num, name);
    show_attrs(sysdir);

    if (enable) {
        char path[320];
        snprintf(path, sizeof path, "%s/enable", sysdir);
        if (write_str(path, "1") == 0) printf("enable <- 1\n");
        snprintf(path, sizeof path, "%s/enable_sc", sysdir);
        if (write_str(path, "1") == 0) printf("enable_sc <- 1\n");
        /* A delay of zero means "as fast as you like" to some of these drivers and "never" to
         * others, so ask for something specific. */
        snprintf(path, sizeof path, "%s/delay", sysdir);
        if (write_str(path, "200") == 0) printf("delay <- 200\n");
        show_attrs(sysdir);
    }

    snprintf(evpath, sizeof evpath, "/dev/input/event%d", num);
    fd = open(evpath, O_RDONLY);
    if (fd < 0) {
        printf("cannot open %s (%s)\n", evpath, strerror(errno));
        return 1;
    }
    printf("reading %s for %d seconds - walk a few steps\n", evpath, secs);

    gettimeofday(&start, NULL);
    for (;;) {
        fd_set r;
        struct timeval tv;
        int n;

        gettimeofday(&now, NULL);
        if (now.tv_sec - start.tv_sec >= secs) break;

        FD_ZERO(&r);
        FD_SET(fd, &r);
        tv.tv_sec = 1;
        tv.tv_usec = 0;
        n = select(fd + 1, &r, NULL, NULL, &tv);
        if (n <= 0) continue;

        {
            struct iev ev;
            int got = read(fd, &ev, sizeof ev);
            if (got != (int) sizeof ev) continue;
            if (ev.type == EV_SYN) continue;
            events++;
            printf("  type=%u code=%u value=%d%s\n", ev.type, ev.code, ev.value,
                   ev.type == EV_ABS ? "   (abs - a counter reports here)" :
                   ev.type == EV_REL ? "   (rel)" : "");
        }
    }
    close(fd);

    printf("%d event(s) in %d seconds\n", events, secs);
    if (events == 0)
        printf("nothing arrived: the counter is not producing, so registering for it cannot work\n");
    show_attrs(sysdir);
    return 0;
}
