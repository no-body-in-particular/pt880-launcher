package org.watchlauncher;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;

/**
 * The pulse sensor on the back of the case.
 *
 * It is a Goodix GH30x, an optical PPG part, and this build exposes it through
 * the ordinary sensor framework:
 *
 *     gh30x_sensor | Goodix | 0x00000008 | on-demand | last=<51.0,123.0,81.0>
 *
 * Three values, and they line up exactly with what the tracker protocol
 * uploads in its {@code APHT} frame -- "heart rate + blood pressure". So
 * values[0] is bpm and the other two are systolic and diastolic. Only the
 * first is shown; the other two are read here because they cost nothing and
 * having them named is better than having them as magic indices.
 *
 * <h3>Finding it</h3>
 *
 * Not by type: {@code TYPE_HEART_RATE} is 21 and arrived in API 20, and this
 * watch is 19, so the vendor gave it a private type number that means nothing
 * portable. The name is the stable handle.
 *
 * <h3>Getting a reading out of it</h3>
 *
 * dumpsys calls it "on-demand", which is Android's word for a one-shot trigger
 * sensor -- those deliver through {@link TriggerEventListener} and rearm each
 * time, not through a continuous listener. Which of the two this vendor
 * actually implements is not something the dump settles, so both are wired up:
 * a normal listener, and a trigger request. Whichever the driver honours, a
 * reading arrives; the other is inert.
 *
 * A PPG reading is not instant either. The sensor has to see enough pulses to
 * be sure, which takes seconds, so the screen shows that it is measuring
 * rather than showing a zero.
 *
 * <h3>One reading, not a stream</h3>
 *
 * An optical sensor measures by lighting an LED against the skin and watching
 * the reflection, so leaving it running costs battery continuously for a
 * number that changes slowly. It is stopped as soon as it produces a reading,
 * and started again only when the sports screen is opened or refreshed. In
 * between, the last value the tracker logged is what gets shown -- that is
 * already being measured every ten minutes by the firmware, at no cost to us.
 */
public class HeartRate {

    public interface Listener {
        void onHeartRate(int bpm);
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Thread own;
    private final SensorManager sensors;
    private final Sensor sensor;
    private final Listener listener;

    private int bpm = -1;
    private int systolic = -1, diastolic = -1;
    private long readingAt = 0;
    private boolean running = false;

    public HeartRate(Context c, Listener l) {
        ctx = c;
        listener = l;
        sensors = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        sensor = find();
    }

    /** The Goodix part, by name. Falls back to anything that calls itself a
     *  heart rate sensor, so this is not welded to one vendor's spelling. */
    private Sensor find() {
        if (sensors == null) return null;
        List<Sensor> all = sensors.getSensorList(Sensor.TYPE_ALL);
        if (all == null) return null;
        for (int i = 0; i < all.size(); i++) {
            Sensor s = all.get(i);
            String n = s.getName();
            String v = s.getVendor();
            n = (n == null) ? "" : n.toLowerCase(Locale.US);
            v = (v == null) ? "" : v.toLowerCase(Locale.US);
            if (n.contains("gh30") || n.contains("heart") || n.contains("hrs")
                    || n.contains("ppg") || v.contains("goodix")) {
                return s;
            }
        }
        return null;
    }

    public boolean available() { return sensor != null; }

    public String sensorName() {
        return sensor == null ? "none" : sensor.getName();
    }

    public int bpm() { return bpm; }

    /** Systolic/diastolic, or -1. Read but not shown; the screen was asked for
     *  a heart rate. */
    public int systolic() { return systolic; }
    public int diastolic() { return diastolic; }

    /** Milliseconds since the last reading, or -1 if there has not been one. */
    public long age() {
        return readingAt == 0 ? -1 : (System.currentTimeMillis() - readingAt);
    }

    /**
     * When this measurement was asked for, so a cached value cannot answer it.
     *
     * <h3>Why this is needed</h3>
     *
     * registerListener on this sensor delivers its last value straight away, before the LED
     * has even come on. The wait loop upstream is "spin until bpm() is non-zero", so that
     * cached number satisfied it in milliseconds, the reading was sent, and stop() unregistered
     * everything before the measurement the trigger asked for could happen.
     *
     * The symptom is unmistakable once you know: the same 59 bpm and 120/79 on every cycle for
     * hours, and no green LED. dumpsys agrees -- gh30x_sensor's "last=< 59.0,120.0, 79.0>" is
     * exactly what was being reported as a fresh reading each time.
     *
     * SensorEvent timestamps are nanoseconds on the elapsed-realtime clock, so a replayed value
     * carries the time it was actually measured and is older than this. Some drivers stamp
     * zero; for those the dwell below is the fallback.
     */
    private long startedAt;

