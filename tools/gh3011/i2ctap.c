/* Log everything the daemon does to the sensor: every ioctl, with timing.
 *
 * Earlier versions logged only three whitelisted commands, because logging the others meant
 * dereferencing their argument - and GH_IOC_ENABLE_POWER passes an immediate, not a pointer, so
 * that crash-looped the daemon. The lesson was the wrong one: the fix is not to skip those
 * commands but to log the argument as a value and never dereference it.
 *
 * That whitelist is why the complete command sequence has never been seen. GH_IOC_INIT and
 * gh_dev_wait_irq are both named in the daemon's strings and neither appears in any capture so
 * far, which is the most likely place the missing piece is hiding.
 *
 * Timestamps are included because replaying the writes verbatim does not reproduce the result,
 * and delay between steps is one of the few things a replay can get wrong invisibly.
 *
 * Only the i2c passthrough has its argument dereferenced, because that one is known to be a
 * pointer. Everything else is printed as a number.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/time.h>

#define XFER 0xc0084704u
#define MAXLINES 400000

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };

static int (*real_ioctl)(int, int, ...);
static int (*real_open)(const char *, int, ...);

static int logfd = -1;
static int lines;
static int ghfd = -1;
static struct timeval t0;

static void out(const char *s, int n)
{
    if (logfd < 0) {
        logfd = real_open ? real_open("/data/local/tmp/i2c.log",
                                      O_WRONLY | O_CREAT | O_APPEND, 0644) : -1;
        gettimeofday(&t0, 0);
    }
    if (logfd >= 0) write(logfd, s, n);
}

static double now(void)
{
    struct timeval t;
    gettimeofday(&t, 0);
    return (t.tv_sec - t0.tv_sec) + (t.tv_usec - t0.tv_usec) / 1e6;
}

int open(const char *path, int flags, ...)
{
    int fd, mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    if (!real_open) real_open = dlsym(RTLD_NEXT, "open");
    fd = real_open(path, flags, mode);
    if (fd >= 0 && path && strstr(path, "gh_tools")) {
        char b[128];
        int p;
        ghfd = fd;
        p = snprintf(b, sizeof b, "OPEN %s -> fd %d\n", path, fd);
        out(b, p);
    }
    return fd;
}

int ioctl(int fd, int req, ...)
{
    va_list ap;
    void *arg;
    unsigned int r;
    int rc;
    double t_before, t_after;

    va_start(ap, req);
    arg = va_arg(ap, void *);
    va_end(ap);

    if (!real_ioctl) real_ioctl = dlsym(RTLD_NEXT, "ioctl");

    t_before = (logfd >= 0) ? now() : 0.0;
    rc = real_ioctl(fd, req, arg);
    r = (unsigned int)req;

    if (fd != ghfd || ghfd < 0 || lines >= MAXLINES) return rc;
    t_after = now();

    if (r == XFER && arg) {
        struct rdwr *q = arg;
        char b[8192];
        int p = 0, i, j;
        if (q->n > 0 && q->n <= 4 && q->msgs) {
            struct msg *m0 = &q->msgs[0];
            p += snprintf(b + p, sizeof b - p, "%8.3f ", t_after);
            if (q->n == 2 && !(m0->flags & 1) && m0->len == 2 && m0->buf) {
                struct msg *m1 = &q->msgs[1];
                p += snprintf(b + p, sizeof b - p, "R %02x%02x", m0->buf[0], m0->buf[1]);
                if (m1->buf)
                    for (j = 0; j < m1->len && j < 1024; j++)
                        p += snprintf(b + p, sizeof b - p, " %02x", m1->buf[j]);
            } else {
                for (i = 0; i < q->n; i++) {
                    struct msg *m = &q->msgs[i];
                    p += snprintf(b + p, sizeof b - p, "%c", (m->flags & 1) ? 'r' : 'W');
                    if (m->buf)
                        for (j = 0; j < m->len && j < 1024; j++)
                            p += snprintf(b + p, sizeof b - p, " %02x", m->buf[j]);
                    p += snprintf(b + p, sizeof b - p, " |");
                }
            }
            p += snprintf(b + p, sizeof b - p, "  rc=%d\n", rc);
            out(b, p);
            lines++;
        }
        return rc;
    }

    /* Every other command on this device. The argument is printed as a value and never
     * dereferenced - some of these pass an immediate, and reading it as a pointer kills the
     * daemon. `blocked` marks a call that took long enough to have been waiting on something,
     * which is how gh_dev_wait_irq will show itself. */
    {
        char b[192];
        double dt = t_after - t_before;
        int p = snprintf(b, sizeof b, "%8.3f IOCTL %08x arg=%08x rc=%d%s\n",
                         t_after, r, (unsigned int)(unsigned long)arg, rc,
                         dt > 0.02 ? "  <-- blocked" : "");
        out(b, p);
        lines++;
    }
    return rc;
}
