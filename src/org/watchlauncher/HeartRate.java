package org.watchlauncher;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;

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

    private final SensorManager sensors;
    private final Sensor sensor;
    private final Listener listener;

    private int bpm = -1;
    private int systolic = -1, diastolic = -1;
    private long readingAt = 0;
    private boolean running = false;

    public HeartRate(Context c, Listener l) {
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

    /** Take one reading. Returns to idle by itself once it has one. */
    public void start() {
        if (sensor == null || running) return;
        running = true;
        try {
            sensors.registerListener(events, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Exception e) { /* the trigger path may still work */ }
        // Asked for once. A one-shot sensor fires and disarms, and that is the
        // whole intent here -- there is deliberately no rearm.
        try {
            sensors.requestTriggerSensor(trigger, sensor);
        } catch (Exception e) { /* not a trigger sensor */ }
    }

    /** True while the LED is on and no reading has come back yet. */
    public boolean measuring() { return running; }

    public void stop() {
        if (!running) return;
        running = false;
        try { sensors.unregisterListener(events); } catch (Exception e) { /* ignore */ }
        try { sensors.cancelTriggerSensor(trigger, sensor); } catch (Exception e) { /* ignore */ }
    }

    private void take(float[] values) {
        if (values == null || values.length == 0) return;
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

    private final SensorEventListener events = new SensorEventListener() {
        public void onSensorChanged(SensorEvent e) { take(e.values); }
        public void onAccuracyChanged(Sensor s, int accuracy) { }
    };

    private final TriggerEventListener trigger = new TriggerEventListener() {
        public void onTrigger(TriggerEvent e) { take(e.values); }
    };
}
