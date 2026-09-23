package org.watchlauncher;

import android.view.KeyEvent;

import java.io.File;
import java.io.FileWriter;

/**
 * One line per key event the activity is handed, on the card rather than in the log.
 *
 * This button has now been diagnosed wrong five times, and every wrong diagnosis was a theory
 * about which events arrive rather than a record of it: auto-repeat that the key does not do,
 * a virtual-key quiet time that is zero, a pointer setting belonging to gestures, a real key
 * the app was not receiving because the layout it needed did not exist yet. The one fix that
 * held came from a getevent capture, and that reads the driver - it cannot see the code the
 * framework makes up, which is the half that actually breaks things.
 *
 * So the activity writes down what it is given. Device -1 is the system inventing an event;
 * any other id is a real one, and the scan code says which physical key. A press that produced
 * one tap and a press that produced none or two are then a diff rather than an argument.
 *
 * A file and not {@code Log.i} because logcat is useless here: the GPS layer emits thousands of
 * lines a minute and the buffer rotates within minutes, and three diagnostics have already been
 * lost that way and read as "the code never ran".
 */
public final class KeyLog {

    private static final String PATH = SleepLog.DIR + "/keys.csv";

    /** Keep it small: this is a probe, not a record worth a night's disk. */
    private static final long MAX_BYTES = 256 * 1024;

    private KeyLog() { }

    public static synchronized void append(String what, int keyCode, KeyEvent e) {
        FileWriter w = null;
        try {
            File dir = new File(SleepLog.DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(PATH);
            if (f.length() > MAX_BYTES) return;
            boolean fresh = !f.exists();
            w = new FileWriter(f, true);
            if (fresh) {
                w.write("# millis,what,keyCode,scanCode,deviceId,repeat,flags,downTime\n");
            }
            w.write(System.currentTimeMillis() + "," + what + "," + keyCode + ","
                    + (e == null ? -1 : e.getScanCode()) + ","
                    + (e == null ? -1 : e.getDeviceId()) + ","
                    + (e == null ? -1 : e.getRepeatCount()) + ","
                    + (e == null ? -1 : e.getFlags()) + ","
                    + (e == null ? -1 : e.getDownTime()) + "\n");
        } catch (Exception ex) {
            // a diagnostic; losing it must not cost the press
        } finally {
            try { if (w != null) w.close(); } catch (Exception ex) { /* ignore */ }
        }
    }
}
