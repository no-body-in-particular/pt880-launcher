package org.watchlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The overnight accelerometer log: one file a night, one line every burst.
 *
 * <pre>
 * millis,meanX,meanY,meanZ,sdMag,enmo,range,samples
 * </pre>
 *
 * Axes are in g, already averaged over the burst. More is recorded than the
 * scorer reads -- ENMO and the magnitude range are activity metrics for
 * algorithms that want counts, kept so a different scoring method can be tried
 * against a night that has already been slept rather than needing another one.
 *
 * A night is named for the evening it started: anything logged before noon
 * belongs to the previous day's night, so a single file spans one sleep rather
 * than being cut in half at midnight.
 *
 * The file is plain text on the card, so it can be pulled off and scored
 * somewhere with more room to think:
 *
 * <pre>
 * adb pull /sdcard/sleep/
 * </pre>
 */
public class SleepLog {

    public static final String DIR = "/sdcard/sleep";

    private static final String PREF_ON = "sleepLogging";
    private static final String PREF_SENT = "sleepScoreSent";
    private static final String PREF_STATE = "sleepState";
    private static final String PREF_RUN = "sleepRun";
    private static final String PREF_ANGLE = "sleepLastAngle";
    private static final String PREF_DAY = "sleepDay";
    private static final String PREF_DAY_MIN = "sleepDayMinutes";
    private static final String PREF_FLAG_AT = "sleepFlagAt";

    /** Watching for sleep to start: a burst every few minutes, nothing kept. */
    public static final int WATCHING = 0;

    /** Asleep as far as we can tell: a burst every 30s, all of it kept. */
    public static final int LOGGING = 1;

    private static final SimpleDateFormat NIGHT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    /** One burst. */
    public static class Epoch {
        public long at;
        public double x, y, z;
        public double sd, enmo, range;
        public int samples;

        /** The arm's angle to the horizontal, which is what van Hees's method
         *  watches for change in. */
        public double zAngle() {
            double flat = Math.sqrt(x * x + y * y);
            if (flat == 0 && z == 0) return 0;
            return Math.atan2(z, flat) * 180.0 / Math.PI;
        }
    }

    private SleepLog() { }

    // ---------------------------------------------------------------- state

