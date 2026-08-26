package org.watchlauncher;

/**
 * How fast you are going, and when you will get there.
 *
 * Both numbers have to come from the drive itself rather than from the plan.
 * The route's cost model is an average over a road class - it says a
 * residential street is 22 km/h - and while that is the right thing to choose
 * a route with, it is not the right thing to put on the screen next to a
 * speedometer. What the watch shows has to agree with what the driver sees
 * out of the window.
 *
 * <h3>Speed</h3>
 *
 * The location may or may not carry one. This watch usually gets its position
 * from the tracker rather than from a provider that fills in getSpeed(), so
 * speed is derived from consecutive fixes whenever the fix does not have it:
 * the ground covered divided by the time it took.
 *
 * That number is noisy - a position ten metres out in either direction across
 * a ten second gap is two metres a second of error, seven km/h - so it is
 * smoothed. Not by averaging over a fixed window, which lags badly when you
 * pull off a motorway, but with an exponential filter whose weight depends on
 * how long the gap was: a long gap tells you more about the new speed than a
 * short one does, so it moves the estimate further.
 *
 * <h3>Time to arrival</h3>
 *
 * Dividing the distance left by the current speed is wrong in the one case
 * that matters most - stopped at a light, the current speed is zero and the
 * arrival time is infinite. So the estimate runs off a separate figure: the
 * average speed made good over the last few kilometres, stops included. That
 * is what actually predicts the rest of a drive, because the rest of the
 * drive will have stops in it too.
 *
 * Until there is enough of a drive to average, it falls back to the speed the
 * route was planned at, which is the best guess available before moving.
 *
 * No Android in here: it is arithmetic over fixes, and the host can test it.
 */
public class Drive {

    /** Below this, treat it as stopped rather than as a slow crawl. */
    private static final float STOPPED_MS = 0.6f;

    /** Fixes further apart than this are not one journey any more - the watch
     *  was asleep, or out of signal. Start again rather than average across
     *  the gap and report a crawl. */
    private static final long GAP_MS = 180000;

    /** Smoothing time constant. A gap this long moves the estimate 63% of the
     *  way to the new reading; shorter gaps move it proportionally less. */
    private static final float TAU_MS = 12000f;

    /** The shortest interval worth dividing by. A position is good to a few
     *  metres, so over one second that error alone is several metres a second
     *  and swamps the answer; over ten it is a rounding difference. */
    private static final long MIN_INTERVAL_MS = 4000;

    /** Faster than this is a bad fix rather than a drive: 216 km/h is beyond
     *  anything this watch is going to be strapped to. */
    private static final float MAX_MS = 60f;

    /** How much recent driving the arrival estimate averages over. Long
     *  enough to include the stops, short enough to notice leaving a
     *  motorway. */
    private static final long WINDOW_MS = 420000;          // seven minutes
    private static final double WINDOW_M = 6000;

    private double lastLat = Double.NaN, lastLon;
    private long lastAt = 0;

    /** Smoothed instantaneous speed, metres per second; -1 until known. */
    private float speed = -1;

    /** Rolling made-good figures: ground covered and time taken, both decayed
     *  so that old driving stops counting without keeping a list of fixes. */
    private double windowM = 0;
    private long windowMs = 0;

    /** Reset when the route changes: the previous drive's average is not
     *  evidence about this one. */
    public void restart() {
        lastLat = Double.NaN;
        lastAt = 0;
        speed = -1;
        windowM = 0;
        windowMs = 0;
    }

    /**
     * Take a fix.
     *
     * @param at        when, in milliseconds
     * @param providerMs the speed the fix carried, or a negative number if it
     *                   carried none
     */
    public void fix(long at, double lat, double lon, float providerMs) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return;

        // A fix that brought its own speed has already answered the question,
        // and none of the reasoning about intervals below applies to it.
        if (providerMs >= 0) {
            long dt = lastAt > 0 ? at - lastAt : 0;
            apply(providerMs, dt > 0 && dt <= GAP_MS ? dt : (long) TAU_MS);
            if (dt > 0 && dt <= GAP_MS && !Double.isNaN(lastLat)) {
                credit(metres(lastLat, lastLon, lat, lon), dt);
            }
            lastLat = lat; lastLon = lon; lastAt = at;
            return;
        }

        if (Double.isNaN(lastLat) || lastAt <= 0) {
            lastLat = lat; lastLon = lon; lastAt = at;
            return;
        }

        long dt = at - lastAt;

        // Time running backwards means the clock was set, not that the watch
        // drove backwards. Too long a gap means the watch was asleep or out of
        // signal, and the ground between is not driving that happened at an
        // average - start again.
        if (dt <= 0 || dt > GAP_MS) {
            lastLat = lat; lastLon = lon; lastAt = at;
            windowM = 0; windowMs = 0;
            return;
        }

