package org.watchlauncher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The last position and the last pulse, from the frames this client sent.
 *
 * <h3>What this used to be, and why it stopped working</h3>
 *
 * It read the vendor tracker's own database -
 * {@code /data/data/com.enqualcomm.support/databases/data} - copying it out
 * with the root shell because it is {@code system:system}, rolling back its
 * journal, and parsing the fixes out of the {@code PROTOCOL_CMD_RECORDS} rows,
 * because the vendor uploaded positions and kept no location table.
 *
 * That app is gone from this watch. No package, no apk in
 * {@code /system/priv-app}, no {@code /data/data} directory. Every call landed
 * on {@code problem = "copy failed"}, so the sports screen has been showing
 * that instead of a speed or a pulse, and {@code sleepNights()} returned an
 * empty list for ever - which is why the firmware sleep types 5, 6 and 7 have
 * not been sent by anything for some time.
 *
 * <h3>What it reads now</h3>
 *
 * This client is the tracker. It knows every frame it sent, so the record is
 * kept as it sends them: {@link #recordFix} from the position frame and
 * {@link #recordPulse} from the health frame, into the tracker's own
 * preferences so a restarted launcher still knows where it was.
 *
 * Two fixes are kept rather than one, because speed is measured between them.
 *
 * <h3>Speed</h3>
 *
 * Not the position frame's own speed field: the server ignores that and works
 * it out between consecutive positions, so this does the same, by the same
 * rules as {@code compute_speed()} and {@code move_to()} in CTracker.
 *
 * <ul>
 *   <li>haversine distance in km over the gap between the two fixes in hours;
 *   <li>a zero or negative gap is unmeasurable, not zero;
 *   <li>past {@link #MAX_PLAUSIBLE_SPEED} the reading is discarded rather than
 *       rewritten to zero -- zero claims the watch stood still, which is a
 *       claim, not the absence of one;
 *   <li>only fixes with coordinates pair. A frame with none has no position to
 *       pair with, which is the common case on this watch: with the gps
 *       provider off, every position frame goes up as V with zeroes and the
 *       server places it from the cell and the access points instead.
 * </ul>
 */
public class TrackerLog {

    /** Anything faster is a bad pair of fixes, not a journey. */
    private static final double MAX_PLAUSIBLE_SPEED = 700;

    /** Earth radius in km, as CTracker's haversineDistance uses it. */
    private static final double EARTH_KM = 6371;

    // The newest frame, whether or not it carried coordinates.
    private static final String K_LAST_AT = "log_frame_at";
    private static final String K_LAST_VALID = "log_frame_valid";

    // The newest two frames that did carry coordinates.
    private static final String K_FIX_AT = "log_fix_at";
    private static final String K_FIX_LAT = "log_fix_lat";
    private static final String K_FIX_LON = "log_fix_lon";
    private static final String K_PREV_AT = "log_prev_at";
    private static final String K_PREV_LAT = "log_prev_lat";
    private static final String K_PREV_LON = "log_prev_lon";

    private static final String K_BPM = "log_bpm";
    private static final String K_BPM_AT = "log_bpm_at";

    private final Context ctx;

    private float speed = -1;
    private boolean fixValid = false;
    private long fixAt = 0;
    private long speedSpanMs = 0;
    private int bpm = -1;
    private long bpmAt = 0;
    private String problem = null;

    public TrackerLog(Context c) {
        this.ctx = c.getApplicationContext();
    }

    /**
     * Kept for callers that still hand over a root shell. Nothing here needs
     * one now - the record is this app's own.
     */
    public TrackerLog(Context c, RootShell ignored) {
        this(c);
    }

    public float speed() { return speed; }

    public long speedSpanMs() { return speedSpanMs; }

    public boolean fixValid() { return fixValid; }

    public long fixAt() { return fixAt; }

    public int bpm() { return bpm; }

    public long bpmAt() { return bpmAt; }

    public String problem() { return problem; }

    public synchronized void refresh() {
        problem = null;
        speed = -1;
        speedSpanMs = 0;

        SharedPreferences p = TrackerService.prefs(ctx);

        fixAt = p.getLong(K_LAST_AT, 0);
        fixValid = p.getBoolean(K_LAST_VALID, false);
        bpm = p.getInt(K_BPM, -1);
        bpmAt = p.getLong(K_BPM_AT, 0);

        if (fixAt == 0 && bpm < 0) {
            // Not an error. A client that has only just started has nothing to
            // show yet, and saying so beats an empty screen that looks broken.
            problem = "nothing sent yet";
            return;
        }

        long newestAt = p.getLong(K_FIX_AT, 0);
        long prevAt = p.getLong(K_PREV_AT, 0);
        if (newestAt == 0 || prevAt == 0) return;      // nothing to measure against

        measure(newestAt, readDouble(p, K_FIX_LAT), readDouble(p, K_FIX_LON),
                prevAt, readDouble(p, K_PREV_LAT), readDouble(p, K_PREV_LON));
    }

    /** compute_speed() and the plausibility test from move_to(), together. */
    private void measure(long newestAt, double newLat, double newLon,
                         long prevAt, double prevLat, double prevLon) {
        long dtMs = Math.abs(newestAt - prevAt);
        if (dtMs <= 0) return;                   // cannot say how fast, not zero

        double km = haversineKm(prevLat, prevLon, newLat, newLon);
        double kmh = km / (dtMs / 3600000.0);
        if (kmh < 0 || kmh > MAX_PLAUSIBLE_SPEED) return;

        speed = (float) kmh;
        speedSpanMs = dtMs;
        // The measurement belongs to the newer of the two fixes, which is also
        // the one whose age the screen reports.
        fixAt = newestAt;
        fixValid = true;
    }

    /** CTracker's haversineDistance, in km. */
    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_KM * c;
    }

    // ---------------------------------------------------------------- writing

    /**
     * Record a position frame as it goes out.
     *
     * @param hasPosition the server's own test: coordinates, not the A/V
     *                    character. A frame of zeroes is a frame with no
     *                    position, however it is flagged.
     */
    public static void recordFix(Context c, boolean hasPosition,
                                 double lat, double lon, long at) {
        if (c == null || at <= 0) return;
        try {
            SharedPreferences p = TrackerService.prefs(c);
            SharedPreferences.Editor e = p.edit();
            e.putLong(K_LAST_AT, at);
            e.putBoolean(K_LAST_VALID, hasPosition);

            if (hasPosition) {
                // The one that was newest becomes the previous, so the pair the
                // speed is measured from is always the last two that had a
                // position - not the last two frames.
                long wasAt = p.getLong(K_FIX_AT, 0);
                if (wasAt > 0) {
                    e.putLong(K_PREV_AT, wasAt);
                    e.putLong(K_PREV_LAT, p.getLong(K_FIX_LAT, 0));
                    e.putLong(K_PREV_LON, p.getLong(K_FIX_LON, 0));
                }
                e.putLong(K_FIX_AT, at);
                writeDouble(e, K_FIX_LAT, lat);
                writeDouble(e, K_FIX_LON, lon);
            }
            e.commit();
        } catch (Throwable t) { /* the record is a convenience, never the point */ }
    }

    /** Record a heart rate as it goes out. */
    /**
     * The last pulse, if it is recent enough to describe the wrist now, or 0.
     *
     * The age matters as much as the value: the sleep detector uses this to tell a still wrist
     * that is asleep from one that is merely sitting down, and a reading from an hour ago
     * answers neither question.
     */
    public static int recentBpm(Context c, long freshMs) {
        if (c == null) return 0;
        try {
            SharedPreferences p = TrackerService.prefs(c);
            int v = p.getInt(K_BPM, -1);
            long at = p.getLong(K_BPM_AT, 0);
            if (v <= 0 || at <= 0) return 0;
            long age = System.currentTimeMillis() - at;
            return (age >= 0 && age <= freshMs) ? v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void recordPulse(Context c, int value, long at) {
        if (c == null || at <= 0) return;
        if (value < 25 || value > 250) return;    // the same range the parser accepted
        // Every accepted pulse teaches the resting estimate the sleep detector compares against.
        SleepLog.observeBpm(c, value);
        try {
            TrackerService.prefs(c).edit()
                    .putInt(K_BPM, value)
                    .putLong(K_BPM_AT, at)
                    .commit();
        } catch (Throwable t) { /* as above */ }
    }

    /* SharedPreferences has no double. The bits round-trip exactly, where a
     * float would quietly lose about a metre of the sixth decimal place. */

    private static void writeDouble(SharedPreferences.Editor e, String key, double v) {
        e.putLong(key, Double.doubleToRawLongBits(v));
    }

    private static double readDouble(SharedPreferences p, String key) {
        return Double.longBitsToDouble(p.getLong(key, 0));
    }
}