    /** Sleep tracking is on unless it has been deliberately turned off. There
     *  is nothing to remember to do at bedtime: the watcher decides. */
    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(PREF_ON, true);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(PREF_ON, on).commit();
    }

    /** The last night whose score was uploaded, so it is not sent twice. */
    public static String lastScored(Context c) {
        return prefs(c).getString(PREF_SENT, null);
    }

    public static void markScored(Context c, String night) {
        prefs(c).edit().putString(PREF_SENT, night).commit();
    }

    /** When the live sleeping flag was last pushed, so it can be resent on a
     *  timer without the service having to count bursts across restarts. */
    public static long flagSentAt(Context c) {
        return prefs(c).getLong(PREF_FLAG_AT, 0);
    }

    public static void setFlagSentAt(Context c, long at) {
        prefs(c).edit().putLong(PREF_FLAG_AT, at).commit();
    }

    public static int state(Context c) {
        return prefs(c).getInt(PREF_STATE, WATCHING);
    }

    public static void setState(Context c, int state) {
        prefs(c).edit().putInt(PREF_STATE, state).putInt(PREF_RUN, 0).commit();
    }

    /** How many consecutive bursts have agreed with each other -- still while
     *  watching, moving while logging. The only state the detector needs. */
    public static int run(Context c) {
        return prefs(c).getInt(PREF_RUN, 0);
    }

    public static void setRun(Context c, int n) {
        prefs(c).edit().putInt(PREF_RUN, n).commit();
    }

    /**
     * Add a finished session to today's running total and return the new one.
     *
     * A session counts towards the day it <em>ended</em>: you wake up on
     * Tuesday having slept through Monday night, and "how much have I slept
     * today" means that sleep plus any nap since. Attributing it to the night
     * it started would leave today's figure at zero until bedtime.
     */
    public static int addDayMinutes(Context c, long endedAt, int minutes) {
        String day = NIGHT.format(new Date(endedAt));
        SharedPreferences p = prefs(c);
        int total = day.equals(p.getString(PREF_DAY, "")) ? p.getInt(PREF_DAY_MIN, 0) : 0;
        total += minutes;
        p.edit().putString(PREF_DAY, day).putInt(PREF_DAY_MIN, total).commit();
        return total;
    }

    public static int dayMinutes(Context c) {
        SharedPreferences p = prefs(c);
        String today = NIGHT.format(new Date());
        return today.equals(p.getString(PREF_DAY, "")) ? p.getInt(PREF_DAY_MIN, 0) : 0;
    }

    public static float lastAngle(Context c) {
        return prefs(c).getFloat(PREF_ANGLE, Float.NaN);
    }

    public static void setLastAngle(Context c, double a) {
        prefs(c).edit().putFloat(PREF_ANGLE, (float) a).commit();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("watchlauncher", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- naming

    /** The night a moment belongs to. Before noon is still last night. */
    public static String nightOf(long millis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(millis);
        if (cal.get(Calendar.HOUR_OF_DAY) < 12) cal.add(Calendar.DAY_OF_MONTH, -1);
        return NIGHT.format(cal.getTime());
    }

    public static File fileFor(String night) {
        return new File(DIR, night + ".csv");
    }

    /** The most recent night with a file, or null. */
    public static String latestNight() {
        File dir = new File(DIR);
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        String best = null;
        for (int i = 0; i < kids.length; i++) {
            String n = kids[i].getName();
            if (!n.endsWith(".csv")) continue;
            String night = n.substring(0, n.length() - 4);
            if (best == null || night.compareTo(best) > 0) best = night;
        }
        return best;
    }

    // ---------------------------------------------------------------- writing

    public static synchronized void append(Context c, long at,
            double x, double y, double z, double sd, double enmo,
            double range, int samples) {
        appendTo(fileFor(nightOf(at)), at, x, y, z, sd, enmo, range, samples);
    }

    /**
     * The watcher's own bursts, kept separately.
     *
     * They must not go into the night's log: that is sampled every 30 seconds
     * and the scorer works out its epoch length from the median gap, so
     * five-minute watcher rows mixed in would make every duration it reports
     * wrong. They are still worth keeping -- the thresholds that decide when
     * sleep started are guesses until there is a night of real numbers to
     * check them against.
     */
    public static synchronized void appendWatch(Context c, long at,
            double x, double y, double z, double sd, double enmo,
            double range, int samples) {
        appendTo(new File(DIR, "watch-" + nightOf(at) + ".csv"),
                at, x, y, z, sd, enmo, range, samples);
    }

    private static synchronized void appendTo(File f, long at,
            double x, double y, double z, double sd, double enmo,
            double range, int samples) {
        FileWriter w = null;
        try {
            File dir = new File(DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            boolean fresh = !f.exists();
            w = new FileWriter(f, true);
            if (fresh) {
                w.write("# millis,meanX,meanY,meanZ,sdMag,enmo,range,samples"
                        + "  (g, burst means)\n");
            }
            w.write(at + ","
                    + fmt(x) + "," + fmt(y) + "," + fmt(z) + ","
                    + fmt(sd) + "," + fmt(enmo) + "," + fmt(range) + ","
                    + samples + "\n");
        } catch (Exception e) {
            // A night with a hole in it is still worth scoring; losing the
            // whole log because one write failed is not.
        } finally {
            try { if (w != null) w.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.5f", v);
    }

    // ---------------------------------------------------------------- reading

    public static List<Epoch> read(String night) {
        List<Epoch> out = new ArrayList<Epoch>();
        File f = fileFor(night);
        if (!f.isFile()) return out;

        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                String[] p = line.split(",");
                if (p.length < 8) continue;
                try {
                    Epoch e = new Epoch();
                    e.at = Long.parseLong(p[0].trim());
                    e.x = Double.parseDouble(p[1]);
                    e.y = Double.parseDouble(p[2]);
                    e.z = Double.parseDouble(p[3]);
                    e.sd = Double.parseDouble(p[4]);
                    e.enmo = Double.parseDouble(p[5]);
                    e.range = Double.parseDouble(p[6]);
                    e.samples = Integer.parseInt(p[7].trim());
                    out.add(e);
                } catch (Exception ex) {
                    /* one malformed line does not spoil the night */
                }
            }
        } catch (Exception e) {
            /* unreadable is an empty night */
        } finally {
            try { if (r != null) r.close(); } catch (Exception e) { /* ignore */ }
        }
        return out;
    }

    /** How many epochs have been recorded for tonight so far. */
    public static int countTonight() {
        return read(nightOf(System.currentTimeMillis())).size();
    }
}