        /*
         * Speed is measured over an interval, not between whichever two fixes
         * happen to arrive next to each other.
         *
         * Fixes do not turn up evenly spaced. A provider flushing a backlog
         * delivers two positions a tenth of a second apart, and the tracker
         * hands over the same reading twice in a row. Dividing by those gaps
         * gives 344 km/h and 0 km/h respectively, from a watch doing a steady
         * hundred - the error in the position is fixed at a few metres, so
         * the shorter the interval the more of the answer is noise.
         *
         * So the last fix is held as an anchor and nothing is computed until
         * enough time has passed to divide by. Fixes arriving in between are
         * not thrown away; they are simply not yet the end of a measurement.
         */
        if (dt < MIN_INTERVAL_MS) return;

        double moved = metres(lastLat, lastLon, lat, lon);
        lastLat = lat; lastLon = lon; lastAt = at;

        float measured = (float) (moved / (dt / 1000.0));
        if (Float.isNaN(measured) || Float.isInfinite(measured)) return;
        // Faster than anything this watch is riding in: a bad fix, not a drive.
        if (measured > MAX_MS) return;

        apply(measured, dt);
        credit(moved, dt);
    }

    /** Fold a reading into the smoothed speed, weighted by how long it took. */
    private void apply(float measured, long dt) {
        if (speed < 0) {
            speed = measured;
            return;
        }
        float a = 1f - (float) Math.exp(-dt / TAU_MS);
        speed += a * (measured - speed);
    }

    /**
     * Add to the made-good average - ground covered and time taken, stops and
     * all.
     *
     * Decayed rather than windowed: multiplying both totals by the same factor
     * keeps their ratio while letting the last few minutes dominate, which
     * costs two numbers instead of a list of every fix.
     */
    private void credit(double moved, long dt) {
        windowM += moved;
        windowMs += dt;
        if (windowMs > WINDOW_MS || windowM > WINDOW_M) {
            double keep = Math.min(WINDOW_MS / (double) windowMs,
                                   windowM > 0 ? WINDOW_M / windowM : 1.0);
            windowM *= keep;
            windowMs = (long) (windowMs * keep);
        }
    }

    /** Current speed in metres per second, or -1 if it is not known yet. */
    public float speedMs() { return speed; }

    /** Current speed in whole km/h, or -1. */
    public int kmh() {
        if (speed < 0) return -1;
        if (speed < STOPPED_MS) return 0;
        return (int) Math.round(speed * 3.6);
    }

    /** Average speed made good, metres per second, or -1 if too little
     *  driving has been seen to say. */
    public float madeGoodMs() {
        if (windowMs < 30000 || windowM < 100) return -1;
        return (float) (windowM / (windowMs / 1000.0));
    }

    /**
     * Seconds until arrival, or -1 if there is nothing to base it on.
     *
     * @param metresLeft distance still to drive
     * @param plannedMs  the speed the route was planned at, or a negative
     *                   number if that is not known either
     */
    public int etaSeconds(double metresLeft, float plannedMs) {
        if (metresLeft < 0 || Double.isNaN(metresLeft)) return -1;
        float use = madeGoodMs();
        if (use < 1f) use = plannedMs;
        if (use < 1f) return -1;
        double s = metresLeft / use;
        if (s > 86400) return -1;                  // a day out is not an ETA
        return (int) Math.round(s);
    }

    /** "3 min", "1 h 12", or null. Short: it shares a 240px line. */
    public static String shortTime(int seconds) {
        if (seconds < 0) return null;
        if (seconds < 60) return "<1 min";
        int m = (seconds + 30) / 60;
        if (m < 60) return m + " min";
        return (m / 60) + " h " + pad2(m % 60);
    }

    /** Clock time of arrival, given now in milliseconds and the local offset
     *  in milliseconds, as "14:32". */
    public static String arrivalClock(long nowMs, int offsetMs, int seconds) {
        if (seconds < 0) return null;
        long t = nowMs + offsetMs + seconds * 1000L;
        long day = ((t % 86400000L) + 86400000L) % 86400000L;
        return pad2((int) (day / 3600000L)) + ":" + pad2((int) (day / 60000L % 60));
    }

    private static String pad2(int v) { return v < 10 ? "0" + v : String.valueOf(v); }

    /** Equirectangular, which over the tens of metres between two fixes is
     *  indistinguishable from the great circle and far cheaper. Kept here
     *  rather than borrowed from Route so that this class pulls in nothing:
     *  Route reaches the road graph, the road graph reaches Android, and then
     *  none of this could be tested off the device. */
    static double metres(double la1, double lo1, double la2, double lo2) {
        double dy = (la2 - la1) * 110540;
        double dx = (lo2 - lo1) * 111320 * Math.cos(Math.toRadians((la1 + la2) / 2));
        return Math.sqrt(dx * dx + dy * dy);
    }
}
