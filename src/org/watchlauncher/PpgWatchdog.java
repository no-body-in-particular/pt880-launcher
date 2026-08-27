package org.watchlauncher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

/**
 * Notice when the watch has stopped taking its own pulse readings, and only then ask for one.
 *
 * <h3>The rule</h3>
 *
 * {@link Ppg} goes around a check the firmware makes on purpose, so it is a fallback and
 * never a second source of readings. It fires only when the watch has genuinely gone quiet:
 * nothing for {@link #SILENT_LIMIT_MS}, when the firmware manages one every few minutes when
 * it is working. If readings are arriving, this does nothing at all.
 *
 * <h3>Knowing whether it is still reporting</h3>
 *
 * Not by asking - by listening. Every reading the vendor stack completes is broadcast, twice:
 *
 * <pre>
 *     com.ic.action.BLOOD_HEART                     BLOOD_HEART, BLOOD_SPO2
 *     com.enqualcomm.support.ACTION_BROADCAST_PPG   ppg, bph, bpl, spo2, time
 * </pre>
 *
 * Either one means the pulse pipeline is alive, so the timestamp is all that is kept. That
 * covers readings taken on the firmware's own schedule and readings the server asked for,
 * without this having to know which is which or to poll anything.
 *
 * <h3>Why the timestamp is written down</h3>
 *
 * In a preference, not a field. The launcher gets restarted - by the system, by an install -
 * far more often than a night is long, and an in-memory timestamp resets to "just heard one"
 * every time, which is the answer that stops the watchdog ever firing. It is seeded on first
 * run with the current time so a fresh install waits a full window before deciding anything
 * is wrong.
 *
 * <h3>Not too often</h3>
 *
 * Forcing a measurement lights an LED against the skin for several seconds, and if the flags
 * really are latched it will keep being needed. {@link #FORCE_COOLDOWN_MS} puts a floor
 * between attempts, so a watch that cannot get a pulse at all - off the wrist, on a table -
 * spends about a minute an hour trying rather than running the sensor continuously.
 */
public class PpgWatchdog {

    private static final String TAG = "PpgWatchdog";

    private static final String PREFS = "ppg_watchdog";
    private static final String KEY_LAST_READING = "last_reading";
    private static final String KEY_LAST_FORCED = "last_forced";

    /**
     * How long a silence has to run before this steps in.
     *
     * Ten minutes, because the watch's own cycle is three. That is measured rather than
     * assumed - the gaps between its readings run 137, 43, 137, 43 seconds, two readings per
     * 180 second cycle - so ten minutes is already three missed cycles and not something a
     * working sensor does. An earlier draft used thirty five, set before the cadence was
     * known, which left a wedged sensor unnoticed for half an hour.
     */
    private static final long SILENT_LIMIT_MS = 10 * 60 * 1000L;

    /**
     * Floor between forced attempts.
     *
     * Matched to the limit above: once the flags are latched nothing clears them, so this is
     * the rate readings arrive at until something does. It is well short of the three minute
     * cycle it stands in for, deliberately - an optical sensor costs battery every time it
     * lights up, and a fallback that ran as often as the real thing would be the real thing.
     */
    private static final long FORCE_COOLDOWN_MS = 10 * 60 * 1000L;

    /**
     * How often to look. Cheap - a clock comparison unless something is wrong.
     *
     * Half the silence limit, so a silence is noticed within about five minutes of crossing
     * it rather than up to a full interval later.
     */
    private static final long CHECK_INTERVAL_MS = 5 * 60 * 1000L;

    private static final int ALARM_ID = 0x9917;
    static final String ACTION_CHECK = "org.watchlauncher.PPG_CHECK";

    private static final String ACTION_BLOOD_HEART = "com.ic.action.BLOOD_HEART";
    private static final String ACTION_PPG_RESULT = "com.enqualcomm.support.ACTION_BROADCAST_PPG";

    private PpgWatchdog() {
    }

    // ------------------------------------------------------------------ listening

