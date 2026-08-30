/* Start the sensor the way the app does, then read the chip while it runs.
 *
 * gh30x_ppgStart turned out to be nothing but ioctl(_IOW('G',9,24), {4}), which is a request
 * rather than a start: the chip is actually driven by the HAL and the daemon, and those only
 * move when the sensor is activated through the Android sensor framework. So activate it the
 * same way a listener does - ASensorEventQueue_enableSensor on ppg_egc_sensor - and read the
 * i2c registers while it is genuinely running.
 *
 * The sensor is disabled on every exit path.
 */
#include <stdio.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <android/sensor.h>
#include <android/looper.h>

#define XFER 0xc0084704u
#define CMD  0x40184709u
#define ADDR 0x14

struct msg { unsigned short addr, flags, len; unsigned char *buf; };
struct rdwr { struct msg *msgs; int n; };

static ASensorEventQueue *q;
static const ASensor *sens;
static int fd = -1;

static void stop_all(void)
{
    if (q && sens) ASensorEventQueue_disableSensor(q, sens);
}
static void bail(int s) { (void)s; stop_all(); _exit(2); }

static int rdn(unsigned short reg, unsigned char *o, int n)
{
    unsigned char a[2];
    struct msg m[2]; struct rdwr r;
    a[0] = reg >> 8; a[1] = reg & 0xff;
    memset(o, 0, n);
    m[0].addr = ADDR; m[0].flags = 0; m[0].len = 2; m[0].buf = a;
    m[1].addr = ADDR; m[1].flags = 1; m[1].len = n; m[1].buf = o;
    r.msgs = m; r.n = 2;
    return ioctl(fd, XFER, &r);
}

int main(int argc, char **argv)
{
    ASensorManager *mgr;
    ASensorList list;
    ALooper *looper;
    struct timeval t0, t;
    double secs = argc > 1 ? atof(argv[1]) : 15.0;
    unsigned short base = argc > 2 ? (unsigned short)strtol(argv[2], 0, 0) : 0x0086;
    int n, i, want = -1, rows = 0;

    setvbuf(stdout, NULL, _IONBF, 0);
    mgr = ASensorManager_getInstance();
    if (!mgr) { printf("no sensor manager\n"); return 1; }
    n = ASensorManager_getSensorList(mgr, &list);
    printf("%d sensors:\n", n);
    for (i = 0; i < n; i++) {
        const char *nm = ASensor_getName(list[i]);
        printf("  [%d] type=%d %s\n", i, ASensor_getType(list[i]), nm ? nm : "?");
        if (nm && (strstr(nm, "ppg") || strstr(nm, "PPG") || strstr(nm, "egc") || strstr(nm, "gh30x") || ASensor_getType(list[i]) == 21)) want = i;
    }
    if (want < 0) { printf("no ppg sensor in the list\n"); return 1; }
    sens = list[want];
    printf("using [%d] %s\n", want, ASensor_getName(sens));

    fd = open("/dev/gh_tools", O_RDWR);
    signal(SIGTERM, bail); signal(SIGINT, bail);
    signal(SIGSEGV, bail); signal(SIGALRM, bail);
    atexit(stop_all);
    alarm((int)secs + 20);

    looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    q = ASensorManager_createEventQueue(mgr, looper, 3, NULL, NULL);
    printf("enableSensor  -> %d\n", ASensorEventQueue_enableSensor(q, sens));
    printf("setEventRate  -> %d\n", ASensorEventQueue_setEventRate(q, sens, 10000));
    sleep(2);

    printf("\nsensor active. reading 0x%04x\n", base);
    gettimeofday(&t0, 0);
    for (;;) {
        unsigned char b[16];
        ASensorEvent ev;
        double el;
        unsigned int c1, c2;
        gettimeofday(&t, 0);
        el = (t.tv_sec - t0.tv_sec) + (t.tv_usec - t0.tv_usec) / 1e6;
        if (el > secs) break;
        while (ASensorEventQueue_getEvents(q, &ev, 1) > 0)
            printf("   event type=%d  %.2f %.2f %.2f\n", ev.type,
                   ev.data[0], ev.data[1], ev.data[2]);
        rdn(base, b, 8);
        c1 = ((unsigned)b[2] << 16) | (b[0] << 8) | b[1];
        c2 = ((unsigned)b[6] << 16) | (b[4] << 8) | b[5];
        printf("%6.2f  ch1=%8u ch2=%8u  raw %02x%02x%02x%02x %02x%02x%02x%02x\n",
               el, c1, c2, b[0],b[1],b[2],b[3],b[4],b[5],b[6],b[7]);
        rows++;
        usleep(40000);
    }
    printf("%d rows\n", rows);
    stop_all();
    printf("sensor disabled\n");
    return 0;
}
