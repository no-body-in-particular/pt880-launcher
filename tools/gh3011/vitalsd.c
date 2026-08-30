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
 *     ->  wear          the thermometer alone: no LEDs, no measurement
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
#include <fcntl.h>
#include <time.h>

#define SOCKNAME "watchvitals"      /* abstract: android.net.LocalSocket, ABSTRACT namespace */
#define HELPER   "/data/local/tmp/ppgd"
#define SECS_HR   "40"   /* green is 25 Hz: it needs the time to fill enough windows */
#define SECS_SPO2 "45"   /* red is 100 Hz - 2500 samples in 25 s is plenty */
/* The balanced pass. The vendor spends about eight seconds here and we did too, but eight is
 * not enough on this sensor: four runs at 8 s gave R of 1.048, 0.907, 0.751 and 0.782, and the
 * same wrist at 25 s gave 0.877, 0.841 and 0.741 - half the spread. The extra seventeen seconds
 * are the cheapest accuracy available, and the pass is still shorter than the one after it. */
#define SECS_RATIO "25"

/* Where the short pass leaves its samples for the long pass to explain. See measure(). */
#define KEEP "/data/local/tmp/pass1.txt"


#define TEMP_ENABLE "/sys/devices/virtual/input/input6/enable"
#define TEMP_VALUE  "/sys/devices/virtual/input/input6/value"

/* Hundredths of a degree. Skin holds the thermopile in the low thirties - 34.57 on the wrist it
 * was measured against - while on a table it falls to room temperature within minutes. Thirty
 * sits between the two with room on either side. */
#define WORN_C 3000

/* The whole of a small file. */
static int slurp(const char *path, char *buf, size_t n)
{
    ssize_t got;
    int f = open(path, O_RDONLY);
    if (f < 0) return -1;
    got = read(f, buf, n - 1);
    close(f);
    if (got <= 0) return -1;
    buf[got] = 0;
    return 0;
}

/* Wrist temperature in hundredths of a degree, or -1 if the sensor will not say.
 *
 * A gxts02s thermopile, reported through the "temperature" input device rather than a thermal
 * zone - the zones are the CPU, GPU, charger and board, none of which touch the wearer. It reads
 * "0 0" until the driver has produced a sample, which takes about six seconds from cold, so this
 * enables it and waits instead of believing the first look.
 *
 * Left enabled afterwards: disabling would save a little current, but the next measurement would
 * pay those six seconds again and the framework may be sharing the sensor.
 */
static int read_temp(int patience)
{
    char buf[64];
    int f, t, tries;

    f = open(TEMP_ENABLE, O_WRONLY);
    if (f >= 0) { write(f, "1\n", 2); close(f); }

    for (tries = 0; tries < patience; tries++) {
        if (slurp(TEMP_VALUE, buf, sizeof buf) == 0) {
            t = atoi(buf);
            if (t > 0) return t;
        }
        sleep(1);
    }
    return -1;
}


/* Everything measured, kept on the card.
 *
 * The reply carries far more than the launcher uses - the ratio, its window spread, the matched
 * amplitudes, the raw pulse shape before the gate - and all of it is thrown away the moment the
 * socket closes. Those are exactly the numbers needed to work out why a saturation will not hold
 * still, and they cannot be reconstructed afterwards from a heart rate.
 *
 * One line per measurement, appended, with the time in front of it. It is a few hundred bytes a
 * measurement and the card has gigabytes; it survives reboots, which logcat does not, and it can
 * be pulled whenever there is a question to ask of it.
 */
#define VLOG "/sdcard/vitals.log"

static void logline(const char *mode, const char *line)
{
    FILE *f = fopen(VLOG, "a");
    time_t now;
    struct tm *tmv;
    char when[32];

    if (!f) return;
    now = time(NULL);
    tmv = localtime(&now);
    if (tmv && strftime(when, sizeof when, "%Y-%m-%d %H:%M:%S", tmv) > 0) {
        fprintf(f, "%s %s %s", when, mode, line);
    } else {
        fprintf(f, "%ld %s %s", (long)now, mode, line);
    }
    if (line[0] && line[strlen(line)-1] != 0x0a) fputc(0x0a, f);
    fclose(f);
}


/* A running view of the ratio, across measurements rather than within one.
 *
 * Saturation does not move quickly. A healthy wearer at rest holds the same figure for hours, so
 * combining the last several measurements is not smoothing away a signal - there is nothing there
 * moving fast enough to smooth away. What it does remove is the part that changes between one
 * measurement and the next, which on this sensor is most of what R does.
 *
 * The median, not the mean: a pass where the band slipped produces a wild value rather than a
 * slightly wrong one, and the mean would carry it.
 *
 * Only passes that survived a quality check go in. A ratio measured on eight beats that the
 * matched filter and the bin estimate disagree about by a factor of nine is not a worse
 * measurement of saturation - it is not a measurement of saturation, and averaging more of them
 * together makes a confident wrong answer rather than an honest empty one.
 */
