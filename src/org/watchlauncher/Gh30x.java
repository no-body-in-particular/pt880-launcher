package org.watchlauncher;

import android.util.Log;

/**
 * Start the sensor ourselves, in the mode that produces an SpO2.
 *
 * The measurement has always been started by com.ic.work, which starts it through the sensor
 * HAL - and the HAL is the piece that wedges. It stops delivering, its last= freezes at the
 * final triple it managed, the service is handed nothing and reports zeros, and only restarting
 * com.ic.work clears it. Three times in two days, each time costing hours of blood pressure and
 * SpO2 while the driver went on producing a pulse perfectly well.
 *
 * The way out was in the vendor's own library the whole time. libICJniUtils.so exports
 * enableSPO2 as a GLOBAL symbol, but has no Java_com_ic_jni_ICJniUtils_ wrapper for it, so it
 * cannot be reached from Java however the class is declared. enablePPG is wrapped, and starts
 * the chip in heart rate mode only - "event ppg 59 , spo2 0 , weared 1" on every sample of a
 * full window, which is what made com.ic.work look indispensable.
 *
 * native/gh30x.c is a shim that dlopens that library and calls the two entry points that have
 * no wrapper. Nothing about the hardware is reimplemented: the vendor's code does the work.
 *
 * With this, the whole measurement avoids the HAL - started here, heart rate and SpO2 read from
 * /dev/input/event1, pressures from getHighBloodPressure which is already wrapped. There is
 * nothing left in the path that can wedge.
 *
 * Fails soft in every direction. A missing library, a build whose symbols differ, a chip that
 * refuses to start: {@link #available()} is false and the caller asks com.ic.work as before.
 */
final class Gh30x {

    private static final String TAG = "Gh30x";

    private static final boolean LOADED = load();

    private static boolean load() {
        try {
            System.loadLibrary("gh30x");
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "libgh30x.so did not load (" + t + "); the service will start "
                    + "measurements as before");
            return false;
        }
    }

    private Gh30x() { }

    private static native boolean available();
    private static native int enableSpo2();
    private static native int disablePpg();
    private static native int[] report();

    /**
     * The driver's own reading, or null.
     *
     * Six words out of _IOR('G', 11, 24). The library's log line names five of them - "is wared
     * %d , ppg %d , spo2 %d , bph %d , bpl %d" - and the sixth is unaccounted for, so the whole
     * lot is logged the first few times rather than trusted: a field order worked out from a
     * format string is a good guess, not a measurement.
     */
    static int[] read() {
        if (!LOADED) return null;
        try {
            int[] r = report();
            if (r != null && logged < LOG_FIRST) {
                logged++;
                StringBuilder b = new StringBuilder("driver report:");
                for (int i = 0; i < r.length; i++) b.append(' ').append(r[i]);
                b.append("   (expected: worn, ppg, spo2, bph, bpl, ?)");
                Log.i(TAG, b.toString());
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int logged;
    private static final int LOG_FIRST = 6;

    /** Whether the vendor library is present and still has the symbols this needs. */
    static boolean usable() {
        if (!LOADED) return false;
        try {
            return available();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Start a measurement in SpO2 mode. True if the chip took it.
     *
     * The vendor's function returns its own status; anything negative is a refusal, and the two
     * this shim adds are worth telling apart in the log: -1 is the library not loading at all,
     * -2 is it loading without the symbol, which means a different build of the vendor app and
     * not something a retry will fix.
     */
    static boolean start() {
        if (!usable()) return false;
        try {
            int rc = enableSpo2();
            if (rc < 0) {
                Log.w(TAG, "enableSPO2 refused: " + (rc == -1 ? "the vendor library would not "
                        + "load" : rc == -2 ? "no enableSPO2 in this build of it"
                        : "returned " + rc));
                return false;
            }
            Log.i(TAG, "started the sensor in SpO2 mode without the service");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not start the sensor", t);
            return false;
        }
    }

    /** Stop it. Always worth calling: a chip left running is a lit LED and a flat battery. */
    static void stop() {
        if (!LOADED) return;
        try {
            disablePpg();
        } catch (Throwable t) {
            Log.w(TAG, "could not stop the sensor", t);
        }
    }
}
