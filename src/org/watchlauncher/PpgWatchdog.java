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

    /** An optical reading needs seconds of clean signal; past this it is not coming. */
    private static final long READ_TIMEOUT_MS = 40000;

    /** The vendor's own pulse type on the JK frame. */
    private static final int TYPE_PULSE = 2;

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

    /**
     * When the firmware last completed a measurement, or 0 if it never has here.
     *
     * Exposed so other things can stay out of its way. The vendor broadcasts a result when it
     * finishes, and it works to a fixed cycle, so the last one plus the cycle is a good
     * estimate of when the next is due.
     */
    public static long lastVendorReadingAt(Context context) {
        try {
            return prefs(context).getLong(KEY_LAST_READING, 0);

        } catch (Throwable t) {
            return 0;
        }
    }

    /** The firmware's measurement period, as the server sets it with IWBPSQ. */
    public static final long VENDOR_CYCLE_MS = 3 * 60 * 1000L;

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

        //Everything below this line blocks, and none of it may run where it is called from.
        //
        //check() is called straight out of a BroadcastReceiver, which runs on the main thread
        //and has about ten seconds before the system decides it has hung. What follows opens
        //a root shell (twenty second timeout), opens a socket (eight to connect, six to
        //read), shells out four more times for the report, and can wait forty seconds for an
        //optical reading. Any one of those overruns it; together they are nowhere close.
        //
        //An overrun there does not just skip a check. It is an unresponsive main thread in
        //the launcher process, which is a frozen or blank display on a watch whose home
        //screen this is.
        new Thread(new Runnable() {
            public void run() {
                work(context);
            }
        }).start();
    }

    private static void work(final Context context) {
        //Every pass, whether or not anything is wrong. The score is lost when the tracker is
        //killed and restarted, which is the case it exists for, so re-applying it is the
        //whole mechanism - and this alarm is already running on a five minute tick, so it
        //costs one shell and no new wakeups.
        Guard.protectTracker(context);

        if (!shouldForce(context)) {
            return;
        }

        prefs(context).edit().putLong(KEY_LAST_FORCED, System.currentTimeMillis()).apply();

        final long silentMin = silentFor(context) / 60000L;
        Log.i(TAG, "no pulse reading for " + silentMin + " min");


        //Then the recovery. com.ic.work runs one work queue for both sensors: requests
        //become items, a single worker takes them one at a time, and each item carries a
        //creation timestamp that nothing ever reads. There is no timeout. A measurement
        //whose sensor callback never arrives holds the queue forever, and because heart rate
        //and temperature share it, both stop together and stay stopped.
        //
        //Nothing reachable from outside clears that. Binding the service and asking for a
        //reading - which is what this used to do - only puts another item behind the stuck
        //one. stopCurrentWork would be the escape hatch and this build does not implement
        //it: the binder answers three transactions and that is not one of them.
        //
        //So restart the process that holds the queue. Android brings it straight back, and
        //CoreService's next alarm finds a working service. That is seconds and a sensor
        //nobody was reading anyway, against the alternative the server falls back on, which
        //is rebooting the whole watch - a dark screen for minutes, a lost GPS fix, and about
        //thirty three minutes of missing readings each time.
        boolean restarted = restartSensorService(context);

        if (restarted) {
            Log.i(TAG, "restarted com.ic.work");
        }

        //Then fill the gap the stall left, using the path that does not go through the queue
        //at all.
        //
        //HeartRate reads gh30x_sensor through the platform's SensorManager, which is a
        //different route to the same hardware from the one com.ic.work queues work onto. It
        //predates all of this and it is the reason the Sports screen can still show a pulse
        //on a watch whose vitals stopped reaching the server half an hour ago. Asking the
        //vendor's service instead - which is what this used to do - only put another item
        //behind the one that is stuck.
        //
        //Only when the restart above actually worked. A reading uploaded here counts as a
        //reading on the server, which resets the timer behind its reboot-the-watch recovery.
        //That is exactly right when the cause has just been cleared and exactly wrong when it
        //has not: it would leave the vendor stack wedged, the readings pulse-only, and the
        //one backstop that reliably fixes it disarmed. So if there was no root and no
        //restart, the gap is left visible and the server is left free to act on it.
        if (restarted) {
            fillGap(context);
        }

        //Ppg used to be asked for a reading here as well. It is gone, for two reasons.
        //
        //It could not have worked: Ppg goes through com.ic.work's SensorDataService, which
        //is the component that stalls, so asking it during a stall only puts another item
        //behind the one that is stuck. fillGap above uses the platform sensor instead, which
        //is a different route to the same hardware and does not touch that queue.
        //
        //And it crashed the launcher every time this fired. Ppg builds a Handler in a field
        //initialiser, which needs a Looper, and moving this work off the receiver's main
        //thread - the fix for the black screen - left it running on a plain thread that has
        //none. "Can't create handler inside thread that has not called Looper.prepare()",
        //thrown from the watchdog, in the home app. One fix's blast radius landing in
        //another's code.
    }

    /**
     * Take one reading over the platform sensor and send it, so the gap has something in it.
     *
     * Blocking, with a ceiling: an optical measurement needs seconds of clean signal and may
     * never converge on a wrist that has moved, so it waits {@link #READ_TIMEOUT_MS} and
     * gives up rather than holding the alarm's thread open.
     */
    private static void fillGap(final Context context) {
        final int[] got = new int[] { -1 };
        final Object done = new Object();
        HeartRate hr = null;

        try {
            hr = new HeartRate(context, new HeartRate.Listener() {
                @Override
                public void onHeartRate(int bpm) {
                    synchronized (done) {
                        got[0] = bpm;
                        done.notifyAll();
                    }
                }
            });

            if (!hr.available()) {
                Log.i(TAG, "no platform pulse sensor - nothing to fill the gap with");
                return;
            }

            hr.start();

            synchronized (done) {
                if (got[0] <= 0) {
                    done.wait(READ_TIMEOUT_MS);
                }
            }

        } catch (Throwable t) {
            Log.w(TAG, "could not read the pulse directly", t);

        } finally {
            try {
                if (hr != null) {
                    hr.stop();                       //the LED costs battery for as long as it runs
                }

            } catch (Throwable t) {
                //ignore
            }
        }

        if (got[0] <= 0) {
            Log.i(TAG, "no pulse from the platform sensor either");
            return;
        }

        upload(context, got[0]);
    }

    /** Send it as the vendor's own pulse frame, so it lands where every other reading does. */
    private static void upload(Context context, int bpm) {
        RootShell root = new RootShell();

        try {
            TrackerConfig cfg = new TrackerConfig(context, root);
            cfg.load();

            if (!cfg.usable()) {
                return;
            }

            //TYPE_PULSE is the vendor's own type 2 on the JK frame - the same one the
            //firmware uses - so this is recorded as a heart rate rather than as something
            //the launcher invented, and it needs no server change to be understood.
            new SleepUpload().sendOne(cfg, TYPE_PULSE, bpm, System.currentTimeMillis());
            Log.i(TAG, "filled the gap with " + bpm + " bpm from the platform sensor");

        } catch (Throwable t) {
            Log.w(TAG, "could not upload the pulse", t);

        } finally {
            try {
                root.close();

            } catch (Throwable t) {
                //ignore
            }
        }
    }

    /**
     * Restart the process that owns the sensor queue.
     *
     * Needs root, which the launcher has where the root helper is installed and does not
     * where it is not - so a failure here is reported and shrugged off rather than treated as
     * an error. Without it the server's reboot is still the backstop; this only makes the
     * cheap recovery available when it can be.
     *
     * Killed by name rather than by a remembered pid: the pid changes every time this works,
     * and killing a stale one would eventually kill something else.
     */
    private static boolean restartSensorService(Context context) {
        RootShell shell = null;

        try {
            shell = new RootShell();

            if (!shell.open() || !shell.isRoot()) {
                Log.i(TAG, "no root - leaving the sensor process alone");
                return false;
            }

            //SIGKILL rather than SIGTERM: the worker is stuck inside a call that is not
            //coming back, so there is nothing to unwind politely.
            shell.exec("for p in $(ps | grep com.ic.work | awk '{print $2}'); do kill -9 $p; done");
            return true;

        } catch (Throwable t) {
            Log.w(TAG, "could not restart the sensor process", t);
            return false;

        } finally {
            try {
                if (shell != null) {
                    shell.close();
                }

            } catch (Throwable t) {
                //ignore
            }
        }
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