#define RING 9

static double ring[RING];
static int ring_n = 0, ring_at = 0;

static int cmp_dbl(const void *a, const void *b)
{
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : (x > y);
}

static void ring_add(double r)
{
    ring[ring_at] = r;
    ring_at = (ring_at + 1) % RING;
    if (ring_n < RING) ring_n++;
}

/* The middle of what has been seen, and how far the middle half of it spreads. */
static int ring_view(double *med, double *spread)
{
    double tmp[RING];
    int i;

    *med = 0;
    *spread = 0;
    if (ring_n < 3) return ring_n;
    for (i = 0; i < ring_n; i++) tmp[i] = ring[i];
    qsort(tmp, ring_n, sizeof tmp[0], cmp_dbl);
    *med = tmp[ring_n / 2];
    *spread = tmp[(ring_n * 3) / 4] - tmp[ring_n / 4];
    return ring_n;
}

/* Read name=<number> out of a reply line, or -1. */
static double field_of(const char *line, const char *name)
{
    const char *at = strstr(line, name);
    if (!at) return -1.0;
    return atof(at + strlen(name));
}

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
    char ratio_out[192];
    size_t ratio_sz = sizeof ratio_out;
    FILE *p;

    ratio_out[0] = 0;

    int t;

    out[0] = 0;

    /* Do not light the sensor for forty-five seconds against a bedside table.
     *
     * Off the wrist a measurement cannot succeed - it ends in no_agreement once the windows have
     * failed to cluster - but it takes the whole run to get there with the LEDs on throughout.
     * The thermometer answers the same question in about a second.
     *
     * If the sensor will not say, measure anyway: a missing thermometer is a reason to fall back
     * to the slow answer, not to refuse to answer at all. */
    t = read_temp(8);
    if (t >= 0 && t < WORN_C) {
        snprintf(out, outsz, "hr=0 reason=not_worn temp=%d.%02d\n", t / 100, t % 100);
        return;
    }
    /* Do NOT stop gh3011_daemon here. This process *is* that service now - it runs in the slot
     * init used to start the vendor's - so stopping it kills this daemon mid-measurement, which
     * is exactly what happened the first time. The vendor binary is disabled by virtue of being
     * replaced; there is nothing left to stop. */

    /* Two passes, the way the vendor firmware does it: a short one for the saturation and a
     * long one for the rate and the pressure.
     *
     * They want opposite configurations, which is why one pass cannot serve both. The ratio
     * needs channel 1 carrying signal, and that means zeroing 0x0180 to lift it from two counts
     * of pulse to thirty - but the same change drops channel 2 from 190-260 counts to 34-95, and
     * channel 2 is where the pulse shape behind the pressure comes from. Six measurements in the
     * balanced state found no usable beats at all.
     *
     * So the short pass runs balanced and reports only R, and the long pass runs as before. The
     * ratio costs eight seconds on top of the forty, which is what the vendor spends too.
     */
    if (strcmp(mode, "spo2") == 0) {
        char rline[256];
        rline[0] = 0;
        snprintf(cmd, sizeof cmd, "%s %s %s ratio 2>/dev/null", HELPER, SECS_RATIO, KEEP);
        p = popen(cmd, "r");
        if (p) {
            char line[512];
            while (fgets(line, sizeof line, p)) {
                if (strstr(line, "r=")) {
                    strncpy(rline, line, sizeof rline - 1);
                    rline[sizeof rline - 1] = 0;
                }
            }
            pclose(p);
        }
        /* Carried on the reply so the ratio can be watched while it is being made to behave.
         * No saturation is derived from it: across four consecutive resting passes it came back
         * 1.40, 0.84, 0.84 and 1.13, and the frequency it was measured at wandered between 41
         * and 59 bpm, which is eight seconds being too short to lock a rate rather than anything
         * about the wearer. See docs/vitals.md. */
        if (rline[0]) {
            size_t at = strlen(rline);
            while (at > 0 && (rline[at-1] == 0x0a || rline[at-1] == 0x0d)) rline[--at] = 0;
            snprintf(ratio_out, ratio_sz, " pass1[%s]", rline);
        }
    }

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

    /* Now that the rate is known, read the short pass again at it.
     *
     * The eight seconds could not settle a rate of their own - four consecutive runs on a
     * resting wrist put it at 41, 45, 49 and 59 bpm - and a ratio measured at the wrong
     * frequency is a ratio measured on noise. The long pass settles it properly by window
     * agreement, so the samples kept from the short one are read back at that. No extra sensor
     * time: the same eight seconds, understood once there is something to understand them with.
     */
    {
        const char *at = strstr(out, "hr=");
        int bpm = at ? atoi(at + 3) : 0;
        if (bpm >= 30 && bpm <= 210) {
            char rcmd[320], line[512];
            snprintf(rcmd, sizeof rcmd, "%s 0 %s redo %d 2>/dev/null", HELPER, KEEP, bpm);
            p = popen(rcmd, "r");
            if (p) {
                while (fgets(line, sizeof line, p)) {
                    if (strstr(line, "redone=1")) {
                        size_t n2 = strlen(line);
                        while (n2 > 0 && (line[n2-1] == 0x0a || line[n2-1] == 0x0d)) line[--n2] = 0;
                        snprintf(ratio_out, ratio_sz, " pass1[%s]", line);
                    }
                }
                pclose(p);
            }
        }
    }

    /* Judge the pass, then fold it into the running view.
     *
     * Two independent estimates of the same amplitude are available - the matched filter, which
     * projects onto the beat, and the bin, which keeps only the fundamental. When they agree the
     * pass held together; when they disagree by a factor, one of them is measuring noise and
     * neither is worth keeping. That is a check no single estimator can perform on itself.
     */
    if (ratio_out[0]) {
        double rm = field_of(ratio_out, "rmatch=");
        double rb = field_of(ratio_out, " r=");
        int beats = (int) field_of(ratio_out, "mbeats=");
        double med = 0, sp = 0;
        int n3;

        /* Two gates, and between them they separated ten logged passes exactly.
         *
         * The window spread is how far the sub-windows of one pass disagreed. Above about a
         * third the pass did not hold still, and the two passes that failed it were carrying
         * two and six counts of pulse on channel 1 - not a worse ratio, no ratio at all.
         *
         * Agreement between the two estimators is the other. The matched filter and the bin
         * measure the same amplitude by different routes, so when they part company by more
         * than half again, one of them is measuring noise and there is no way to tell which
         * from inside either. That is a check neither can perform on itself.
         *
         * On the ten passes those were fitted against, the six that survived had a median of
         * 0.667 and lay between 0.583 and 0.729; the four rejected were 0.190, 1.531, 2.044 and
         * 2.739. Ten is not many, and the thresholds are round numbers rather than fitted ones
         * for that reason.
         */
        double rsp = field_of(ratio_out, "spread=");
        if (rm > 0.05 && rm < 5.0 && beats >= 8 && rb > 0 && rsp >= 0 && rsp < 0.35) {
            double hi = rm > rb ? rm : rb, lo = rm > rb ? rb : rm;
            if (lo > 0 && hi / lo < 1.6) ring_add(rm);
        }
        n3 = ring_view(&med, &sp);
        if (n3 >= 3) {
            size_t at2 = strlen(ratio_out);
            snprintf(ratio_out + at2, ratio_sz - at2, " rstable=%.3f rspread=%.3f rn=%d",
                     med, sp, n3);
        }
    }

    /* The short pass rides along on the same line. */
    if (ratio_out[0]) {
        size_t at = strlen(out);
        while (at > 0 && (out[at-1] == 0x0a || out[at-1] == 0x0d)) out[--at] = 0;
        snprintf(out + at, outsz - at, "%s\n", ratio_out);
    }

    /* Carry the temperature on the same line. It is a wrist and not a body - a few degrees above
     * the room and well below its owner, which is how the vendor once filed 21 C as a body
     * temperature - so converting it is the launcher's business, not this daemon's. */
    if (t > 0) {
        size_t at = strlen(out);
        while (at > 0 && (out[at-1] == 0x0a || out[at-1] == 0x0d)) out[--at] = 0;
        snprintf(out + at, outsz - at, " temp=%d.%02d\n", t / 100, t % 100);
    }
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

        if (strcmp(req, "wear") == 0) {
            /* Answerable without lighting an LED, so the launcher can skip a measurement it
             * already knows will fail. */
            int wt = read_temp(8);
            if (wt > 0)
                snprintf(reply, sizeof reply, "worn=%d temp=%d.%02d\n",
                         wt >= WORN_C ? 1 : 0, wt / 100, wt % 100);
            else
                snprintf(reply, sizeof reply, "worn=-1 reason=no_thermometer\n");
        } else {
            measure(req, reply, sizeof reply);
            logline(req, reply);
        }
        write(c, reply, strlen(reply));
        close(c);
    }

    close(listenfd);
    return 0;
}
