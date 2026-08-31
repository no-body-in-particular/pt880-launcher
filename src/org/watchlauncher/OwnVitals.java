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
 * used to occupy, and this only speaks to a socket. That half lives in its own repository, checked
 * out here as the {@code vitals} submodule - it drives the chip, and this class knows nothing
 * about the sensor beyond the three words it can ask for. Ask for a reading, get one line back:
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

    /**
     * The daemon answers when the measurement is done, so this has to outlast it.
     *
     * Two minutes because the red request is now two passes: 25 seconds balanced for the ratio,
     * then 45 for the rate and the pressure, plus the re-read and the chip settling between them
     * - about eighty seconds in total. It was seventy, set when the whole thing was one 45 second
     * pass, and lengthening the measurement without lengthening this meant every reading timed
     * out just before it was ready.
     */
    private static final int TIMEOUT_MS = 120000;

    /** The thermometer answers in about a second and needs no measurement, so it gets its own
     *  patience. Waiting two minutes to be told nobody is wearing the watch is the opposite of
     *  what the check is for. */
    private static final int WEAR_TIMEOUT_MS = 15000;

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

        // spo2rel, not spo2. The daemon stopped emitting an absolute saturation once R was shown
        // to drift from 0.32 to 0.98 over eight hours on a motionless wrist - a threefold change
        // in the ratio the whole method rests on, which makes any single anchor wrong by several
        // points within a day.
        //
        // What it emits instead is a movement away from this sensor's own recent baseline, on
        // the reasoning that the drift is slow and a desaturation is not: an apnoea lasts tens of
        // seconds and the instrument takes hours to wander that far. So a fall is real and worth
        // seeing. The absolute number is an assumption - 97 for a healthy adult at rest - and is
        // not a measurement of anyone's saturation. docs/vitals.md is explicit about this.
        int ox = field(line, "spo2rel=");
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

    private static String ask(String request) {
        return ask(request, TIMEOUT_MS);
    }

    /** Send one request to vitalsd and return its reply, or null. */
    private static String ask(String request, int timeoutMs) {
        LocalSocket s = new LocalSocket();
        try {
            // The timeout goes on before the connect, not after it.
            //
            // vitalsd serves one request at a time behind a backlog of four, so a connect made
            // while it is busy waits rather than failing. Setting the timeout afterwards meant
            // the connect itself had none, and a thread that blocked there blocked forever -
            // taking the measuring flag with it, which is only cleared in a finally that never
            // ran. One stuck thread then stopped every later cycle: three hours of readings
            // went missing that way.
            s.setSoTimeout(timeoutMs);
            s.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(timeoutMs);

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
        String line = ask("wear", WEAR_TIMEOUT_MS);
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
