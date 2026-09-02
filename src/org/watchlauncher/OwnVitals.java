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
 * against its 49 - and is published. Wear comes from the sensor's own detector rather than the
 * thermopile: about a second, and it detects a wrist rather than warmth.
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

    /** The wear check answers in a few seconds and lights no LED for long, so it gets its own
     *  patience. Waiting two minutes to be told nobody is wearing the watch is the opposite of
     *  what the check is for. */
    private static final int WEAR_TIMEOUT_MS = 15000;

    private OwnVitals() { }

    /** One measurement, or null if nothing trustworthy came out. */
    static Vitals measure(Context ctx, boolean wantSpo2) {
        String line = ask(wantSpo2 ? "spo2" : "hr");
        if (line == null) return null;

        int bpm = field(line, "hr=");
        if (bpm < 30 || bpm > 210) {
            Log.i(TAG, "no reading: " + line.trim());
            return null;
        }

        Vitals r = new Vitals();
        r.heartRate = bpm;

        // spo2rel, not spo2, and it is already a percentage: the daemon publishes
        // 97 - 25*(R - baseline), clamped at 100, where 97 is SPO2_ASSUMED_REST in vitalsd.c.
        //
        // It is named "rel" because of how it is arrived at rather than what it is. An absolute
        // figure from R alone is not available - R drifted from 0.32 to 0.98 over eight hours on
        // a motionless wrist, a threefold change in the ratio the whole method rests on - so the
        // slow part is treated as this sensor's baseline and subtracted, and the textbook slope
        // is applied to what is left. A fall is real and worth acting on; the anchor is an
        // assumption about a healthy adult at rest, not a measurement of anyone's saturation.
        // docs/vitals.md is explicit about this and the chart should be read in that light.
        //
        // Taken at whatever the daemon publishes rather than re-checked against a range here.
        // vitalsd clamps above 100 and reports nothing below 70 - further from the baseline than
        // that is the sensor rather than the blood - and a second threshold in this file could
        // only disagree with the first.
        // spo2= if the daemon could produce one, spo2rel= otherwise.
        //
        // spo2= is an actual saturation now rather than a movement away from a baseline. It uses
        // the vendor's own curve, 110 - 25R, read out of their binary - FUN_00020040 evaluates
        // the quadratic and FUN_0001f8c0 fills its coefficients with 0, -25 and 110 - applied to
        // the ratio averaged across passes rather than to one measurement, which is also what
        // they do.
        //
        // It appears only when the pulse behind it was big enough to divide by, which on this
        // sensor is a minority of measurements: perfusion at the wrist varies by more than a
        // factor of ten across a day, and below about thirty counts of pulse a single ADC count
        // moves the ratio further than a six point desaturation does. spo2rel is the fallback
        // and says what it always said - a movement from this sensor's own recent baseline.
        // spo2abs=, not spo2=. ppgd emits a spo2= of its own, always zero, earlier in the same
        // line - and field() takes the first match, so reading spo2= here would have found that
        // zero every time and silently fallen through to the relative value. The same collision
        // wrote a sleep sample's count as the gain until yesterday.
        // spo2abs is deliberately not read.
        //
        // It was, and it displayed 86% for a wearer whose fingertip meter read 98 to 99 at the
        // same moment - from three accumulated passes carrying four times the pulse the gate
        // asks for, so not a marginal reading that slipped through. The ratio behind it moves
        // across the whole physiological range between one pass and the next, which averaging
        // makes steady rather than correct. vitalsd no longer emits the field unless SPO2ABS is
        // set, and this does not look for it either: two independent places to switch it on is
        // the right number for a figure that alarming.
        int ox = field(line, "spo2rel=");
        if (ox > 0 && ox <= 100) r.oxygen = ox;

        // A wrist, converted to a body. vitalsd says so on the line it sends - it measures the
        // thermopile and leaves the conversion here on purpose - and this did not do it, so
        // every vitals cycle reported the wrist reading as a body temperature. Against the
        // converted path on the same watch on the same afternoon that was 31 to 35 where the
        // other said 36 and a half; a wrist at 33 is an ordinary wrist and a body at 33 is
        // hypothermia, and the chart showed the second.
        //
        // Nothing is published when the conversion is not available. It leans on the vendor
        // library, and a plausible-looking number arrived at by adding a constant would be the
        // same mistake in the other direction - the offset is not constant, it is most of what
        // the conversion is for.
        double wrist = dfield(line, "temp=");
        double body = BodyTemp.fromWrist(wrist);
        if (body >= BodyTemp.PERSON_MIN_C && body <= BodyTemp.PERSON_MAX_C) r.temperature = body;

        int sbp = field(line, "sbp=");
        int dbp = field(line, "dbp=");
        // Both or neither: half a pressure is not a reading. The helper zeroes both when the
        // pulse shape it would have come from is not physiological.
        if (sbp >= 70 && sbp <= 200 && dbp >= 40 && dbp <= 130 && sbp > dbp) {
            r.systolic = sbp;
            r.diastolic = dbp;
        }

        recordSleepSample(ctx, line);

        Log.i(TAG, "own measurement: " + r + "  [" + line.trim() + "]");
        return r;
    }

    /**
     * Keep the accelerometer this measurement was already watching.
     *
     * The sleep recorder samples five seconds every five minutes on an alarm of its own, and only
     * writes to the night's log in some states of its cadence machine - so the file the scorer
     * reads holds 119 rows across a fifteen hour night, in clusters with hours between them. That
     * is circular: rows are written once sleep is suspected and sleep is scored from the rows, so
     * a night that starts unnoticed is never recorded to be noticed.
     *
     * A measurement watches the same accelerometer end to end for thirty to eighty seconds, every
     * three minutes, and threw all of it away. Writing it costs no wakeup and no LED - it has
     * already happened - and gives the scorer continuous coverage that does not depend on the
     * recorder having guessed right about when the night began.
     *
     * The axes arrive normalised so a resting wrist reads 1.0, which is what the scorer's angle
     * wants and what makes it independent of this driver's counts per g.
     */
    private static void recordSleepSample(Context ctx, String line) {
        // an=, not n=. A reading carries gain=2323, and "n=" matches inside it - so the sample
        // count came out as the gain, which is a plausible-looking number in a column nobody
        // reads twice. Field names here have to be ones no other field ends with.
        int n = field(line, "an=");
        if (n <= 0) return;

        double ax = dfield(line, "ax=");
        double ay = dfield(line, "ay=");
        double az = dfield(line, "az=");
        double sd = dfield(line, "asd=");
        double enmo = dfield(line, "aenmo=");
        double range = dfield(line, "arange=");

        // All three axes at zero is the buffer never being filled rather than a motionless
        // wearer - gravity alone makes it impossible - and perfect stillness is exactly what the
        // scorer reads as the soundest sleep there is.
        if (ax == 0 && ay == 0 && az == 0) return;
        if (sd < 0 || enmo < 0 || range < 0) return;

        try {
            SleepLog.append(ctx, System.currentTimeMillis(), ax, ay, az, sd, enmo, range, n);
        } catch (Throwable t) {
            Log.w(TAG, "could not record the sleep sample", t);
        }
    }

    private static String ask(String request) {
        return ask(request, TIMEOUT_MS);
    }

    /** Send one request to vitalsd and return its reply, or null. */
    private static String ask(String request, int timeoutMs) {
        LocalSocket s = new LocalSocket();
        try {
            // The timeout goes on before the connect where the platform allows it.
            //
            // vitalsd serves one request at a time behind a backlog of four, so a connect made
            // while it is busy waits rather than failing, and a thread that blocked there used to
            // block for ever - taking the measuring flag with it, which is only cleared in a
            // finally that never ran. Three hours of readings went missing that way.
            //
            // But this platform creates the socket's implementation inside connect(), so setting
            // a timeout first throws "socket not created" and the request never leaves. That
            // turned one hung measurement into every measurement failing instantly: twenty-two
            // hours with nothing logged, and a wear check stuck on "removed" because the pulse
            // it looks for comes from the readings this was refusing to take.
            //
            // So it is attempted and not required. The hang it guards against is covered anyway
            // by TrackerService, which treats a measurement still running after five minutes as
            // lost and starts another.
            try {
                s.setSoTimeout(timeoutMs);
            } catch (Throwable ignored) {
                // No timeout before connect on this platform; the one after it still applies.
            }
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
    /**
     * What the thermometer reads at the skin, in Celsius, or 0 if it will not say.
     *
     * A wrist and not a body -- {@link BodyTemp} converts it, and needs an ambient reading this
     * watch does not have, so the conversion is an approximation the caller should know about.
     *
     * Nothing is lit for this. The daemon reads the same gxts02s thermopile the vendor's library
     * reads, through the temperature input device, and that library is not on this path any
     * more: it was the last thing it was still being used for.
     */
    static double temperature(Context ctx) {
        String line = ask("temp", WEAR_TIMEOUT_MS);
        if (line == null) return 0;
        double t = dfield(line, "temp=");
        return t > 0 ? t : 0;
    }

    static int worn(Context ctx) {
        String line = ask("wear", WEAR_TIMEOUT_MS);
        if (line == null) return -1;

        // worn= is the sensor's own detector now, not the thermometer.
        //
        // The GH3011 has a wear detector and it took a capture of the vendor daemon on the wire
        // to arm it: there is a chip init before the auto-detect table, a second pass that
        // overwrites six of that table's registers afterwards, and a write to 0x0002 before the
        // start. vitals/docs/gh3011.md has the sequence. It answers in about a second where the
        // thermopile needs eight, and it detects a wrist rather than warmth, which a pocket also
        // supplies.
        //
        // The daemon still decides which source to use and reports both. adt= is the detector on
        // its own: -1 there means it could not run and the thermometer answered instead, which is
        // worth seeing in the log because it is the case where a reading got slow again.
        int worn = field(line, "worn=");
        int adt = field(line, "adt=");
        if (adt < 0) Log.i(TAG, "wear fell back to the thermometer  [" + line.trim() + "]");
        return worn;
    }

    /**
     * Steps since the counter was last reset, or -1 if the daemon could not say.
     *
     * Read off the i2c bus rather than through SensorManager, which has never produced a number
     * on this watch - see TrackerSources.steps for why. A cheap question: no LED, no measurement,
     * two bytes.
     */
    static int steps(Context ctx) {
        String line = ask("steps", WEAR_TIMEOUT_MS);
        if (line == null) return -1;
        return field(line, "steps=");
    }

    /**
     * One accelerometer burst, as the sums a sleep burst reduces to, or null.
     *
     * The daemon samples the DA217 directly for {@code ms} and returns n, the per-axis sums, the
     * magnitude sums and the extremes - the nine numbers the recorder builds anyway. Reading the
     * part here rather than through SensorManager is what makes the vendor's driver removable:
     * it owns the same chip and delivers nothing at all for the step half of it.
     */
    static String accelBurst(Context ctx, int ms) {
        // Long enough for the burst itself plus the daemon being busy with something else.
        return ask("accel " + ms, ms + 10000);
    }

    /** Read {@code name=<decimal>} out of the reply, or -1. */
    static double dfield(String line, String name) {
        if (line == null) return -1;
        int at = line.indexOf(name);
        if (at < 0) return -1;
        int i = at + name.length(), start = i;
        // A leading minus, because an axis sum is negative whenever the wrist is the other way
        // up. Without this every such field parsed as -1 and a burst read as motionless.
        if (i < line.length() && line.charAt(i) == '-') i++;
        int digits = i;
        while (i < line.length()
                && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.')) i++;
        // Count the digits rather than compare against start: a consumed minus sign would
        // otherwise look like progress and "sx=-" would parse as a number.
        if (i == digits) return -1;
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
