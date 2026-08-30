/* vitalsd - measure vitals on request, over a socket.
 *
 * The launcher cannot do this itself. Driving the chip needs the vendor daemon stopped, which
 * needs root, and wsu does not give the app process root - and RootShell's twenty second timeout
 * is shorter than a forty second measurement anyway. So the privileged part lives here, started
 * from init, and the app only speaks to a socket.
 *
 * Protocol, one line each way:
 *
 *     ->  hr            green LED, heart rate
 *     ->  spo2          red and IR, adds the ratio of ratios and the pulse shape
 *     <-  hr=49 spread=2 hz=24.9 ... spo2=98 sbp=102 dbp=66
 *     <-  hr=0 reason=...            when nothing trustworthy came out
 *
 * The socket is in Linux's abstract namespace, which is what android.net.LocalSocket speaks by
 * default, so the Java side needs no filesystem permissions.
 *
 * Only one measurement runs at a time: the sensor is a single piece of hardware, and two
 * overlapping requests would fight over it exactly as we and the vendor daemon did.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <stddef.h>

#define SOCKNAME "watchvitals"      /* abstract: android.net.LocalSocket, ABSTRACT namespace */
#define HELPER   "/data/local/tmp/ppgd"
#define SECS_HR   "40"   /* green is 25 Hz: it needs the time to fill enough windows */
#define SECS_SPO2 "35"   /* red is 100 Hz - 2500 samples in 25 s is plenty */

static int listenfd = -1;

static void bye(int s)
{
    (void)s;
    if (listenfd >= 0) close(listenfd);
    /* The vendor daemon stays off: this has replaced it. Nothing to restore. */
    _exit(0);
}

/* Run one measurement and return its single line. The vendor daemon is stopped for the duration
 * and started again straight after, including on failure. */
static void measure(const char *mode, char *out, size_t outsz)
{
    char cmd[256];
    FILE *p;

    out[0] = 0;
    /* Do NOT stop gh3011_daemon here. This process *is* that service now - it runs in the slot
     * init used to start the vendor's - so stopping it kills this daemon mid-measurement, which
     * is exactly what happened the first time. The vendor binary is disabled by virtue of being
     * replaced; there is nothing left to stop. */

    snprintf(cmd, sizeof cmd, "%s %s \"\" %s 2>/dev/null", HELPER,
             strcmp(mode, "spo2") == 0 ? SECS_SPO2 : SECS_HR,
             strcmp(mode, "spo2") == 0 ? "spo2" : "hr");
    p = popen(cmd, "r");
    if (p) {
        char line[512];
        while (fgets(line, sizeof line, p)) {
            /* The helper prints progress on some paths; the reading is the line with hr= on it. */
            if (strstr(line, "hr=")) {
                strncpy(out, line, outsz - 1);
                out[outsz - 1] = 0;
            }
        }
        pclose(p);
    }

    if (!out[0]) snprintf(out, outsz, "hr=0 reason=helper_gave_nothing\n");
}

int main(void)
{
    struct sockaddr_un addr;
    socklen_t alen;

    signal(SIGTERM, bye);
    signal(SIGINT, bye);
    signal(SIGPIPE, SIG_IGN);       /* a launcher that hangs up mid-reply must not kill this */

    listenfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (listenfd < 0) { perror("socket"); return 1; }

    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = 0;                                  /* abstract namespace */
    strncpy(addr.sun_path + 1, SOCKNAME, sizeof addr.sun_path - 2);
    alen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCKNAME));

    if (bind(listenfd, (struct sockaddr *)&addr, alen) < 0) { perror("bind"); return 1; }
    if (listen(listenfd, 4) < 0) { perror("listen"); return 1; }

    fprintf(stderr, "vitalsd: listening on abstract socket \"%s\"\n", SOCKNAME);

    for (;;) {
        char req[64], reply[512];
        int c = accept(listenfd, NULL, NULL);
        ssize_t n;
        if (c < 0) {
            if (errno == EINTR) continue;
            perror("accept");
            break;
        }
        n = read(c, req, sizeof req - 1);
        if (n <= 0) { close(c); continue; }
        req[n] = 0;
        while (n > 0 && (req[n-1] == '\n' || req[n-1] == '\r')) req[--n] = 0;

        measure(req, reply, sizeof reply);
        write(c, reply, strlen(reply));
        close(c);
    }

    close(listenfd);
    return 0;
}
