package org.watchlauncher;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * The last position and the last pulse, read out of the tracker firmware's own
 * records.
 *
 * <h3>Why this exists</h3>
 *
 * Android's location stack is empty on this watch: no last known location, and
 * {@code gps} is not even in {@code Enabled Providers}. Position is the
 * tracker's business and it runs its own {@code gpsd} on a ten-minute cycle
 * without feeding the framework. Its database has no location table either --
 * it reads a fix, uploads it, and keeps nothing.
 *
 * What it does keep is {@code PROTOCOL_CMD_RECORDS}, a log of the protocol
 * frames it sent. The fixes are in there, inside the frames.
 *
 * <h3>The frames</h3>
 *
 * A location report, {@code AP01}, is fixed-width after the opcode. The field
 * widths are not guessed: they are the ones CTracker's
 * {@code thinkrace_protocol.c} hands to sscanf, which is what has been reading
 * these frames on the server all along.
 *
 * <pre>
 * IWAP01 26 08 25 V 00 0000.0000 N 000 00.0000 E 000.0 17 29 58 000.00 045 000 099
 *        yy mm dd |  deg  minutes    deg minutes   speed hh mm ss course sig sat batt
 *                 `- A = valid, V = invalid, and the server ignores it: a fix
 *                    counts as GPS when the coordinates are not both zero.
 * </pre>
 *
 * Every frame on this unit so far carries zero coordinates, because it has
 * been indoors -- positioning falls back to the WiFi MACs and cell ids that
 * follow, which the <em>server</em> resolves. So there is nothing to measure
 * against and no speed to report, which is a different statement from standing
 * still and is shown as such.
 *
 * <h3>Speed</h3>
 *
 * Not the frame's own speed field: the server ignores that and computes speed
 * between consecutive positions, so this does the same, by the same rules as
 * {@code compute_speed()} and {@code move_to()} in CTracker.
 *
 * <ul>
 *   <li>haversine distance in km over the gap between the two fixes in hours;
 *   <li>a zero or negative gap is unmeasurable, not zero;
 *   <li>past {@link #MAX_PLAUSIBLE_SPEED} the reading is discarded rather than
 *       rewritten to zero -- zero claims the watch stood still, which is a
 *       claim, not the absence of one;
 *   <li>only GPS fixes pair. The server refuses to measure against a cell
 *       tower fix, because those hop kilometres as the serving tower changes,
 *       and that is where its several-hundred km/h rows came from. Here it
 *       falls out for free: a frame with no coordinates has no position to
 *       pair with.
 * </ul>
 *
 * Health is {@code APTP}, one reading per frame:
 *
 * <pre>
 * IWAPJK,2026-08-25 17:33:03,2,57#     type 2 = heart rate, 57 bpm
 *                            1,80|121  type 1 = blood pressure, diastolic|systolic
 *                            3,36.97   type 3 = temperature, C
 *                            4,97      type 4 = SpO2, %
 * </pre>
 *
 * <h3>Getting at it</h3>
 *
 * The database is {@code system:system} and mode 660, so an ordinary app
 * cannot open it. It is copied into this app's own directory by the root shell
 * -- the same one the terminal uses -- and read from there. The journal is
 * copied alongside so SQLite can roll it back and we see what the tracker
 * sees rather than a torn page.
 */
public class TrackerLog {

    private static final String SRC =
            "/data/data/com.enqualcomm.support/databases/data";

    /** Health reading types, from the APTP frames. */
    private static final int TYPE_BP = 1;
    private static final int TYPE_HR = 2;
    private static final int TYPE_TEMP = 3;
    private static final int TYPE_SPO2 = 4;

    private static final SimpleDateFormat FRAME_TIME =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat ROW_TIME =
            new SimpleDateFormat("yyMMdd HHmmss", Locale.US);

    private final Context ctx;
    private final RootShell root;

    /** From CTracker's config.h. Past this the pair is noise, not a journey. */
    private static final double MAX_PLAUSIBLE_SPEED = 700;

    /** Earth radius in km, as CTracker's haversineDistance uses it. */
    private static final double EARTH_KM = 6371;

    /** How far back to look for a second GPS fix to pair with. At the stock
     *  ten-minute location cycle, this is most of a day. */
    private static final int FRAME_LIMIT = 120;

    /** km/h between the last two GPS positions, or -1 when unmeasurable. */
    private float speed = -1;

    /** Whether the newest frame carried a position at all. */
    private boolean fixValid = false;
    private long fixAt = 0;

    /** The gap between the two fixes the speed was measured over. */
    private long speedSpanMs = 0;

    private int bpm = -1;
    private long bpmAt = 0;

    private String problem = null;

    public TrackerLog(Context c, RootShell root) {
        this.ctx = c.getApplicationContext();
        this.root = root;
    }

    public float speed() { return speed; }
    public long speedSpanMs() { return speedSpanMs; }
    public boolean fixValid() { return fixValid; }
    public long fixAt() { return fixAt; }
    public int bpm() { return bpm; }
    public long bpmAt() { return bpmAt; }
    public String problem() { return problem; }

    /** Blocking: copies ~115 KB through a root shell and runs two queries.
     *  Call it off the UI thread. */
    public synchronized void refresh() {
        problem = null;
        File db = copy();
        if (db == null) return;

        SQLiteDatabase h = null;
        try {
            // Read-write, not read-only: a hot journal has to be rolled back
            // before the newest rows are visible, and that is a write.
            h = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            readFix(h);
            readHeart(h);
        } catch (Exception e) {
            problem = "cannot read log";
        } finally {
            try { if (h != null) h.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    /** Root-copy the database and its journal somewhere we are allowed to
     *  open. Mode 666 is safe here: the containing directory is this app's
     *  private one, which nothing else can traverse. */
    private File copy() {
        if (root == null || !root.isRoot()) {
            problem = "needs the root helper";
            return null;
        }
        File dir = ctx.getFilesDir();
        File out = new File(dir, "tracker.db");
        String p = out.getAbsolutePath();
        String r = root.exec(
                "cat " + SRC + " > " + p + " 2>/dev/null; "
              + "cat " + SRC + "-journal > " + p + "-journal 2>/dev/null; "
              + "chmod 666 " + p + " " + p + "-journal 2>/dev/null; "
              + "echo copied");
        if (r == null || r.indexOf("copied") < 0 || !out.isFile() || out.length() < 1024) {
            problem = "copy failed";
            return null;
        }
        return out;
    }

    /**
     * The nights the firmware has recorded but not yet sent anywhere, read out
     * of the same copy the fixes come from.
     *
     * @param lastSent the newest date already uploaded, or null for all of it
     */
    public synchronized java.util.List<SleepUpload.Night> sleepNights(String lastSent) {
        java.util.List<SleepUpload.Night> none =
                new java.util.ArrayList<SleepUpload.Night>();
        File db = copy();
        if (db == null) return none;

        SQLiteDatabase h = null;
        try {
            h = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            return SleepUpload.unsent(h, lastSent);
        } catch (Exception e) {
            problem = "cannot read sleep";
            return none;
        } finally {
            try { if (h != null) h.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    // ---------------------------------------------------------------- fixes

    /** One parsed location frame. */
    private static class Fix {
        long at;
        double lat, lon;
        boolean hasPosition;
    }

    /**
     * Walk back through the location frames for the two most recent that
     * carry a position, and measure between them.
     */
    private void readFix(SQLiteDatabase h) {
        Cursor c = null;
        try {
            c = h.rawQuery(
                    "SELECT DATE, TIME, CMD FROM PROTOCOL_CMD_RECORDS"
                  + " WHERE CMD LIKE 'IWAP01%' OR CMD LIKE 'IWAP10%'"
                  + " ORDER BY _id DESC LIMIT " + FRAME_LIMIT, null);
            if (c == null || !c.moveToFirst()) return;

            Fix newest = null, previous = null;
            boolean first = true;
            do {
                Fix f = parseFix(c.getString(0), c.getString(1), c.getString(2));
                if (f == null) continue;
                if (first) {
                    // The newest frame decides what the screen says about the
                    // fix, whether or not it has coordinates in it.
                    fixValid = f.hasPosition;
                    fixAt = f.at;
                    first = false;
                }
                if (!f.hasPosition) continue;
                if (newest == null) { newest = f; continue; }
                previous = f;
                break;
            } while (c.moveToNext());

            measure(newest, previous);
        } catch (Exception e) {
            problem = "no location frames";
        } finally {
            try { if (c != null) c.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    /** compute_speed() and the plausibility test from move_to(), together. */
    private void measure(Fix newest, Fix previous) {
        speed = -1;
        speedSpanMs = 0;
        if (newest == null || previous == null) return;

        long dtMs = Math.abs(newest.at - previous.at);
        if (dtMs <= 0) return;                   // cannot say how fast, not zero

        double km = haversineKm(previous.lat, previous.lon, newest.lat, newest.lon);
        double kmh = km / (dtMs / 3600000.0);
        if (kmh < 0 || kmh > MAX_PLAUSIBLE_SPEED) return;

        speed = (float) kmh;
        speedSpanMs = dtMs;
        // The measurement belongs to the newer of the two fixes, which is also
        // the one whose age the screen reports.
        fixAt = newest.at;
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

    /**
     * One frame to a {@link Fix}, at the offsets the server's sscanf uses.
     * Coordinates are degrees and decimal minutes, hemisphere in the character
     * after each; a frame that is short or misshapen returns null rather than
     * a guess.
     */
    static Fix parseFix(String date, String time, String cmd) {
        if (cmd == null || cmd.length() < 6 + 59) return null;
        String b = cmd.substring(6);
        try {
            double latDeg = Integer.parseInt(b.substring(7, 9));
            double latMin = Double.parseDouble(b.substring(9, 16));
            char north = b.charAt(16);
            double lonDeg = Integer.parseInt(b.substring(17, 20));
            double lonMin = Double.parseDouble(b.substring(20, 27));
            char east = b.charAt(27);

            Fix f = new Fix();
            f.lat = latDeg + latMin / 60.0;
            f.lon = lonDeg + lonMin / 60.0;
            if (north == 'S') f.lat = -f.lat;
            if (east == 'W') f.lon = -f.lon;
            // The server's own test: coordinates, not the A/V character.
            f.hasPosition = (f.lat != 0 || f.lon != 0);
            f.at = when(date, time);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- health

    private void readHeart(SQLiteDatabase h) {
        Cursor c = null;
        try {
            // The frames are one reading each, so the newest heart-rate frame
            // is not necessarily the newest frame.
            c = h.rawQuery(
                    "SELECT CMD FROM PROTOCOL_CMD_RECORDS"
                  + " WHERE CMD LIKE 'IWAPJK,%' ORDER BY _id DESC LIMIT 40", null);
            if (c == null) return;
            while (c.moveToNext()) {
                if (parseHealth(c.getString(0))) return;
            }
        } catch (Exception e) {
            /* no health frames; the live sensor is still there */
        } finally {
            try { if (c != null) c.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    /** @return true once a heart rate has been taken from this frame. */
    boolean parseHealth(String cmd) {
        if (cmd == null) return false;
        String[] f = cmd.split(",");
        if (f.length < 4) return false;
        try {
            if (Integer.parseInt(f[2].trim()) != TYPE_HR) return false;
            String v = f[3].trim();
            int hash = v.indexOf('#');
            if (hash >= 0) v = v.substring(0, hash);
            int value = Math.round(Float.parseFloat(v));
            if (value < 25 || value > 250) return false;
            bpm = value;
            bpmAt = FRAME_TIME.parse(f[1].trim()).getTime();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The row's own DATE and TIME columns -- yymmdd and hhmmss -- which agree
     *  with the timestamp inside the frame and are cheaper to read. */
    private static long when(String date, String time) {
        try {
            return ROW_TIME.parse(date.trim() + " " + time.trim()).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
