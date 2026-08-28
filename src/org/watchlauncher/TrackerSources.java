package org.watchlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
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
    public static Location lastFix(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
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
        try {
            SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return lastSteps;
            Sensor s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (s == null) return lastSteps;

            final SensorEventListener l = new SensorEventListener() {
                public void onSensorChanged(SensorEvent e) {
                    if (e.values != null && e.values.length > 0) lastSteps = (int) e.values[0];
                }

                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL);
            // Give it a moment to deliver the initial value, then let go: holding the
            // registration open for a counter that changes on every footstep would wake the
            // process all day for a number we only send every ten minutes.
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sm.unregisterListener(l);
        } catch (Throwable t) {
            Log.w(TAG, "no step count", t);
        }
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

    /** Local time in the shape {@code APJK} carries it. */
    public static String stamp() {
        Calendar cal = Calendar.getInstance();
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
    public static float temperature(Context c) {
        try {
            SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return lastTemp;
            Sensor found = null;
            for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
                String n = s.getName();
                if (n != null && n.toLowerCase(Locale.US).indexOf("temperature") >= 0) {
                    found = s;
                    break;
                }
            }
            if (found == null) return lastTemp;

            final SensorEventListener l = new SensorEventListener() {
                public void onSensorChanged(SensorEvent e) {
                    if (e.values == null || e.values.length == 0) return;
                    float v = e.values[0];
                    // Accept either scale: some builds report degrees directly. A body reading
                    // is never 2771 degrees and never 0.3, so the magnitude disambiguates it
                    // without having to know which firmware is underneath.
                    if (v > 100f) v = v / 100f;
                    if (v > 20f && v < 45f) lastTemp = v;
                }

                public void onAccuracyChanged(Sensor s, int a) {
                }
            };
            sm.registerListener(l, found, SensorManager.SENSOR_DELAY_NORMAL);
            try {
                Thread.sleep(800);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sm.unregisterListener(l);
        } catch (Throwable t) {
            Log.w(TAG, "no temperature reading", t);
        }
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
