package org.watchlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Everything a position report is made of: a fix, the serving cell, the access points in
 * earshot, the battery and the step count.
 *
 * <h3>Why the cell and the WiFi list are not optional extras</h3>
 *
 * The vendor sends all three on every report and the server uses them. A fix indoors is rare on
 * this watch -- it has never once completed one, because its A-GPS server was unreachable until
 * recently -- so for most of the day the cell id and the surrounding access points are the only
 * thing locating it at all. A client that sent GPS alone would look correct on a bench and put
 * the watch nowhere on a map.
 *
 * <h3>Reading, not scanning</h3>
 *
 * {@link #wifi} takes the last scan results rather than asking for a fresh scan. The radio is
 * already scanning on its own schedule, a forced scan costs power on every position cycle, and
 * the results are equally good for locating: an access point does not move between one scan and
 * the next. If nothing has scanned recently the field goes out empty, which the server accepts.
 */
public final class TrackerSources {

    private static final String TAG = "TrackerSources";

    private TrackerSources() {
    }

    // ------------------------------------------------------------------ position

    /**
     * The most recent fix from any provider, or null.
     *
     * Both providers are asked and the newer answer wins. GPS is the more accurate one but on
     * this device it is usually the one with nothing to say, so preferring it unconditionally
     * would throw away the only position available.
     */
    /** The last fix this client asked for and got, which is not the same as the framework's. */
    private static volatile Location ourFix;

    /** Satellites used in the last fix, for the position frame's own field. */
    private static volatile int lastSats = 0;

    /** Signal as a percentage, kept up to date by the listener {@link #watchSignal} starts. */
    private static volatile int lastSignal = 0;

    public static int satellites() { return lastSats; }

    public static int signal() { return lastSignal; }

    /**
     * Keep the GSM signal strength current.
     *
     * There is no getter for it on this API level -- a listener is the only way -- so one is
     * registered for the life of the service rather than woken per frame. Registered on the
     * main looper because PhoneStateListener needs one and the tracker thread has none.
     *
     * The vendor's frames carry 065 and 073 in this field, so it is a percentage rather than
     * asu or dBm; getGsmSignalStrength gives 0-31 with 99 for "unknown", which scales.
     */
    public static void watchSignal(final Context c) {
        try {
            new android.os.Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        TelephonyManager tm = (TelephonyManager)
                                c.getSystemService(Context.TELEPHONY_SERVICE);
                        if (tm == null) return;
                        tm.listen(new android.telephony.PhoneStateListener() {
                            public void onSignalStrengthsChanged(
                                    android.telephony.SignalStrength s) {
                                if (s == null) return;
                                int asu = s.getGsmSignalStrength();
                                if (asu < 0 || asu > 31) return;      // 99 means unknown
                                lastSignal = (int) Math.round(asu * 100.0 / 31.0);
                            }
                        }, android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
                    } catch (Throwable t) {
                        Log.w(TAG, "no signal strength", t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "could not watch the signal", t);
        }
    }

    /**
     * Ask the receiver for a fix, and wait for one.
     *
     * <h3>Why the tracker has to ask</h3>
     *
     * Turning the gps provider on in secure settings does not produce fixes. Something has to
     * call {@code requestLocationUpdates}, and on this watch the only things that ever did were
     * the map and the sports screen - so a position was available exactly while somebody was
     * looking at one, and the tracker, which reads last-known, found whatever those had left
     * behind or nothing at all. That is why every frame goes up as V with zeroes even with the
     * receiver switched on.
     *
     * <h3>Duty cycled, not left running</h3>
     *
     * A receiver held open costs battery continuously and, on this watch, makes the firmware's
     * heart rate sensor wedge sooner - the trade the sports menu names. So this opens it for a
     * bounded window, takes the first fix, and closes it again. One window per position cycle,
     * which on a ten minute cycle is a few per cent of the time rather than all of it.
     *
     * The listener is delivered on the main looper because this runs on the tracker's own
     * thread, which has none, and blocking here is the point: the caller is a worker whose only
     * job is to have a fix ready for the next frame.
     *
     * @return the fix, or null if none arrived inside {@code timeoutMs}
     */
    public static Location acquireGps(Context c, long timeoutMs) {
        final LocationManager lm =
                (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.i(TAG, "gps provider is off; not asking for a fix");
                return null;
            }
        } catch (Throwable t) {
            return null;
        }

        // Time and a rough position turn a cold start from minutes into seconds, and both are
        // things this watch already has.
        try {
            Gps.assist(lm);
        } catch (Throwable ignored) { }

        // Satellite count for the frame's own field. Counted while the receiver is open,
        // because GpsStatus is only meaningful during a session.
        final android.location.GpsStatus.Listener sats = new android.location.GpsStatus.Listener() {
            public void onGpsStatusChanged(int event) {
                try {
                    android.location.GpsStatus st = lm.getGpsStatus(null);
                    if (st == null) return;
                    int used = 0;
                    for (android.location.GpsSatellite s : st.getSatellites()) {
                        if (s.usedInFix()) used++;
                    }
                    lastSats = used;
                } catch (Throwable ignored) { }
            }
        };
        try {
            lm.addGpsStatusListener(sats);
        } catch (Throwable ignored) { }

        final Location[] got = new Location[1];
        final LocationListener listener = new LocationListener() {
            public void onLocationChanged(Location l) {
                if (l != null && got[0] == null) got[0] = l;
            }

            public void onStatusChanged(String p, int s, Bundle extras) { }

            public void onProviderEnabled(String p) { }

            public void onProviderDisabled(String p) { }
        };

        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener,
                    Looper.getMainLooper());
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (got[0] == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.w(TAG, "could not ask for a fix", t);
        } finally {
            try {
                lm.removeUpdates(listener);
            } catch (Throwable ignored) { }
            try {
                lm.removeGpsStatusListener(sats);
            } catch (Throwable ignored) { }
        }

        if (got[0] == null) {
            Log.i(TAG, "no gps fix inside " + (timeoutMs / 1000) + "s");
            return null;
        }
        ourFix = got[0];
        Log.i(TAG, "gps fix: " + got[0].getLatitude() + "," + got[0].getLongitude()
                + " +/-" + got[0].getAccuracy() + "m");
        return got[0];
    }

    public static Location lastFix(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            // Ours first: it was taken deliberately for a frame, and on this watch the
            // framework's last-known is usually empty or older.
            Location best = ourFix;
            List<String> providers = lm.getAllProviders();
            for (int i = 0; i < providers.size(); i++) {
                Location l;
                try {
                    l = lm.getLastKnownLocation(providers.get(i));
                } catch (SecurityException e) {
                    continue;
                }
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            return best;
        } catch (Throwable t) {
            Log.w(TAG, "no position", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ cell

    /**
     * {@code MCC, MNC, LAC, CI} as the four fields the frame carries, or null if the modem has
     * not registered.
     *
     * The operator string is 5 or 6 digits with the MCC as the first three; splitting at a fixed
     * three is right for both lengths and wrong only for the handful of three-digit MNCs, which
     * this network is not one of.
     */
    public static String[] cell(Context c) {
        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return null;
            String op = tm.getNetworkOperator();
            if (op == null || op.length() < 4) return null;

            String mcc = op.substring(0, 3);
            String mnc = op.substring(3);

            int lac = 0, cid = 0;
            Object cl = tm.getCellLocation();
            if (cl instanceof GsmCellLocation) {
                lac = ((GsmCellLocation) cl).getLac();
                cid = ((GsmCellLocation) cl).getCid();
            }
            return new String[]{mcc, mnc, Integer.toString(lac), Integer.toString(cid)};
        } catch (Throwable t) {
            Log.w(TAG, "no cell info", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ wifi

    /** Maximum access points per report. The vendor sends a similar handful. */
    private static final int MAX_APS = 8;

    /**
     * {@code AP1|<bssid>|<rssi>&AP2|<bssid>|<rssi>...}, strongest first, or "" if nothing has
     * been seen. The index restarts at 1 each report, which is what the capture shows.
     */
    public static String wifi(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            List<ScanResult> results = wm.getScanResults();

            // Ask for the next one. getScanResults() hands back whatever the framework last
            // collected, and on this watch nothing else ever asks: wifi_scan_always_enabled is
            // unset, and the framework only scans of its own accord while it is disconnected
            // and looking for a network. Associated - which is the normal state, and stays the
            // normal state out of the house when the network is a phone's hotspot travelling
            // with the wearer - nothing scans, the cache ages out, and this returned nothing.
            // Every position frame went up with a cell and no access points, which is the one
            // case the server most needs them: indoors, where there is no GPS either.
            //
            // Results are asynchronous, so this scan pays for the next frame rather than this
            // one. On a ten-minute cycle that is the right trade - no waiting on a listener
            // that would have to be unregistered, and one scan per position is what having a
            // position at all is worth.
            try {
                wm.startScan();
            } catch (Throwable t) {
                Log.w(TAG, "could not start a wifi scan", t);
            }

            if (results == null || results.isEmpty()) return "";

            // Strongest first: if the list has to be cut, the useful ones should survive.
            ScanResult[] a = results.toArray(new ScanResult[results.size()]);
            for (int i = 1; i < a.length; i++) {
                ScanResult k = a[i];
                int j = i - 1;
                while (j >= 0 && a[j].level < k.level) {
                    a[j + 1] = a[j];
                    j--;
                }
                a[j + 1] = k;
            }

            StringBuilder b = new StringBuilder();
            int n = Math.min(a.length, MAX_APS);
            for (int i = 0; i < n; i++) {
                if (i > 0) b.append('&');
                b.append("AP").append(i + 1).append('|')
                 .append(a[i].BSSID).append('|').append(a[i].level);
            }
            return b.toString();
        } catch (Throwable t) {
            Log.w(TAG, "no wifi scan", t);
            return "";
        }
    }

    // ------------------------------------------------------------------ battery

    public static int battery(Context c) {
        try {
            Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return 0;
            int level = i.getIntExtra("level", -1);
            int scale = i.getIntExtra("scale", 100);
            return (level < 0 || scale <= 0) ? 0 : (level * 100 / scale);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ steps

    /**
     * Steps since boot from the hardware counter, or the last value we managed to read.
     *
     * The sensor is on-demand: it reports once when you register and then only on change, so a
     * synchronous read is not possible and this keeps the last value instead. Registering
     * briefly on each report is cheap -- the counter runs in hardware whether anyone is
     * listening or not.
     */
    private static volatile int lastSteps = 0;

    public static int steps(Context c) {
        // TYPE_STEP_COUNTER first, then by name: this watch's counter is a DA217 with a vendor
        // type number, so getDefaultSensor(TYPE_STEP_COUNTER) returns null on it and the step
        // field in every heartbeat has been a hardcoded-looking zero ever since.
        float[] v = oneShot(c, "step", Sensor.TYPE_STEP_COUNTER, 5000);
        if (v == null) {
            Log.i(TAG, "no step count; the counter did not answer");
            return lastSteps;
        }
        int n = (int) v[0];
        if (n >= 0) lastSteps = n;
        return lastSteps;
    }

    // ------------------------------------------------------------------ frames

    /**
     * A complete position report, fix or no fix.
     *
     * When there is no fix the frame still goes, carrying the cell and the access points. That
     * is the case that matters on this watch: the server can place it from those alone, and a
     * client that stayed silent without GPS would be silent almost all the time.
     */
    public static String positionFrame(Context c, String id) {
        Location l = lastFix(c);
        String[] cells = cell(c);
        String aps = wifi(c);

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        boolean valid = l != null && isFresh(l);
        if (valid) cal.setTimeInMillis(l.getTime());

        double lat = valid ? l.getLatitude() : 0;
        double lon = valid ? l.getLongitude() : 0;

        // Keep our own record of it. This client is the tracker now, so the sports screen reads
        // what we sent rather than the vendor database it used to copy out with root - that app
        // is not on the watch any more. hasPosition is the server's own test, coordinates rather
        // than the A/V flag: a frame of zeroes carries no position however it is flagged.
        TrackerLog.recordFix(c, valid && (lat != 0 || lon != 0), lat, lon,
                System.currentTimeMillis());

        return BeehomeCodec.location(id, valid,
                lat, lon,
                valid ? l.getSpeed() * 3.6 : 0,          // m/s on the API, km/h on the wire
                (valid && l.hasBearing()) ? l.getBearing() : 0,
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
                signal(), valid ? satellites() : 0, battery(c),
                cells, aps);
    }

    /**
     * A fix older than this is reported as no-fix.
     *
     * Sending a stale position as current is worse than sending none: the server cannot tell the
     * difference, and a watch that has not moved for an hour looks identical to one whose GPS
     * stopped an hour ago.
     */
    private static final long FIX_MAX_AGE_MS = 30 * 60 * 1000L;

    private static boolean isFresh(Location l) {
        long age = System.currentTimeMillis() - l.getTime();
        return age >= 0 && age < FIX_MAX_AGE_MS;
    }

    /**
     * The time in the shape {@code APJK} carries it, in UTC.
     *
     * It was local, which put every reading two hours into the future for a watch on CEST --
     * the server parses these with {@code timegm}. SleepUpload has always stamped UTC, so the
     * two senders disagreed about what the same field meant, and one of them had to be wrong.
     */
    public static String stamp() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        return String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND));
    }

    // ------------------------------------------------------------------ temperature

    /** Last body temperature in degrees C, or 0 if none has arrived yet. */
    private static volatile float lastTemp = 0f;

    /**
     * Read the body temperature sensor.
     *
     * The sensor is a vendor one (GXTS02S) with a non-standard type, so it is found by name
     * rather than by {@code TYPE_AMBIENT_TEMPERATURE}, which it does not answer to. It reports
     * centi-degrees: the wire carries 36.35 where the sensor says 3635.
     *
     * On-demand like the step counter, so this registers, waits briefly for the first value and
     * lets go again rather than holding a subscription open between the ten-minute reports.
     */
    /**
     * One reading from an on-demand sensor.
     *
     * <h3>Why registerListener alone is not enough</h3>
     *
     * {@code dumpsys sensorservice} on this watch lists five sensors and marks three of them
     * "on-demand":
     *
     * <pre>
     * gh30x_sensor          | Goodix | 0x08 | on-demand | last=&lt; 59.0,120.0, 79.0&gt;
     * DA217 Step Counter    | Mira   | 0x0a | on-demand | last=&lt;0&gt;
     * GXTS02S Temperature   | GXCAS  | 0x09 | on-demand | last=&lt;  0.0,  0.0,  0.0&gt;
     * </pre>
     *
     * On-demand is Android's word for a one-shot trigger sensor. Those deliver through
     * {@link TriggerEventListener} and rearm after each event; {@code registerListener} on one
     * produces nothing, ever, and reports no error while doing it. HeartRate has always wired
     * both, which is why the pulse worked - and why its {@code last=} column has real numbers
     * in it while the step counter and the thermometer sit at zero.
     *
     * So both are wired here too. Whichever the driver honours delivers; the other is inert.
     *
     * <h3>And why it polls rather than sleeping once</h3>
     *
     * These take seconds, not milliseconds - a thermometer against skin has to settle. A fixed
     * sleep either gives up too early or wastes the difference on every reading.
     *
     * @return the values, or null if nothing arrived inside {@code waitMs}
     */
    private static float[] oneShot(Context c, String nameContains, int type, long waitMs) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return null;

        Sensor found = null;
        if (type > 0) found = sm.getDefaultSensor(type);
        if (found == null) {
            // By name, because a vendor that gives its sensor a private type number makes
            // getDefaultSensor useless -- and both of these do.
            List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
            for (int i = 0; all != null && i < all.size(); i++) {
                String n = all.get(i).getName();
                if (n != null && n.toLowerCase(Locale.US).indexOf(nameContains) >= 0) {
                    found = all.get(i);
                    break;
                }
            }
        }
        if (found == null) return null;

        final float[][] got = new float[1][];

        SensorEventListener listener = new SensorEventListener() {
            public void onSensorChanged(SensorEvent e) {
                if (e.values != null && e.values.length > 0 && got[0] == null) {
                    got[0] = e.values.clone();
                }
            }

            public void onAccuracyChanged(Sensor s, int a) {
            }
        };
        TriggerEventListener oneshot = new TriggerEventListener() {
            public void onTrigger(TriggerEvent e) {
                if (e.values != null && e.values.length > 0 && got[0] == null) {
                    got[0] = e.values.clone();
                }
            }
        };

        try {
            try {
                sm.registerListener(listener, found, SensorManager.SENSOR_DELAY_NORMAL);
            } catch (Throwable ignored) { /* the trigger path may still work */ }
            try {
                sm.requestTriggerSensor(oneshot, found);
            } catch (Throwable ignored) { /* not a trigger sensor */ }

            long deadline = System.currentTimeMillis() + waitMs;
            while (got[0] == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.w(TAG, "sensor " + nameContains + " failed", t);
        } finally {
            try { sm.unregisterListener(listener); } catch (Throwable ignored) { }
            try { sm.cancelTriggerSensor(oneshot, found); } catch (Throwable ignored) { }
        }
        return got[0];
    }

    public static float temperature(Context c) {
        float[] v = oneShot(c, "temperature", 0, 10000);
        if (v == null) {
            Log.i(TAG, "no temperature reading; the sensor did not answer");
            return lastTemp;
        }
        float x = v[0];
        // Accept either scale: some builds report degrees directly. A body reading is never
        // 2771 degrees and never 0.3, so the magnitude disambiguates it without having to know
        // which firmware is underneath.
        if (x > 100f) x = x / 100f;
        if (x > 20f && x < 45f) lastTemp = x;
        else Log.i(TAG, "temperature " + v[0] + " is not a body reading; keeping the last");
        return lastTemp;
    }


    // ------------------------------------------------------------------ motion

    /**
     * How much the watch moved over a short window, as the mean absolute deviation of the
     * accelerometer magnitude in m/s^2.
     *
     * Gravity is not subtracted: the deviation from the window's own mean removes it, and that
     * works whatever orientation the watch is lying in. A watch on a table reads near zero, a
     * worn one reads well above it even when its owner is sitting still, because a wrist is
     * never quite motionless.
     */
    public static float motionEnergy(Context c, int windowMs) {
        final java.util.List<Float> mags = new java.util.ArrayList<Float>();
        try {
            SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return 0f;
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc == null) return 0f;

            SensorEventListener l = new SensorEventListener() {
                public void onSensorChanged(SensorEvent e) {
                    if (e.values == null || e.values.length < 3) return;
                    float x = e.values[0], y = e.values[1], z = e.values[2];
                    synchronized (mags) {
                        if (mags.size() < 400) {
                            mags.add(Float.valueOf((float) Math.sqrt(x * x + y * y + z * z)));
                        }
                    }
                }

                public void onAccuracyChanged(Sensor s, int a) {
                }
            };
            sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_NORMAL);
            try {
                Thread.sleep(Math.max(500, windowMs));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sm.unregisterListener(l);
        } catch (Throwable t) {
            Log.w(TAG, "no motion reading", t);
            return 0f;
        }

        synchronized (mags) {
            if (mags.size() < 4) return 0f;
            float mean = 0f;
            for (int i = 0; i < mags.size(); i++) mean += mags.get(i).floatValue();
            mean /= mags.size();
            float dev = 0f;
            for (int i = 0; i < mags.size(); i++) dev += Math.abs(mags.get(i).floatValue() - mean);
            return dev / mags.size();
        }
    }

}
