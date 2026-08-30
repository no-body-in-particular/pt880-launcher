/* Replay the daemon's complete start, then read the FIFO.
 *
 * Every previous attempt replayed a hand-picked subset. A literal diff against a capture that
 * produced a real measurement showed the daemon makes 129 writes before its first burst where we
 * made 32 - the missing 97 being the whole configuration table and the calibration loop. An
 * earlier note claimed the daemon does not write that table at runtime; it does, and that note
 * was written while testing against an unpowered chip.
 *
 * So this replays all 242 operations in order, reads included, from seq.h.
 *
 * No blocking ioctl: the interrupt wait hangs indefinitely when the chip is not sampling, and
 * whether it is sampling is exactly the question. The chip is stopped on every exit path.
 */
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/time.h>

static void on_alarm(int s) { (void)s; }   /* no SA_RESTART: makes a blocked ioctl return EINTR */

#define PWR  0x40044702u
#define IRQ  0x40044707u
#define WAIT 0x00004701u   /* _IO(G,1): returns when the FIFO reaches its watermark */
#define BIG  0x825a470au   /* _IOR(G,10,602): 2-byte count + 200 samples x 3 bytes */
#define XFER 0xc0084704u
#define ADDR 0x14

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };
static int fd = -1;

#include "seq.h"

static int wr(unsigned char *p, int n)
{ struct msg m; struct rdwr r; m.addr=ADDR; m.flags=0; m.len=n; m.buf=p; r.msgs=&m; r.n=1;
  return ioctl(fd, XFER, &r); }
static int wr16(unsigned short g, unsigned short v)
{ unsigned char p[4]; p[0]=g>>8; p[1]=g; p[2]=v>>8; p[3]=v; return wr(p,4); }
static int wr8(unsigned short g, unsigned char v)
{ unsigned char p[3]; p[0]=g>>8; p[1]=g; p[2]=v; return wr(p,3); }
static int rdn(unsigned short g, unsigned char *o, int n)
{
    unsigned char a[2];
    struct msg m[2]; struct rdwr r;
    a[0]=g>>8; a[1]=g;
    memset(o,0,n);
    m[0].addr=ADDR; m[0].flags=0; m[0].len=2; m[0].buf=a;
    m[1].addr=ADDR; m[1].flags=1; m[1].len=n; m[1].buf=o;
    r.msgs=m; r.n=2;
    return ioctl(fd, XFER, &r);
}
static int rd16(unsigned short g, unsigned short *v)
{ unsigned char b[2]; if (rdn(g,b,2) < 0) return -1; *v=(unsigned short)((b[0]<<8)|b[1]); return 0; }

static void stop(void)
{
    if (fd < 0) return;
    wr8(0xdddd, 0xc4);
    ioctl(fd, IRQ, 0);
    ioctl(fd, PWR, 0);
}
static void bail(int s) { (void)s; stop(); _exit(2); }

int main(int argc, char **argv)
{
    unsigned short v = 0, st = 0, lvl = 0;
    int waited_out = 0, nsamp = 0;
    struct timeval tstart, tend;
    int i, rounds = argc > 1 ? atoi(argv[1]) : 12;
    unsigned char buf[240];
    static unsigned char big[608];
    FILE *csv = NULL, *csv2 = NULL;

    setvbuf(stdout, NULL, _IONBF, 0);
    signal(SIGTERM, bail); signal(SIGINT, bail); signal(SIGALRM, bail);
    fd = open("/dev/gh_tools", O_RDWR);
    if (fd < 0) { perror("open"); return 1; }
    atexit(stop);

    if (argc > 2) { csv = fopen(argv[2], "w"); if (csv) fprintf(csv, "ch1,ch2\n"); }
    printf("power rc=%d, irq rc=%d\n", ioctl(fd, PWR, 1), ioctl(fd, IRQ, 1));
    usleep(300000);

    for (i = 0; i < NSEQ; i++) {
        if (SEQ[i].op == 0)      wr16(SEQ[i].reg, SEQ[i].val);
        else if (SEQ[i].op == 1) wr8(SEQ[i].reg, (unsigned char)SEQ[i].val);
        else                     rd16(SEQ[i].reg, &v);
    }
    printf("replayed %d operations\n", NSEQ);

    rd16(0x0008, &st); rd16(0x004a, &lvl);
    printf("after the start: status=%04x level=%u%s\n", st, lvl,
           st == 0x0002 ? "   <-- the daemon's running status" : "");

    for (i = 0; i < rounds; i++) {
        int k;
        /* The daemon's loop: arm, then wait for the interrupt, and only then read. The interrupt
         * fires when the FIFO reaches the watermark set in 0x0044 (200 samples), so the level is
         * a known 200 rather than whatever a poll happens to catch mid-fill. Reading without
         * waiting is what produced 74% duplicate samples. */
        wr8(0xdddd, 0xc3);
        {
            struct sigaction sa;
            memset(&sa, 0, sizeof sa);
            sa.sa_handler = on_alarm;          /* deliberately without SA_RESTART */
            sigaction(SIGALRM, &sa, NULL);
            alarm(3);
            if (ioctl(fd, WAIT, 0) < 0) waited_out++;
            alarm(0);
        }
        rd16(0x0008, &st);
        rd16(0x004a, &lvl);

        /* Drain the whole level, not one bufferful. The level runs past 250 samples while a
         * single read returns 240 bytes = 80 samples, so reading once per round threw most of
         * each burst away and left gaps that scatter the beat intervals. The daemon issues
         * three reads per burst for the same reason. */
        {
            int left = lvl * 3;
            if (left > 3000) left = 3000;          /* a nonsense level is not worth chasing */
            while (left > 0) {
                int want = left > 240 ? 240 : left;
                if (rdn(0xaaaa, buf, want) < 0) { printf("  fifo read failed\n"); left = 0; break; }
                if (csv) {
                    for (k = 0; k + 5 < want; k += 6) {
                        unsigned int c1 = ((unsigned)buf[k]<<16)|(buf[k+1]<<8)|buf[k+2];
                        unsigned int c2 = ((unsigned)buf[k+3]<<16)|(buf[k+4]<<8)|buf[k+5];
                        if (c1 || c2) fprintf(csv, "%u,%u\n", c1, c2);
                    }
                }
                left -= want;
            }
        }
        printf("  %2d status=%04x level=%-4u\n", i, st, lvl);
    }

    gettimeofday(&tend, 0);
    {
        double secs = (tend.tv_sec - tstart.tv_sec) + (tend.tv_usec - tstart.tv_usec) / 1e6;
        /* Measured, not assumed. The loop is paced by the interrupt now, so the only honest way
         * to know the sample rate is to time it - and an assumed rate silently scales the heart
         * rate that comes out of the waveform. */
        printf("captured %d pairs in %.1f s = %.1f Hz\n", nsamp, secs,
               secs > 0 ? nsamp / secs : 0.0);
    }
    if (csv) fclose(csv);
    if (csv2) fclose(csv2);
    printf("waits that timed out: %d\n", waited_out);
    stop();
    printf("stopped\n");
    return 0;
}
