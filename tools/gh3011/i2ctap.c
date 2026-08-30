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
 *
 * <h3>The command socket</h3>
 *
 * The chip conversation alone does not explain the daemon, because the daemon does not decide
 * when to measure. init gives it a socket - `socket gh30x_socket stream 660 root system` in
 * init.sl8521e_1h10ll.rc - and com.ic.work sends it commands over that: the strings name
 * `GH30xService::start MeasureType` and `GH30x_CMD_Handler 0x%04x %d param %d`. Left alone with
 * no client it sits in wear detection with the LED lit and reports nothing, which is exactly what
 * ten minutes of it on a sleeping wrist produced.
 *
 * So accept() and the reads and writes on whatever it returns are logged too. That is the half
 * that says which mode a saturation is measured in and what is asked for to start one.
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
static void out(const char *s, int n);      /* defined below; dump() needs both */
static double now(void);

static int (*real_accept)(int, void *, void *);
static ssize_t (*real_read)(int, void *, size_t);
static ssize_t (*real_write)(int, const void *, size_t);

/* Sockets accepted on the command channel. Small fixed set: the daemon serves one client. */
static int cmdfd[8];
static int ncmd;

static int is_cmd(int fd)
{
    int i;
    for (i = 0; i < ncmd; i++) if (cmdfd[i] == fd) return 1;
    return 0;
}

/* Hex, because the commands are binary and a %s of them would say nothing. */
static void dump(const char *tag, int fd, const unsigned char *b, int n)
{
    char line[1024];
    int p = 0, i;
    p += snprintf(line + p, sizeof line - p, "%8.3f %s fd=%d len=%d ", now(), tag, fd, n);
    for (i = 0; i < n && i < 64 && p < (int)sizeof line - 4; i++)
        p += snprintf(line + p, sizeof line - p, "%02x", b[i]);
    p += snprintf(line + p, sizeof line - p, "\n");
    out(line, p);
}

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

    /* The mode ioctls carry a struct, and the struct is the whole question.
     *
     * 0x40184709 is _IOW('G',9,24) - twenty-four bytes that say which measurement to run, and
     * ppgd already writes mode 4 for green and 5 for red and infrared into its first word. The
     * daemon calls it four hundred times a measurement and the tap logged only the pointer, so
     * which MeasureType selects a saturation has been sitting in a capture unread.
     *
     * 0xc0044708 is _IOWR('G',8,4), which the daemon calls while its log says gh_dev_wait_cmd -
     * a four-byte word coming back from the driver, which is how it is told what to do next.
     *
     * Both are safe to dereference: the size is in the request code and both are pointers by
     * that encoding. That is the distinction the earlier crash-loop taught - not "do not
     * dereference", but "dereference only what the request says is a pointer".
     */
    if ((r == 0x40184709u || r == 0xc0044708u) && arg) {
        unsigned char *q = arg;
        int len = (r == 0x40184709u) ? 24 : 4;
        char b[160];
        int p = 0, i;
        p += snprintf(b + p, sizeof b - p, "%8.3f %s ", t_after,
                      r == 0x40184709u ? "SETMODE" : "WAITCMD");
        for (i = 0; i < len; i++) p += snprintf(b + p, sizeof b - p, "%02x", q[i]);
        p += snprintf(b + p, sizeof b - p, " rc=%d\n", rc);
        out(b, p);
        return rc;
    }

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

/* Everything the daemon is told, and everything it answers.
 *
 * The chip half of this file shows what the sensor was asked to do; this half shows who asked and
 * for what. Without it the capture is a conversation with one side missing.
 */
int accept(int fd, void *addr, void *len)
{
    int c;
    if (!real_accept) real_accept = dlsym(RTLD_NEXT, "accept");
    c = real_accept(fd, addr, len);
    if (c >= 0 && ncmd < 8) {
        char b[96];
        int p = snprintf(b, sizeof b, "%8.3f ACCEPT listen=%d -> fd=%d\n", now(), fd, c);
        cmdfd[ncmd++] = c;
        out(b, p);
    }
    return c;
}

ssize_t read(int fd, void *buf, size_t n)
{
    ssize_t r;
    if (!real_read) real_read = dlsym(RTLD_NEXT, "read");
    r = real_read(fd, buf, n);
    if (r > 0 && is_cmd(fd)) dump("CMD-IN ", fd, buf, (int) r);
    return r;
}

ssize_t write(int fd, const void *buf, size_t n)
{
    ssize_t r;
    if (!real_write) real_write = dlsym(RTLD_NEXT, "write");
    r = real_write(fd, buf, n);
    if (r > 0 && is_cmd(fd)) dump("CMD-OUT", fd, buf, (int) r);
    return r;
}
