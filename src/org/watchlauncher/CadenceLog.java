package org.watchlauncher;

import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

/**
 * One line per burst, on the card rather than in the log.
 *
 * The night log arrives at about one row every two minutes where it asks for one every thirty
 * seconds, and the gaps fall on multiples of the period - 30, 60, 105, 180 seconds - which says
 * the alarms fire and the bursts run but most of them write nothing. Only two things can refuse a
 * burst: an empty buffer, and the gravity check that was added to catch fabricated rows. This
 * records which, for every burst, so the answer comes from a night rather than from argument.
 *
 * It is a file and not {@code Log.i} because logcat is useless on this watch: the GPS layer emits
 * thousands of lines a minute and the buffer rotates within minutes. Three separate attempts to
 * measure this through logcat came back empty and were read as the code never running.
 */
public final class CadenceLog {

    private static final String PATH = SleepLog.DIR + "/cadence.csv";

    private CadenceLog() { }

    /**
     * @param at      when the burst finished
     * @param startAt when it began
     * @param n       samples it gathered, zero if the buffer came back empty
     * @param mx      mean of each axis, so the gravity check can be second-guessed later
     * @param nextMs  the interval scheduled after it
     * @param state   watching or logging, since the interval means different things in each
     */
    public static synchronized void append(long at, long startAt, int n,
                                           double mx, double my, double mz,
                                           long nextMs, int state) {
        FileWriter w = null;
        try {
            File dir = new File(SleepLog.DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(PATH);
            boolean fresh = !f.exists();
            w = new FileWriter(f, true);
            if (fresh) {
                w.write("# millis,tookMs,n,meanX,meanY,meanZ,nextMs,state,wrote\n");
            }
            boolean wrote = n > 0 && SleepRules.measuredAWrist(mx, my, mz);
            w.write(at + "," + (at - startAt) + "," + n + ","
                    + String.format(Locale.US, "%.5f,%.5f,%.5f", mx, my, mz)
                    + "," + nextMs + "," + state + "," + (wrote ? 1 : 0) + "\n");
        } catch (Exception e) {
            // a diagnostic; losing it must not cost the burst
        } finally {
            try { if (w != null) w.close(); } catch (Exception e) { /* ignore */ }
        }
    }
}
