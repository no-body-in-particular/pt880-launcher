package org.watchlauncher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.util.Log;
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

    private static final String TAG = "SleepService";

    /** How soon to try again after the accelerometer hands back an empty buffer. */
    private static final long EMPTY_RETRY_MS = 30 * 1000;

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

    /**
     * And the arm may not have changed angle by more than this, when the question is meaningful.
     *
     * Van Hees watches the wrist angle on short epochs and calls it sleep when it stops changing.
     * Comparing two bursts five minutes apart is not that measurement: five minutes is long
     * enough for a sleeper to turn over, so an ordinary posture change read as movement and reset
     * the whole accumulation. Three nights of the watcher's own log say how badly - 43 % of
     * bursts flagged, the longest still run 4 against a threshold of 6, and the night never
     * detected at all.
     *
     * So the angle is only consulted at the fine cadence below, where consecutive samples are
     * thirty seconds apart and a change really does mean the arm moved. At the coarse cadence
     * only ENMO is asked, because it measures movement during the burst rather than the
     * difference between two snapshots.
     */
    private static final double STILL_ANGLE_DEG = 10.0;

    /**
     * The cadence used once a burst looks still, so the angle test above means something.
     *
     * Sampling this fast all day would cost ten times what the watcher costs now - a five second
     * burst every thirty seconds is a sixth of the processor, against a sixtieth at five
     * minutes - and would buy nothing while the wrist is plainly busy. It is only during a
     * candidate still stretch that the fine grain is worth having, so that is when it is taken.
     */
    private static final long CONFIRM_INTERVAL_MS = 30000;

    /**
     * How close to its owner's resting pulse a wrist has to be before stillness counts as sleep.
     *
     * Stillness alone is not sleep-specific and never was. The watcher's own log caught it
     * declaring sleep at 14:40 on an afternoon, which is what became a "35 minute night" on the
     * server: sitting still after lunch looks exactly like sleeping to an accelerometer.
     *
     * A pulse tells them apart, and there is now a verified one every three minutes. Asleep this
     * wrist reads 51-55; sedentary and awake it reads 60-85. The margin sits inside that gap.
     */
    private static final int SLEEP_BPM_MARGIN = 8;

    /** A pulse older than this says nothing about what the wrist is doing now. */
    private static final long BPM_FRESH_MS = 12 * 60 * 1000;

    /**
     * Stillness needed to start a log when there is no usable pulse.
     *
     * The vitals path can be down - the sensor HAL wedges, and docs/vitals.md has the account -
     * and sleep tracking should not stop with it. This used to ask for double, on the reasoning
     * that without a pulse to tell sleep from sitting still, a longer bout was the only defence
     * left.
     *
     * Thirty at the wearer's request, and the reasoning was costing more than it defended: the
     * longest still run of the night that went unlogged was 55.9 minutes, four short of the bar,
     * and our own measurement returns nothing far more often than the vendor's did - so the
     * no-pulse case is now the common one rather than the exception it was written for.
     *
     * The trade is real and worth stating: a still afternoon can now be logged as sleep. The
     * scorer sees that in the data and it is recoverable, where a night never recorded is not.
     */
    private static final int START_AFTER_STILL_MIN_NO_BPM = 30;

    /**
     * Do not bother scoring a stretch shorter than this.
     *
     * Was ninety minutes, which threw away real sleep: a nap, an early night broken up, or a
     * detector that only caught the back half of one, all vanished rather than being recorded
     * short. Half an hour is the same bout length the onset test uses, so anything the watcher
     * was willing to call sleep is now something the scorer is willing to score.
     */
    private static final int MIN_SCORABLE_MIN = 30;

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

        // Stay off the sensor while the firmware is using it.
        //
        // This used to stand back around the vendor firmware's own PPG measurement, which ran
        // to a fixed cycle and could not survive being interrupted. Nothing else drives the
        // sensor now, so there is no longer a measurement to collide with and every burst is
        // taken when it is due.

        reset();
        try {
            // UI rather than GAME: a third of the sample deliveries for a decision that cannot
            // tell the difference.
            //
            // Every number the scorer acts on is a mean - enmo is sEnmo/n, the angle comes from
            // sx/n, sy/n, sz/n - so none of them move when the rate does. GAME was asking the
            // CPU to wake about five hundred times a burst, every thirty seconds once a night
            // is under way, to compute averages that three hundred fewer samples give just as
            // well.
            //
            // Not SENSOR_DELAY_NORMAL, which would be a further threefold cut. That is 5 Hz,
            // and ENMO is a movement metric: at a Nyquist of 2.5 Hz real wrist motion starts
            // being missed rather than averaged, and the direction of the error is towards
            // reading still - which here means falsely scoring sleep. UI leaves Nyquist above
            // 8 Hz, clear of essentially all wrist movement, so the metric is unchanged.
            sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
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

            // A burst that read nothing is not a still wearer.
            //
            // The accelerometer sometimes hands back a buffer it never filled: every axis zero,
            // every metric zero, and a sample count of exactly 256 where a real burst gives about
            // 505. Gravity alone makes that impossible - a watch lying on a table still reads
            // about 1g on one axis - so all three means at zero is the sensor declining, not the
            // wearer being motionless.
            //
            // It matters because of what the scorer does with it. Perfect stillness is exactly
            // what deep sleep looks like, so an empty burst is not a gap in the record, it is a
            // false claim of the soundest sleep there is. On the night of 31 August nine of the
            // seventy-eight bursts were empty and they clustered where our own measurements ran:
            // 22% of the bursts within ninety seconds of one against 2% of the rest. ppgd reads
            // this same accelerometer for its motion figure, and vitals now run every three
            // minutes.
            //
            // So drop it. A missing burst is honest and the scorer already copes with gaps.
            if (mx == 0 && my == 0 && mz == 0) {
                // Come back sooner than the usual interval. Whatever had the sensor - almost
                // always one of our own measurements - will not have it for long, and the
                // alternative is a hole the length of the interval every time one collides.
                // Half a minute is short enough to recover the epoch and long enough not to
                // spin: the night of 31 August lost four hours to a single empty burst that
                // was then not retried until the next alarm.
                next = EMPTY_RETRY_MS;
                Log.i(TAG, "burst read nothing from the accelerometer (" + n
                        + " samples, all zero); no line written, retrying in "
                        + (EMPTY_RETRY_MS / 1000) + "s");
            } else {
                next = decide(now, mx, my, mz, sd, enmo, range, n);
            }
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

        int state = SleepLog.state(this);

        // How long since the previous burst, which is also which cadence produced it. The angle
        // test only applies at the fine one; see STILL_ANGLE_DEG.
        long sinceLast = SleepLog.lastBurstAt(this) > 0 ? now - SleepLog.lastBurstAt(this) : 0;
        boolean fine = sinceLast > 0 && sinceLast <= CONFIRM_INTERVAL_MS * 2;
        SleepLog.setLastBurstAt(this, now);

        float previous = SleepLog.lastAngle(this);
        boolean turned = fine && !Float.isNaN(previous)
                && Math.abs(angle - previous) > STILL_ANGLE_DEG;
        boolean still = enmo < STILL_ENMO && !turned;
        SleepLog.setLastAngle(this, angle);

        if (state == SleepLog.WATCHING) {
            SleepLog.appendWatch(this, now, mx, my, mz, sd, enmo, range, samples);

            // Seconds of stillness rather than a count of bursts, because the cadence changes
            // underneath it: six bursts means half an hour at the coarse rate and three minutes
            // at the fine one, and counting bursts silently means whichever it happens to be.
            // A gap longer than the coarse cadence is credited only that much - the watch may
            // have been off, or the alarm delayed, and neither is evidence of a still wrist.
            int held = still
                    ? SleepLog.run(this) + (int) (Math.min(sinceLast, WATCH_INTERVAL_MS) / 1000)
                    : 0;

            // A pulse counts as fresh if it was taken inside the current run of stillness, even
            // when that is older than BPM_FRESH_MS. The window exists so a rate from an active
            // hour is not used to judge a quiet one, and a wrist that has not moved since the
            // reading has not had the chance to invalidate it.
            //
            // Without this the fallback bar of sixty minutes applies far more often than it was
            // meant to, because our own daemon returns nothing rather than guessing when its
            // windows disagree - so TrackerLog goes stale in a way the vendor's
            // always-an-answer path never did. Last night cost the whole night to it: the
            // longest still run was 55.9 minutes against a bar of sixty, where the same run
            // with a pulse to hand needed only thirty.
            long fresh = BPM_FRESH_MS;
            long ofRun = (long) SleepLog.run(this) * 1000L;
            if (ofRun > fresh) fresh = ofRun;
            int bpm = TrackerLog.recentBpm(this, fresh);
            int resting = SleepLog.restingBpm(this);
            boolean pulseKnown = bpm > 0 && resting > 0;
            boolean pulseSaysSleep = pulseKnown && bpm <= resting + SLEEP_BPM_MARGIN;
            int needSecs = (pulseKnown ? START_AFTER_STILL_MIN
                                       : START_AFTER_STILL_MIN_NO_BPM) * 60;

            if (still && held >= needSecs && (!pulseKnown || pulseSaysSleep)) {
                Log.i(TAG, "asleep: " + (held / 60) + " min still"
                        + (pulseKnown ? ", pulse " + bpm + " against a resting " + resting
                                      : ", no recent pulse so the long bout was required"));
                SleepLog.setState(this, SleepLog.LOGGING);
                sendFlag(1, now);
                return INTERVAL_MS;
            }
            if (still && held >= needSecs) {
                // Still for long enough, but the wrist is not at rest. This is the afternoon
                // case, and refusing it here is the whole point of asking.
                Log.i(TAG, "still for " + (held / 60) + " min but the pulse is " + bpm
                        + " against a resting " + resting + "; not sleep");
            }
            SleepLog.setRun(this, held);
            refreshFlag(0, now);
            // Look closely while something might be happening, and cheaply when it plainly is
            // not. This is where the fine cadence is bought and where the angle test earns it.
            return still ? CONFIRM_INTERVAL_MS : WATCH_INTERVAL_MS;
        }

        int run = still ? 0 : SleepLog.run(this) + 1;

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
