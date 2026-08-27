package org.watchlauncher;

import android.content.Context;
import android.util.Log;

/**
 * Make the tracker the last thing on the watch worth reclaiming.
 *
 * <h3>Honest about what this is</h3>
 *
 * Insurance, not a diagnosis. It was written to stop the low memory killer taking the
 * tracker, and then the first kill report that carried memory figures ruled that out:
 * MemFree 151668 kB and SwapFree 343656 kB at the moment a process died, with no am_kill,
 * lowmemory or "to free" anywhere in the log. The watch is not short of memory and the killer
 * is not what is taking these processes.
 *
 * It is kept because pinning the score is cheap, has no effect when memory is plentiful, and
 * the tracker is genuinely the process this device can least afford to lose. It is not kept
 * because it was shown to fix anything, and it should not be cited as the fix if the tracker
 * stops dying - something else changed at the same time.
 *
 * <h3>Why losing the tracker costs so much</h3>
 *
 * The vendor's tracker, com.enqualcomm.support, is what holds the connection to the server.
 * It is an ordinary app as far as Android is concerned, so anything that reclaims processes
 * takes it like any other - and this launcher, being the home activity, survives.
 *
 * Losing it costs more than losing an app. While it is gone the watch says nothing to the
 * server, so the server cannot poll it and cannot send the reboot that clears a stalled
 * sensor. A stall that would have been recovered in half an hour instead runs until the
 * tracker happens to come back: one measured at 4225 seconds against a 1800 second timeout,
 * because for most of that hour there was nothing listening.
 *
 * <h3>What this does</h3>
 *
 * Pins its oom_score_adj to the value Android gives its own persistent processes, so anything
 * reclaiming looks elsewhere first. That is a fair description of what the tracker is on this
 * device - it is the reason the watch exists - and it was only unprotected because the vendor
 * shipped it as a normal app.
 *
 * Re-applied rather than set once. The score lives on the process, so it is lost the moment
 * the tracker is killed and restarted, which is exactly the case this is for.
 *
 * <h3>What it deliberately leaves alone</h3>
 *
 * com.ic.work. That one holds the sensor queue that stalls, and restarting it is how
 * {@link PpgWatchdog} clears a stall - protecting it would make the watch harder to fix, not
 * easier. It is also cheap to lose: Android brings it back and the next alarm finds a working
 * service.
 *
 * <h3>Why not android:persistent</h3>
 *
 * That is the proper fix and it needs the tracker's manifest changed, which means repacking a
 * system app. Editing that app's bytecode is what caused a bad morning already, and this gets
 * the same protection from outside without touching it.
 */
public class Guard {

    private static final String TAG = "Guard";

    /** The process that holds the server connection. */
    private static final String TRACKER = "com.enqualcomm.support";

    /**
     * What Android gives its own persistent system processes. Low enough that ordinary apps
     * are taken first, not so low that it outranks the parts of the system that have to
     * survive for the watch to be a watch at all.
     */
    private static final int PROTECTED_ADJ = -800;

    private Guard() {
    }

    /**
     * Pin the tracker's score, if this watch has root. Returns true if the command ran.
     *
     * Quiet about failing. Without the root helper there is nothing to do here, and that is a
     * watch that works slightly worse rather than a fault worth reporting every five minutes.
     */
    public static boolean protectTracker(Context context) {
        RootShell shell = null;

        try {
            shell = new RootShell();

            if (!shell.open() || !shell.isRoot()) {
                return false;
            }

            //Matched on the last field rather than with grep, so this cannot pick up the
            //pipeline's own processes or a package whose name merely contains the tracker's -
            //writing a protective score onto the wrong pid is worse than writing none.
            //
            //oom_score_adj is the current interface and oom_adj the older one; both exist on
            //this build and which one the kernel honours is not worth finding out from here,
            //so both are written and failures are ignored.
            String out = shell.exec(
                    "for p in $(ps | awk '$NF==\"" + TRACKER + "\" {print $2}'); do "
                    + "echo " + PROTECTED_ADJ + " > /proc/$p/oom_score_adj 2>/dev/null; "
                    + "echo -16 > /proc/$p/oom_adj 2>/dev/null; "
                    + "cat /proc/$p/oom_score_adj 2>/dev/null; done");

            if (out != null && out.trim().length() > 0) {
                Log.i(TAG, "tracker oom_score_adj now " + out.trim());
                return true;
            }

            //No output means no such process. The tracker is dead at this moment, which is
            //the thing this exists to prevent and is worth a line - it will be protected on
            //the next pass, once Android has brought it back.
            Log.i(TAG, "tracker is not running; nothing to protect yet");
            return false;

        } catch (Throwable t) {
            Log.w(TAG, "could not protect the tracker", t);
            return false;

        } finally {
            try {
                if (shell != null) {
                    shell.close();
                }

            } catch (Throwable t) {
                //ignore
            }
        }
    }
}