    /** A value arriving sooner than this after start() is the cache, not a measurement. */
    private static final long MIN_DWELL_MS = 1500;

    /** Take one reading. Returns to idle by itself once it has one. */
    /**
     * Ask for a reading, from whichever source can give one.
     *
     * The platform sensor is the vendor's, and the vendor's daemon no longer runs - vitalsd took
     * its init slot. So it is registered for and never speaks, which left this screen showing the
     * last figure the tracker had logged, greyed out, for ever. It stays registered because it
     * costs nothing and would start working again if the vendor daemon were ever put back.
     *
     * The reading that actually arrives comes from our own daemon, the same one the tracker uses,
     * on a thread because it lights an LED for the better part of a minute. measuring() stays
     * true meanwhile, which is what puts the screen into its reading state rather than showing a
     * stale number as though it were current.
     */
    public void start() {
        if (running) return;
        running = true;
        startOwn();
        startedAt = android.os.SystemClock.elapsedRealtimeNanos();
        try {
            sensors.registerListener(events, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception e) { /* the trigger path may still work */ }
        // Asked for once. A one-shot sensor fires and disarms, and that is the
        // whole intent here -- there is deliberately no rearm.
        try {
            sensors.requestTriggerSensor(trigger, sensor);
        } catch (Exception e) { /* not a trigger sensor */ }
    }

    /** One measurement from vitalsd, reported on the UI thread. */
    private void startOwn() {
        if (own != null && own.isAlive()) return;
        own = new Thread(new Runnable() {
            public void run() {
                final Vitals r = OwnVitals.measure(ctx, false);
                ui.post(new Runnable() {
                    public void run() {
                        running = false;
                        if (r == null || r.heartRate <= 0) {
                            if (listener != null) listener.onHeartRate(bpm);
                            return;
                        }
                        bpm = r.heartRate;
                        if (r.systolic > 0) systolic = r.systolic;
                        if (r.diastolic > 0) diastolic = r.diastolic;
                        readingAt = System.currentTimeMillis();
                        if (listener != null) listener.onHeartRate(bpm);
                    }
                });
            }
        }, "sports-hr");
        own.setDaemon(true);
        own.start();
    }

    /** True while the LED is on and no reading has come back yet. */
    public boolean measuring() { return running; }

    public void stop() {
        if (!running) return;
        running = false;
        try { sensors.unregisterListener(events); } catch (Exception e) { /* ignore */ }
        try { sensors.cancelTriggerSensor(trigger, sensor); } catch (Exception e) { /* ignore */ }
    }

    private void take(float[] values, long eventNanos) {
        if (values == null || values.length == 0) return;
        if (!fresh(eventNanos)) return;
        int v = Math.round(values[0]);
        // A PPG part reports 0 while it is still working out the rate, and
        // nonsense if the watch is not being worn. Neither is a heart rate.
        if (v < 25 || v > 250) return;
        bpm = v;
        if (values.length >= 3) {
            systolic = Math.round(values[1]);
            diastolic = Math.round(values[2]);
        }
        readingAt = System.currentTimeMillis();
        // One reading is the whole job. Stop before telling anyone, so the
        // sensor is already off by the time the screen redraws.
        stop();
        if (listener != null) listener.onHeartRate(bpm);
    }

    /**
     * Was this measured for us, or is it the value the sensor already had?
     *
     * The timestamp settles it when the driver provides one. When it does not -- zero, or
     * something not on the elapsed-realtime clock -- fall back to how long the LED has been on:
     * a PPG part needs seconds of clean signal, so anything inside the first moment cannot be a
     * real measurement whatever its timestamp says.
     */
    private boolean fresh(long eventNanos) {
        if (eventNanos > 0 && eventNanos > startedAt) return true;
        long onFor = (android.os.SystemClock.elapsedRealtimeNanos() - startedAt) / 1000000L;
        return onFor >= MIN_DWELL_MS;
    }

    private final SensorEventListener events = new SensorEventListener() {
        public void onSensorChanged(SensorEvent e) { take(e.values, e.timestamp); }
        public void onAccuracyChanged(Sensor s, int accuracy) { }
    };

    private final TriggerEventListener trigger = new TriggerEventListener() {
        public void onTrigger(TriggerEvent e) { take(e.values, e.timestamp); }
    };
}