    /**
     * Watch the vendor's result broadcasts. Registered for the life of the process; the
     * timestamp it keeps is what survives, not the receiver.
     */
    public static void listen(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_BLOOD_HEART);
        filter.addAction(ACTION_PPG_RESULT);

        try {
            context.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    noteReading(c);
                }
            }, filter);

        } catch (Exception e) {
            //a receiver we cannot register only costs us the watchdog, not the launcher
            Log.w(TAG, "could not listen for pulse broadcasts", e);
        }
    }

    /** Remember that the pipeline is alive. */
    static void noteReading(Context context) {
        prefs(context).edit().putLong(KEY_LAST_READING, System.currentTimeMillis()).apply();
    }

    // ------------------------------------------------------------------ scheduling

    public static void start(Context context) {
        seed(context);
        listen(context);
        schedule(context, CHECK_INTERVAL_MS);
    }

    static void schedule(Context context, long delayMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        //setExact, for the same reason SleepService uses it: KitKat made repeating alarms
        //inexact, and this one wants to be predictable rather than clustered
        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pending(context));
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(context));
    }

    private static PendingIntent pending(Context context) {
        Intent i = new Intent(context, SleepAlarmReceiver.class);
        i.setAction(ACTION_CHECK);
        return PendingIntent.getBroadcast(context, ALARM_ID, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** First run: assume it is working, so a fresh install waits a full window. */
    private static void seed(Context context) {
        SharedPreferences p = prefs(context);

        if (!p.contains(KEY_LAST_READING)) {
            p.edit().putLong(KEY_LAST_READING, System.currentTimeMillis()).apply();
        }
    }

    // ------------------------------------------------------------------ the check

    /**
     * Called from the alarm. Re-arms itself whatever it decides, so the watchdog survives a
     * night of failures rather than stopping at the first one.
     */
    public static void check(final Context context) {
        schedule(context, CHECK_INTERVAL_MS);

        if (!shouldForce(context)) {
            return;
        }

        prefs(context).edit().putLong(KEY_LAST_FORCED, System.currentTimeMillis()).apply();

        long silentMin = silentFor(context) / 60000L;
        Log.i(TAG, "no pulse reading for " + silentMin + " min - asking the sensor directly");

        new Ppg(context, new Ppg.Listener() {
            @Override
            public void onReading(int bpm, int systolic, int diastolic, int oxygen) {
                //the vendor's own callback uploads this to the tracker; the broadcast it
                //sends on the way will update the timestamp too, but not if it took the
                //reading without broadcasting, so record it here as well
                noteReading(context);
                Log.i(TAG, "forced reading: " + bpm + " bpm, " + systolic + "/" + diastolic
                        + ", spo2 " + oxygen);
            }

            @Override
            public void onFailed(String why) {
                //nothing to do but wait for the cooldown and try again. A watch that is
                //genuinely off the wrist lands here every time, which is correct.
                Log.i(TAG, "forced reading did not happen: " + why);
            }
        }).request(Ppg.TEST_ALL);
    }

    /** Quiet for long enough, and not tried too recently. */
    static boolean shouldForce(Context context) {
        return silentFor(context) > SILENT_LIMIT_MS
                && sinceForced(context) > FORCE_COOLDOWN_MS;
    }

    private static long silentFor(Context context) {
        return age(prefs(context).getLong(KEY_LAST_READING, 0));
    }

    private static long sinceForced(Context context) {
        return age(prefs(context).getLong(KEY_LAST_FORCED, 0));
    }

    /**
     * How long ago, defended against the clock moving.
     *
     * The watch gets its time from the network and from the tracker server, and a correction
     * that steps the clock backwards would otherwise leave a timestamp in the future and this
     * reading "negative seconds old" - which compares as recent and quietly disables the
     * watchdog until real time catches up. A future timestamp is treated as unknown, which
     * makes it forceable rather than stuck.
     */
    private static long age(long stamp) {
        if (stamp <= 0) {
            return Long.MAX_VALUE;
        }

        long age = System.currentTimeMillis() - stamp;
        return age < 0 ? Long.MAX_VALUE : age;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
