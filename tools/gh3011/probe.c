/* Probe the five unidentified 'G' commands, safely.
 *
 * A first attempt called them directly and hung for ten minutes: one of these blocks, which is
 * what gh_dev_wait_irq would do. So every call now happens in a child process with its own
 * alarm, and the parent reports whether the child returned or had to be killed. A blocking
 * command is then a result rather than a hang, and the chip is checked between calls so that a
 * command which breaks it is identified immediately.
 */
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/wait.h>

#define PWR  0x40044702u
#define XFER 0xc0084704u
#define ADDR 0x14

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };
static int fd = -1;

static int rd16(unsigned short g, unsigned short *v)
{
    unsigned char a[2], d[2];
    struct msg m[2]; struct rdwr r;
    a[0]=g>>8; a[1]=g; d[0]=d[1]=0;
    m[0].addr=ADDR; m[0].flags=0; m[0].len=2; m[0].buf=a;
    m[1].addr=ADDR; m[1].flags=1; m[1].len=2; m[1].buf=d;
    r.msgs=m; r.n=2;
    if (ioctl(fd, XFER, &r) < 0) return -1;
    *v = (unsigned short)((d[0]<<8)|d[1]);
    return 0;
}

static unsigned short id_now(void) { unsigned short id = 0; rd16(0x0028, &id); return id; }

/* Returns: 0.. = ioctl return, -1 = failed with errno in *err, -99 = blocked past the alarm. */
static int try_ioctl(unsigned int req, unsigned long arg, int *err)
{
    pid_t p = fork();
    int st = 0;
    if (p == 0) {
        int rc;
        alarm(3);                       /* SIGALRM kills the child if the call blocks */
        rc = ioctl(fd, req, arg);
        _exit(rc < 0 ? (errno & 0x7f) | 0x80 : 0);
    }
    if (p < 0) return -1;
    waitpid(p, &st, 0);
    if (WIFSIGNALED(st)) return -99;    /* alarm fired: the command blocks */
    if (WIFEXITED(st)) {
        int code = WEXITSTATUS(st);
        if (code & 0x80) { *err = code & 0x7f; return -1; }
        return 0;
    }
    return -1;
}

int main(void)
{
    static const int nrs[] = {0, 1, 3, 5, 6};
    unsigned short id;
    int i;

    setvbuf(stdout, NULL, _IONBF, 0);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { perror("open"); return 1; }

    ioctl(fd, PWR, 1);
    usleep(300000);
    id = id_now();
    printf("baseline chip id = %04x%s\n", id, id == 0x0031 ? " (alive)" : " (NOT ALIVE)");
    if (id != 0x0031) { ioctl(fd, PWR, 0); return 1; }

    for (i = 0; i < (int)(sizeof nrs / sizeof nrs[0]); i++) {
        int nr = nrs[i], rc, err = 0;
        unsigned int io  = 0x00004700u | nr;
        unsigned int iow = 0x40044700u | nr;

        rc = try_ioctl(io, 0, &err);
        id = id_now();
        printf("  _IO ('G',%d)  rc=%s  id=%04x%s\n", nr,
               rc == -99 ? "BLOCKS" : (rc < 0 ? "fail" : "ok"), id,
               id == 0x0031 ? "" : "  <-- chip stopped");
        if (id != 0x0031) { printf("  that one broke it; stopping\n"); break; }

        rc = try_ioctl(iow, 1, &err);
        id = id_now();
        printf("  _IOW('G',%d)  rc=%s  id=%04x%s\n", nr,
               rc == -99 ? "BLOCKS" : (rc < 0 ? "fail" : "ok"), id,
               id == 0x0031 ? "" : "  <-- chip stopped");
        if (id != 0x0031) { printf("  that one broke it; stopping\n"); break; }
    }

    ioctl(fd, PWR, 0);
    close(fd);
    printf("done, power off\n");
    return 0;
}
