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

    /**
     * How many satellites a fix was made from.
     *
     * Out of the fix's own extras, where the gps provider puts it, rather than out of a
     * GpsStatus listener. Two reasons. GpsStatus only reports while a listener is registered,
     * so a fix that arrives any other way -- last known, or a window that has since closed --
     * gets a count of zero next to real coordinates, which is what the server saw: a valid
     * position reporting 000 satellites. And the count then belongs to whatever the receiver
     * was doing at frame time rather than to the fix being sent.
     *
     * The extras travel with the Location, so the number always describes the fix beside it.
     */
    static int satellitesOf(Location l) {
        if (l == null) return 0;
        try {
            android.os.Bundle x = l.getExtras();
            if (x == null) return 0;
            int n = x.getInt("satellites", 0);
            return (n > 0 && n < 100) ? n : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Signal as a percentage, kept up to date by the listener {@link #watchSignal} starts. */
    private static volatile int lastSignal = 0;

    public static int signal() { return lastSignal; }

    private static void postToMain(Runnable r) {
        try {
            new android.os.Handler(Looper.getMainLooper()).post(r);
        } catch (Throwable ignored) { }
    }

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
    /** Held for the life of the process: PhoneStateListener is not kept alive by listen(). */
    private static android.telephony.PhoneStateListener signalListener;

    public static synchronized void watchSignal(final Context c) {
        if (signalListener != null) return;
        signalListener = new android.telephony.PhoneStateListener() {
            public void onSignalStrengthsChanged(android.telephony.SignalStrength s) {
                int pct = percent(s);
                if (pct >= 0) lastSignal = pct;
            }
        };
        try {
            new android.os.Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    try {
                        TelephonyManager tm = (TelephonyManager)
                                c.getSystemService(Context.TELEPHONY_SERVICE);
                        if (tm == null) return;
                        tm.listen(signalListener,
                                android.telephony.PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
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
     * Signal as a percentage, from whichever of the radio's numbers exist.
     *
     * getGsmSignalStrength is the documented one and it is useless here: this watch is on LTE,
     * where it returns 99 for "unknown", which is why the field went out as 000. The LTE
     * numbers are there but hidden at this API level, so they are reached by reflection, newest
     * first, and the GSM asu is the last resort rather than the first choice.
     *
     * RSRP runs about -140 dBm at the edge of usable to -44 at the mast.
     */
    private static int percent(android.telephony.SignalStrength s) {
        if (s == null) return -1;
        try {
            int rsrp = (Integer) s.getClass().getMethod("getLteRsrp").invoke(s);
            if (rsrp < 0 && rsrp > -160) {
                return (int) clampi((rsrp + 140) * 100 / 96, 0, 100);
            }
        } catch (Throwable ignored) { /* not LTE, or not exposed */ }
        try {
            int level = (Integer) s.getClass().getMethod("getLevel").invoke(s);
            if (level >= 0 && level <= 4) return level * 25;
        } catch (Throwable ignored) { /* hidden on this build */ }
        try {
            int asu = s.getGsmSignalStrength();
            if (asu >= 0 && asu <= 31) return (int) Math.round(asu * 100.0 / 31.0);
        } catch (Throwable ignored) { }
        return -1;
    }

    private static long clampi(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
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
        // On the main looper, not this thread. addGpsStatusListener builds a Handler on the
        // calling thread, and this one is a bare worker with no Looper, so the call threw
        // every time and was swallowed by the catch -- which is the other half of why the
        // satellite count was always zero.
        postToMain(new Runnable() {
            public void run() {
                try {
                    lm.addGpsStatusListener(sats);
                } catch (Throwable ignored) { }
            }
        });

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
            postToMain(new Runnable() {
                public void run() {
                    try {
                        lm.removeGpsStatusListener(sats);
                    } catch (Throwable ignored) { }
                }
            });
        }

        if (got[0] == null) {
            Log.i(TAG, "no gps fix inside " + (timeoutMs / 1000) + "s");
            return null;
        }
        ourFix = got[0];
        int n = satellitesOf(got[0]);
        if (n > 0) lastSats = n;
        Log.i(TAG, "gps fix: " + got[0].getLatitude() + "," + got[0].getLongitude()
                + " +/-" + got[0].getAccuracy() + "m from " + (n > 0 ? n : lastSats)
                + " satellites");
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
                // Newest by the monotonic clock, for the same reason isFresh uses it: a gps
                // fix and a network fix are timestamped from different clocks, so comparing
                // getTime() across providers picks whichever clock is running ahead.
                long a = ageOf(l);
                if (a < 0) continue;
                if (best == null || a < ageOf(best)) best = l;
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

    /** Whether the standing listener below has been put in place yet. */
    private static volatile boolean stepsListening = false;

    /** Where the count survives a restart of this process. */
    private static final String KEY_STEPS = "client_last_steps";

    /**
     * Keep a listener on the step counter for as long as the process lives.
     *
     * The counter reports once on registration and then only when it changes. A five second
     * one-shot therefore answers only if a step happens to be taken inside those five seconds,
     * and on a still wrist it returns nothing at all -- which {@link #steps} then reported as
     * whatever it last saw, which after a restart is zero. That is what put a hardcoded-looking
     * zero in every heartbeat for five days: the sensor was not broken and the wearer was not
     * still, the window was just never open at the right moment.
     *
     * A standing registration has no such window. The counter runs in hardware whether anyone
     * is listening or not, so this costs a callback per step and nothing else.
     */
    private static synchronized void listenForSteps(Context c) {
        if (stepsListening) return;
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;

        Sensor found = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (found == null) {
            // By name, because this watch's counter is a DA217 with a private type number and
            // getDefaultSensor is useless on it.
            List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
            for (int i = 0; all != null && i < all.size(); i++) {
                String n = all.get(i).getName();
                if (n != null && n.toLowerCase(Locale.US).indexOf("step") >= 0) {
                    found = all.get(i);
                    break;
                }
            }
        }
        if (found == null) {
            Log.w(TAG, "no step counter on this device, by type or by name");
            return;
        }

        final Context app = c.getApplicationContext();
        boolean ok = false;
        try {
            ok = sm.registerListener(new SensorEventListener() {
                public void onSensorChanged(SensorEvent e) {
                    if (e.values == null || e.values.length == 0) return;
                    record(app, (int) e.values[0]);
                }

                public void onAccuracyChanged(Sensor s, int a) { }
            }, found, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (Throwable t) {
            Log.w(TAG, "could not listen to " + found.getName(), t);
        }

        // And the trigger path, because a standing registration is not enough on its own.
        //
        // dumpsys marks this sensor on-demand, which is Android's word for a one-shot trigger
        // sensor, and those deliver through TriggerEventListener alone: registerListener on one
        // produces nothing, ever, and returns true while doing it - so `ok` above is not evidence
        // that anything will arrive. The same dumpsys shows the counter's last= column at zero,
        // where gh30x_sensor, which has always requested both, carries real numbers. That is the
        // difference between the pulse working and this not.
        //
        // Which of the two the driver honours is not worth deciding from here. HeartRate settled
        // it by asking for both and letting the inert one be inert, and this now does the same.
        // A trigger fires once and disarms, so it re-arms itself in the callback, and steps()
        // re-arms as well whenever nothing has arrived yet - a request lost to a driver that was
        // busy would otherwise be the end of it.
        stepSensor = found;
        stepTrigger = new TriggerEventListener() {
            public void onTrigger(TriggerEvent e) {
                if (e.values != null && e.values.length > 0) record(app, (int) e.values[0]);
                armStepTrigger(app);
            }
        };
        armStepTrigger(app);

        stepsListening = ok || stepTriggerArmed;
        Log.i(TAG, "step counter " + found.getName() + ": listener " + (ok ? "on" : "refused")
                + ", trigger " + (stepTriggerArmed ? "armed" : "refused"));
    }

    /** The counter, kept so re-arming does not have to search the sensor list again. */
    private static volatile Sensor stepSensor;
    private static volatile TriggerEventListener stepTrigger;
    private static volatile boolean stepTriggerArmed = false;

    private static void armStepTrigger(Context app) {
        Sensor s = stepSensor;
        TriggerEventListener t = stepTrigger;
        if (s == null || t == null) return;
        SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        try {
            stepTriggerArmed = sm.requestTriggerSensor(t, s);
        } catch (Throwable ignored) {
            // Not a trigger sensor after all, in which case the standing listener is the one
            // that matters and this costs nothing.
            stepTriggerArmed = false;
        }
    }

    /** One count from either path, kept and persisted if it is new. */
    private static void record(Context app, int n) {
        if (n < 0 || n == lastSteps) return;
        lastSteps = n;
        // Kept, so a restart does not report zero until the wearer next moves. The counter
        // itself is since boot, so this is only ever a floor: a real reboot resets it and the
        // next value will be smaller, which is correct.
        try {
            app.getSharedPreferences("tracker", Context.MODE_PRIVATE)
                    .edit().putInt(KEY_STEPS, n).apply();
        } catch (Throwable ignored) { /* the reading still stands */ }
    }

    /**
     * Steps since boot from the hardware counter.
     *
     * Served from the standing listener. The first call also restores what was last seen before
     * the process restarted, so a heartbeat sent before the wearer has moved carries the count
     * they had rather than a zero.
     */
    public static int steps(Context c) {
        // The daemon first, because the framework has never answered.
        //
        // Both ways of asking the sensor framework were registered - a listener and a trigger -
        // and neither has produced a number in five days. It is not the registration: the input
        // device is enabled and delivered nothing at all across twenty-five seconds of walking,
        // so the driver is where it stops.
        //
        // The chip underneath is counting fine. It is a DA217 at 2-0026 and its 0x0e/0x0f pair
        // rose by 21 over a thirty second walk, holding steady when the wrist is still. Reading
        // it needs root, which this process has not got and vitalsd has - the same reason the
        // measurement lives there.
        //
        // The framework path stays underneath, still registered. If the driver is ever fixed it
        // will start answering and cost nothing in the meantime, and it is the only source that
        // survives vitalsd being absent.
        // Asked for in the background, never on the caller's thread.
        //
        // This is called from inside the heartbeat, which is built on the tracker loop's own
        // thread, and the daemon serves one request at a time behind a measurement that can hold
        // it for eighty seconds. Asking directly stalled that loop: no heartbeat, no position
        // frame, and - because the spool is emptied after both - no readings either, while the
        // measurements themselves carried on filling it. A step count is not worth a minute of
        // the connection's attention, so the last one answers and a refresh runs behind it.
        refreshStepsAsync(c.getApplicationContext());
        if (daemonSteps >= 0) return daemonSteps;

        if (!stepsListening) {
            if (lastSteps == 0) {
                try {
                    lastSteps = c.getSharedPreferences("tracker", Context.MODE_PRIVATE)
                            .getInt(KEY_STEPS, 0);
                } catch (Throwable ignored) { /* nothing kept; zero is the honest answer */ }
            }
            listenForSteps(c);
        } else if (lastSteps == 0) {
            armStepTrigger(c.getApplicationContext());
        }
        return lastSteps;
    }


    /** The daemon's last answer, or -1 before it has given one. */
    private static volatile int daemonSteps = -1;
    private static volatile boolean stepsAsking = false;
    private static volatile long stepsAskedAt = 0;

    /** How stale the count may get before it is worth asking again. */
    private static final long STEPS_REFRESH_MS = 60000;

    private static void refreshStepsAsync(final Context app) {
        long now = System.currentTimeMillis();
        if (stepsAsking || now - stepsAskedAt < STEPS_REFRESH_MS) return;
        stepsAsking = true;
        stepsAskedAt = now;
        new Thread(new Runnable() {
            public void run() {
                try {
                    int n = OwnVitals.steps(app);
                    if (n >= 0) {
                        daemonSteps = n;
                        record(app, n);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "could not read the step counter", t);
                } finally {
                    stepsAsking = false;
                }
            }
        }, "steps").start();
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
                signal(), valid ? satsFor(l) : 0, battery(c),
                cells, aps);
    }

    /** The fix's own count, falling back to the last one the receiver reported. */
    private static int satsFor(Location l) {
        int n = satellitesOf(l);
        return n > 0 ? n : lastSats;
    }

    /**
     * A fix older than this is reported as no-fix.
     *
     * Sending a stale position as current is worse than sending none: the server cannot tell the
     * difference, and a watch that has not moved for an hour looks identical to one whose GPS
     * stopped an hour ago.
     */
    private static final long FIX_MAX_AGE_MS = 30 * 60 * 1000L;

    /**
     * How long ago a fix was taken, in milliseconds, or -1 if it cannot be told.
     *
     * From the monotonic clock, not the wall clock. A GPS fix's getTime() is satellite time,
     * and this watch sets its clock from the network: the two disagree by enough that
     * System.currentTimeMillis() - getTime() came out *negative*, which the old test read as
     * "not fresh" and threw the fix away. The log caught it exactly - a fix at 15 metres from
     * five satellites, and the frame twenty milliseconds later saying V with zeroes.
     *
     * elapsedRealtimeNanos is the same clock at both ends, so it cannot skew.
     */
    private static long ageOf(Location l) {
        if (l == null) return -1;
        try {
            long et = l.getElapsedRealtimeNanos();
            if (et > 0) {
                long age = (android.os.SystemClock.elapsedRealtimeNanos() - et) / 1000000L;
                return age >= 0 ? age : 0;
            }
        } catch (Throwable ignored) { /* pre-17 shape; fall through */ }

        long wall = System.currentTimeMillis() - l.getTime();
        // A small negative is clock skew between the receiver and the system, not a fix from
        // the future. Anything beyond that is a timestamp worth distrusting.
        if (wall < 0) return wall > -FIX_MAX_AGE_MS ? 0 : -1;
        return wall;
    }

    private static boolean isFresh(Location l) {
        long age = ageOf(l);
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
        // The daemon reads the thermopile and this converts what it says. Both halves used to
        // be the vendor's: its library read the sensor, because the platform's own GXTS02S is a
        // mirror stuck at last=<0.0,0.0,0.0>, and its get_bodytemp_from_wristtemp turned a
        // wrist into a body. The conversion was disassembled and reimplemented first, and now
        // the reading comes from vitalsd too, so nothing here is borrowed.
        //
        // This is the path for a TEMP# asked between cycles. Every vitals measurement already
        // carries a temp= of its own and sends it, so when this has nothing the last good
        // reading stands rather than a gap appearing.
        float v = (float) BodyTemp.fromWrist(OwnVitals.temperature(c));
        if (v >= BodyTemp.PERSON_MIN_C && v <= BodyTemp.PERSON_MAX_C) lastTemp = v;
        else if (v > 0f) Log.i(TAG, "temperature " + v + " C is not a body; not reporting it");
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
        // The daemon first, so nothing here needs the vendor's driver.
        //
        // It samples the same chip off the bus and returns the same statistic - a mean absolute
        // deviation of the magnitude, not a standard deviation, so the threshold this feeds keeps
        // its meaning. In g, where the framework reports m/s^2, hence the multiply.
        //
        // The listener below stays for when the daemon is busy: it serves one request at a time.
        String line = OwnVitals.accelBurst(c, Math.max(500, windowMs));
        if (line != null && OwnVitals.field(line, "n=") >= 4) {
            double mad = OwnVitals.dfield(line, "mad=");
            if (mad >= 0) return (float) (mad * 9.80665);
        }

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
