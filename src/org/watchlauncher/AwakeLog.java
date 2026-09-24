package org.watchlauncher;

import java.io.File;
import java.io.FileWriter;

/**
 * The things that say somebody is awake without asking the accelerometer.
 *
 * Every signal the sleep watcher has is a different way of asking one sensor how the wrist is
 * moving, and on this wearer they have now all been measured against a labelled desk afternoon
 * and a labelled night. None of them separates the two. Movement: the desk is the stiller of
 * the pair. Pulse: the desk is the lower. Arm angle: a factor of five with the distributions
 * overlapping. Posture, and the spread of it over a session: the desk sits inside the range the
 * nights cover. Time of day: this wearer sleeps in the afternoon.
 *
 * What is actually different about a desk is not the wrist, it is that its owner is using
 * things. The screen comes on. Steps get taken, a few at a time, to fetch something. Four hours
 * with neither is not somebody at a desk, whatever their forearm is doing.
 *
 * Neither was being recorded, which is why the idea could not be tested against the nights
 * already on the card and has to start collecting now. This writes them down and nothing reads
 * them yet; when a week of them exists they can be run through WatchReplay against the same
 * labelled sessions as everything else.
 *
 * It is cheap in the way the alternatives are not. The screen broadcast arrives whether anyone
 * listens or not, and the step counter is a separate chip that is already counting - the
 * accelerometer cannot be read continuously on this watch at any sensible cost, because
 * dumpsys reports the QMAX981 as "no batching support", so every sample would have to wake the
 * processor.
 */
public final class AwakeLog {

    private static final String PATH = SleepLog.DIR + "/awake.csv";

    /** A probe, not a record worth a night's disk. Two events a minute for a week is under this. */
    private static final long MAX_BYTES = 2 * 1024 * 1024;

    private AwakeLog() { }

    /**
     * @param what  "screen" or "steps"
     * @param value 1 or 0 for the screen; the increment since the last burst for steps
     */
    public static synchronized void append(String what, long value) {
        FileWriter w = null;
        try {
            File dir = new File(SleepLog.DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(PATH);
            if (f.length() > MAX_BYTES) return;
            boolean fresh = !f.exists();
            w = new FileWriter(f, true);
            if (fresh) w.write("# millis,what,value\n");
            w.write(System.currentTimeMillis() + "," + what + "," + value + "\n");
        } catch (Exception e) {
            // a diagnostic; losing it must not cost anything else
        } finally {
            try { if (w != null) w.close(); } catch (Exception e) { /* ignore */ }
        }
    }
}
