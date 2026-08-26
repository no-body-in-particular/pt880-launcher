package org.watchlauncher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Samples the accelerometer overnight, in short bursts.
 *
 * <h3>Why bursts</h3>
 *
 * {@code dumpsys sensorservice} says "no batching support" on every sensor
 * here, so there is no hardware FIFO: a continuously registered sensor keeps
 * the application processor awake for every single sample, all night. Instead
 * an alarm wakes the watch every {@link #INTERVAL_MS}, it samples for
 * {@link #BURST_MS}, writes one summary line, and goes back to sleep. The CPU
 * is awake for about a sixth of the night rather than all of it.
 *
 * The cost is time resolution: one reading every 30 seconds instead of every
 * 5. That is coarser than van Hees's original 5-second epochs, but the
 * algorithm is looking for sustained inactivity over five minutes and more, so
 * ten samples per window is still enough to see it. It is a real deviation
 * from the published method and is written down here rather than hidden.
 *
 * <h3>What gets written</h3>
 *
 * Per burst, the mean of each axis, the spread of the vector magnitude, and
 * two activity metrics -- deliberately more than the scorer needs. ENMO and
 * the magnitude range are there so a different algorithm can be tried against
 * the same night without having to record another one.
 *
 * <h3>Staying alive</h3>
 *
 * The service does not run all night. Each burst is its own short-lived start,
 * driven by an {@link AlarmManager} wakeup, so a service killed for memory is
 * simply restarted by the next alarm instead of taking the night with it.
 */
public class SleepService extends Service implements SensorEventListener {

    /** How often to sample once sleep has been detected. */
    public static final long INTERVAL_MS = 30000;

    /** How often to sample while merely watching for it. A burst every five
     *  minutes costs about a sixtieth of the CPU that logging does, which is
     *  what makes leaving this armed all day reasonable. */
    public static final long WATCH_INTERVAL_MS = 300000;

    /** Stillness this long starts a log. Thirty minutes, the same bout length
     *  van Hees requires, so sitting through a film does not count as a nap
     *  and a genuine sleep is only ever missed by its first half hour --
     *  which the scorer would not have counted as sleep either. */
    private static final int START_AFTER_STILL_MIN = 30;

    /** Movement this long ends it. Long enough that a trip to the bathroom
     *  does not close the night and split it across two files. */
    private static final int STOP_AFTER_MOVING_MIN = 20;

    /** Below this the wrist is not doing anything. ENMO is the vector
     *  magnitude less one g: sensor noise on a still wrist sits well under
     *  this, sitting at a desk is around it, walking is many times it.
     *  A guess until a real night says otherwise, which is why the watcher
     *  keeps its own log. */
    private static final double STILL_ENMO = 0.015;

    /** And the arm may not have changed angle by more than this. */
    private static final double STILL_ANGLE_DEG = 10.0;

    /** Do not bother scoring a stretch shorter than this. */
    private static final int MIN_SCORABLE_MIN = 90;

    /**
     * How often the live sleeping flag is resent when nothing has changed.
     *
     * Five minutes, which is the same cadence the watch already wakes at, so
     * it costs one small frame on a wakeup that was happening anyway rather
     * than a wakeup of its own. While actually logging, bursts come every
     * thirty seconds and this holds the flag down to one in ten of them.
     *
     * It was half an hour, chosen because the chart treats a gap over
     * forty-five minutes as a break in the series. That left no margin at
     * all: a single missed burst - a crash, a moment without signal - put two
     * points more than forty-five minutes apart and the line came apart.
     * Five minutes means eight bursts in a row have to fail before the graph
     * shows a hole, and a hole then means something really was wrong.
     */
    private static final long FLAG_REFRESH_MS = 5 * 60 * 1000L;

    /** How long to sample for once awake. */
    private static final long BURST_MS = 5000;

    /** Standard gravity, for converting m/s^2 to g. */
    private static final float G = 9.80665f;

    public static final String ACTION_BURST = "org.watchlauncher.SLEEP_BURST";
    private static final int ALARM_ID = 7301;

    private static PowerManager.WakeLock wake;

    private SensorManager sensors;
    private Sensor accel;
    private final Handler ui = new Handler();

    // Burst accumulators.
    private int n = 0;
    private double sx, sy, sz;          // sums per axis, in g
    private double sMag, sMagSq;        // magnitude, for its spread
    private double sEnmo;               // Euclidean norm minus one, clipped
    private double minMag = Double.MAX_VALUE, maxMag = -Double.MAX_VALUE;
    private boolean sampling = false;

    // ---------------------------------------------------------------- schedule

    /** Arm the next burst. Called after every burst, and when logging starts. */
    public static void schedule(Context c, long delayMs) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        // setExact rather than setRepeating: KitKat made repeating alarms
        // inexact, and a sleep log with drifting epochs is harder to score.
        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs, pending(c));
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(c));
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, SleepAlarmReceiver.class);
        i.setAction(ACTION_BURST);
        return PendingIntent.getBroadcast(c, ALARM_ID, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Held across the hand-off from the alarm receiver into this service, so
     *  the watch cannot fall asleep between the two. */
    static synchronized void holdWakeLock(Context c) {
        if (wake == null) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchlauncher.sleep");
            wake.setReferenceCounted(false);
        }
        // Never longer than one burst: a wake lock leaked overnight would flatten
        // the battery, which is a worse outcome than a missing epoch.
        if (!wake.isHeld()) wake.acquire(BURST_MS + 5000);
    }

    private static synchronized void releaseWakeLock() {
        if (wake != null && wake.isHeld()) {
            try { wake.release(); } catch (Exception e) { /* already gone */ }
        }
    }

    // ---------------------------------------------------------------- burst

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!SleepLog.enabled(this)) {
            cancel(this);
            releaseWakeLock();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (sampling) return START_NOT_STICKY;      // a burst is already running
        sampling = true;

        // Arm the next burst before taking this one, not after.
        //
        // The chain used to be re-armed only once sampling finished, so
        // anything that ended the process in between - a crash, the low
        // memory killer, an install - broke it silently and the watch simply
        // stopped recording until the launcher next started. Arming first
        // means the worst case is one missed burst rather than every burst
        // from then on. The interval is replaced at the end with whatever
        // this burst decides, since setExact on the same PendingIntent
        // supersedes it.
        if (SleepLog.enabled(this)) schedule(this, WATCH_INTERVAL_MS);

        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accel = (sensors == null) ? null
                : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel == null) {
            SleepLog.setEnabled(this, false);
            finishBurst();
            return START_NOT_STICKY;
        }

        reset();
        try {
            sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        } catch (Exception e) {
            finishBurst();
            return START_NOT_STICKY;
        }
        ui.postDelayed(stop, BURST_MS);
        return START_NOT_STICKY;
    }

    private final Runnable stop = new Runnable() {
        public void run() { finishBurst(); }
    };

    private void reset() {
        n = 0;
        sx = sy = sz = 0;
        sMag = sMagSq = sEnmo = 0;
        minMag = Double.MAX_VALUE;
        maxMag = -Double.MAX_VALUE;
    }

    public void onSensorChanged(SensorEvent e) {
        if (e.values == null || e.values.length < 3) return;
        double x = e.values[0] / G, y = e.values[1] / G, z = e.values[2] / G;
        double mag = Math.sqrt(x * x + y * y + z * z);

        sx += x; sy += y; sz += z;
        sMag += mag;
        sMagSq += mag * mag;
        // ENMO: the vector magnitude less one g, negatives clipped away. A
        // standard raw-acceleration activity metric that needs no calibration
        // against a particular vendor's "counts".
        sEnmo += Math.max(0, mag - 1.0);
        if (mag < minMag) minMag = mag;
        if (mag > maxMag) maxMag = mag;
        n++;
    }

    public void onAccuracyChanged(Sensor s, int accuracy) { }

    private void finishBurst() {
        ui.removeCallbacks(stop);
        try { if (sensors != null) sensors.unregisterListener(this); }
        catch (Exception e) { /* was not registered */ }

        long now = System.currentTimeMillis();
        long next = WATCH_INTERVAL_MS;

        if (n > 0) {
            double mx = sx / n, my = sy / n, mz = sz / n;
            double meanMag = sMag / n;
            double var = Math.max(0, sMagSq / n - meanMag * meanMag);
            double range = (maxMag > minMag) ? (maxMag - minMag) : 0;
            double enmo = sEnmo / n;
            double sd = Math.sqrt(var);
            next = decide(now, mx, my, mz, sd, enmo, range, n);
        }

        sampling = false;
        if (SleepLog.enabled(this)) schedule(this, next);
        releaseWakeLock();
        stopSelf();
    }

    /**
     * Watch for sleep, or watch for it ending.
     *
     * The whole point of the two cadences: watching costs a burst every five
     * minutes and runs all day, and only once the wrist has been still for
     * half an hour does it start spending a burst every thirty seconds. When
     * movement comes back and stays, the night is scored and sent without
     * anyone having to remember to do it.
     *
     * @return how long to wait before the next burst
     */
    private long decide(long now, double mx, double my, double mz,
                        double sd, double enmo, double range, int samples) {
        double flat = Math.sqrt(mx * mx + my * my);
        double angle = Math.atan2(mz, flat) * 180.0 / Math.PI;

        float previous = SleepLog.lastAngle(this);
        boolean turned = !Float.isNaN(previous)
                && Math.abs(angle - previous) > STILL_ANGLE_DEG;
        boolean still = enmo < STILL_ENMO && !turned;
        SleepLog.setLastAngle(this, angle);

        int state = SleepLog.state(this);
        int run = still == (state == SleepLog.WATCHING) ? SleepLog.run(this) + 1 : 0;

        if (state == SleepLog.WATCHING) {
            SleepLog.appendWatch(this, now, mx, my, mz, sd, enmo, range, samples);
            int needed = (START_AFTER_STILL_MIN * 60)
                    / (int) (WATCH_INTERVAL_MS / 1000);
            if (still && run >= needed) {
                SleepLog.setState(this, SleepLog.LOGGING);
                sendFlag(1, now);
                return INTERVAL_MS;
            }
            SleepLog.setRun(this, run);
            refreshFlag(0, now);
            return WATCH_INTERVAL_MS;
        }

        // Logging. Every burst is kept, movement or not -- the scorer needs
        // the wake epochs as much as the sleep ones to measure WASO.
        SleepLog.append(this, now, mx, my, mz, sd, enmo, range, samples);
        int needed = (STOP_AFTER_MOVING_MIN * 60) / (int) (INTERVAL_MS / 1000);
        if (!still && run >= needed) {
            SleepLog.setState(this, SleepLog.WATCHING);
            sendFlag(0, now);
            scoreAndSend();
            return WATCH_INTERVAL_MS;
        }
        SleepLog.setRun(this, run);
        refreshFlag(1, now);
        return INTERVAL_MS;
    }

    /** Resend the flag only if it has been a while, so the graph keeps a
     *  continuous line without a connection every burst. */
    private void refreshFlag(int value, long now) {
        if (now - SleepLog.flagSentAt(this) < FLAG_REFRESH_MS) return;
        sendFlag(value, now);
    }

    /** Asleep or not, as a stat, on its own thread. */
    private void sendFlag(final int value, final long at) {
        SleepLog.setFlagSentAt(this, at);
        final Context ctx = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                RootShell root = new RootShell();
                try {
                    TrackerConfig cfg = new TrackerConfig(ctx, root);
                    cfg.load();
                    SleepUpload up = new SleepUpload();
                    up.sendOne(cfg, SleepUpload.TYPE_SLEEPING, value, at);
                } catch (Exception e) {
                    /* the next refresh will carry it */
                } finally {
                    root.close();
                }
            }
        }).start();
    }

    /** Score the night that just ended and push it to the tracker server, on
     *  its own thread -- this reads a file, runs a rolling median over it and
     *  then opens a socket, none of which belongs in a service callback. */
    private void scoreAndSend() {
        final Context ctx = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    String night = SleepLog.latestNight();
                    if (night == null) return;
                    if (night.equals(SleepLog.lastScored(ctx))) return;

                    java.util.List<SleepLog.Epoch> epochs = SleepLog.read(night);
                    int minutes = (epochs.size() * (int) (INTERVAL_MS / 1000)) / 60;
                    if (minutes < MIN_SCORABLE_MIN) return;   // a nap, not a night

                    SleepScore.Result r = SleepScore.score(epochs);
                    if (!r.valid) return;

                    // The day's running total, counted against the day the
                    // sleep ended -- so a nap this afternoon adds to last
                    // night rather than starting a new figure.
                    int dayTotal = SleepLog.addDayMinutes(ctx, r.wakeAt, r.tstMin);

                    RootShell root = new RootShell();
                    try {
                        TrackerConfig cfg = new TrackerConfig(ctx, root);
                        cfg.load();
                        SleepUpload up = new SleepUpload();
                        if (up.sendScore(cfg, r) > 0) SleepLog.markScored(ctx, night);
                        up.sendOne(cfg, SleepUpload.TYPE_DAY_TOTAL, dayTotal, r.wakeAt);
                    } finally {
                        root.close();
                    }
                } catch (Exception e) {
                    // Tomorrow's burst will try again; the log is still on the
                    // card either way, so nothing is lost by failing quietly.
                }
            }
        }).start();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        ui.removeCallbacks(stop);
        try { if (sensors != null) sensors.unregisterListener(this); }
        catch (Exception e) { /* ignore */ }
        super.onDestroy();
    }
}
