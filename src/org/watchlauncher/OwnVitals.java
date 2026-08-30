package org.watchlauncher;

import android.content.Context;
import android.util.Log;

/**
 * A heart rate measured by our own code rather than the vendor's.
 *
 * <h3>What this replaces, and what it does not</h3>
 *
 * {@link VendorVitals} asks {@code gh3011_service} for a reading and takes what it is given.
 * This drives the GH3011 directly: it powers the chip, replays the start sequence for the green
 * LED, waits on the chip's own interrupt and reads its FIFO. No vendor code is involved in the
 * measurement, and the number that comes out has been checked against the vendor's on the same
 * wrist minutes apart - 47, 50, 52 against its 49.
 *
 * Only the heart rate. Not SpO2, and not blood pressure:
 *
 * <ul>
 *   <li>SpO2 needs a ratio of ratios across a red and an infrared channel. In green mode the
 *       second channel is not infrared, and the ratio comes out between 1.5 and 3.5 where a real
 *       one is 0.4 to 1.0. A percentage derived from that would be invented.
 *   <li>Blood pressure needs calibration against a cuff. Until that exists, any number is the
 *       intercept of an arbitrary line - which is exactly what the vendor firmware does, and
 *       docs/vitals.md is about why that is worthless.
 * </ul>
 *
 * Both are left to the vendor path, which really does produce them.
 *
 * <h3>Why it shells out</h3>
 *
 * The measurement needs {@code /dev/gh_tools}, root, a blocking ioctl and about forty seconds of
 * sampling. That belongs in a small native program, not on a Dalvik thread, and the vendor daemon
 * has to be stopped for the duration because both would otherwise drive the same chip. The helper
 * always restores it, including if it is killed.
 */
final class OwnVitals {

    private static final String TAG = "OwnVitals";

    /** Where install-launcher.sh puts the helper. */
    private static final String HELPER = "/data/local/tmp/ppgd";

    /** Sampling seconds. Long enough for several independent windows to agree, short enough
     *  that the LED is not lit on a wrist for any longer than the reading needs. */
    private static final int SECONDS = 40;

    private OwnVitals() { }

    /**
     * One measurement, or null if nothing trustworthy came out.
     *
     * A refusal is a normal outcome, not an error: the helper declines when its windows disagree,
     * which is what movement looks like. Returning null lets the caller fall back rather than
     * publishing a rate that came from a moving wrist.
     */
    static VendorVitals.Reading measure(Context ctx) {
        RootShell sh = new RootShell();
        try {
            if (!sh.open() || !sh.isRoot()) {
                Log.w(TAG, "no root shell; leaving the measurement to the vendor path");
                return null;
            }

            // Stop the daemon for the duration: two drivers on one chip is not a race worth
            // having. Started again below whatever happens.
            sh.runQuiet("setprop ctl.stop gh3011_daemon");
            sh.runQuiet("sleep 2");

            String out;
            try {
                out = sh.exec(HELPER + " " + SECONDS + " \"\" hr");
            } finally {
                sh.runQuiet("setprop ctl.start gh3011_daemon");
            }

            int bpm = parseHr(out);
            if (bpm <= 0) {
                Log.i(TAG, "no reading: " + firstLine(out));
                return null;
            }
            VendorVitals.Reading r = new VendorVitals.Reading();
            r.heartRate = bpm;
            // Deliberately zero. The caller treats a zero as "not measured", which is true.
            r.oxygen = 0;
            r.systolic = 0;
            r.diastolic = 0;
            Log.i(TAG, "own measurement: " + bpm + " bpm (" + firstLine(out) + ")");
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "own measurement failed", t);
            return null;
        } finally {
            try { sh.close(); } catch (Throwable ignored) { }
        }
    }

    /** Pull the rate out of the helper's single line: {@code hr=49 spread=2 hz=24.9 ...}. */
    static int parseHr(String out) {
        if (out == null) return 0;
        int at = out.indexOf("hr=");
        if (at < 0) return 0;
        int i = at + 3, v = 0, digits = 0;
        while (i < out.length() && Character.isDigit(out.charAt(i))) {
            v = v * 10 + (out.charAt(i) - '0');
            i++;
            digits++;
        }
        if (digits == 0) return 0;
        // The helper prints hr=0 with a reason when it will not stand behind an answer, and a
        // plausible range is the last guard against a parse that found something else.
        return (v >= 30 && v <= 210) ? v : 0;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.trim();
    }
}
