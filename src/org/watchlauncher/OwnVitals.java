package org.watchlauncher;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Vitals measured by our own code rather than the vendor's.
 *
 * <h3>Why a socket</h3>
 *
 * The measurement needs {@code /dev/gh_tools}, the vendor daemon stopped, a blocking ioctl and
 * the better part of a minute. The daemon has to be stopped because two drivers on one chip
 * fight over it, and that needs root - which this process does not have: {@code wsu} gives a root
 * shell to the Terminal but not to the launcher, and {@link RootShell}'s twenty second timeout is
 * shorter than the measurement regardless.
 *
 * So the privileged half runs as {@code vitalsd}, started by init in the slot the vendor daemon
 * used to occupy, and this only speaks to a socket. Ask for a reading, get one line back:
 *
 * <pre>
 *   -&gt; hr
 *   &lt;- hr=49 spread=3 hz=24.9 ... spo2=92 sbp=102 dbp=66
 *   &lt;- hr=0 reason=no_agreement ...      when nothing trustworthy came out
 * </pre>
 *
 * <h3>What is trusted, and what is not</h3>
 *
 * The heart rate is checked against the vendor's on the same wrist minutes apart - 46 through 52
 * against its 49 - and is published.
 *
 * SpO2 is anchored on the vendor's own ratio rather than the textbook fingertip formula, which
 * gave 81% where the watch said 100. One anchor fixes the offset and not the slope, so it tracks
 * a change rather than measuring saturation outright.
 *
 * A pressure is only returned when the pulse shape behind it is physiological - an upstroke
 * between 80 and 250 ms and a positive augmentation index. It is uncalibrated until a cuff says
 * otherwise, and the helper reports nothing rather than a plausible-looking number when the shape
 * is wrong. That distinction is the entire point of docs/vitals.md.
 */
final class OwnVitals {

    private static final String TAG = "OwnVitals";

    /** Abstract-namespace socket published by vitalsd. */
    private static final String SOCKET = "watchvitals";

    /** The daemon answers when the measurement is done, so this has to outlast it. */
    private static final int TIMEOUT_MS = 70000;

    private OwnVitals() { }

    /** One measurement, or null if nothing trustworthy came out. */
    static VendorVitals.Reading measure(Context ctx, boolean wantSpo2) {
        String line = ask(wantSpo2 ? "spo2" : "hr");
        if (line == null) return null;

        int bpm = field(line, "hr=");
        if (bpm < 30 || bpm > 210) {
            Log.i(TAG, "no reading: " + line.trim());
            return null;
        }

        VendorVitals.Reading r = new VendorVitals.Reading();
        r.fromOwn = true;
        r.heartRate = bpm;

        int ox = field(line, "spo2=");
        if (ox >= 70 && ox <= 100) r.oxygen = ox;

        double temp = dfield(line, "temp=");
        if (temp > 20.0 && temp < 45.0) r.temperature = temp;

        int sbp = field(line, "sbp=");
        int dbp = field(line, "dbp=");
        // Both or neither: half a pressure is not a reading. The helper zeroes both when the
        // pulse shape it would have come from is not physiological.
        if (sbp >= 70 && sbp <= 200 && dbp >= 40 && dbp <= 130 && sbp > dbp) {
            r.systolic = sbp;
            r.diastolic = dbp;
        }

        Log.i(TAG, "own measurement: " + r + "  [" + line.trim() + "]");
        return r;
    }

    /** Send one request to vitalsd and return its reply, or null. */
    private static String ask(String request) {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(TIMEOUT_MS);

            OutputStream out = s.getOutputStream();
            out.write((request + "\n").getBytes("UTF-8"));
            out.flush();

            InputStream in = s.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                if (sb.indexOf("\n") >= 0) break;
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable t) {
            // Not an error worth shouting about: vitalsd may not be installed on every build,
            // and the caller falls back to the vendor path.
            Log.i(TAG, "vitalsd unavailable (" + t + ")");
            return null;
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * Is the watch on a wrist? 1 yes, 0 no, -1 nothing to go on.
     *
     * A second and no LED time, against the forty-five seconds a measurement spends failing off
     * the wrist. -1 means the thermometer would not answer, and the caller should measure rather
     * than assume either way.
     */
    static int worn(Context ctx) {
        String line = ask("wear");
        if (line == null) return -1;
        return field(line, "worn=");
    }

    /** Read {@code name=<decimal>} out of the reply, or -1. */
    static double dfield(String line, String name) {
        if (line == null) return -1;
        int at = line.indexOf(name);
        if (at < 0) return -1;
        int i = at + name.length(), start = i;
        while (i < line.length()
                && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.')) i++;
        if (i == start) return -1;
        try {
            return Double.parseDouble(line.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Read {@code name=<integer>} out of the reply, or -1. */
    static int field(String line, String name) {
        if (line == null) return -1;
        int at = line.indexOf(name);
        if (at < 0) return -1;
        int i = at + name.length(), v = 0, digits = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            v = v * 10 + (line.charAt(i) - '0');
            i++;
            digits++;
        }
        return digits == 0 ? -1 : v;
    }
}
