package org.watchlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-arms sleep tracking after a reboot.
 *
 * The alarm that drives the sampling does not survive a restart, and the watch
 * reboots more often than anyone opens the launcher. Without this, tracking
 * would quietly stop the first time the battery ran out and stay stopped --
 * which is the failure mode of every "just leave it running" feature that has
 * nothing to restart it.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent in) {
        if (!SleepLog.enabled(c)) return;

        boolean rebooted = in == null || in.getAction() == null
                || Intent.ACTION_BOOT_COMPLETED.equals(in.getAction());

        if (rebooted) {
            // Watching cadence, not logging: whatever state it was in before
            // the reboot, the wrist has certainly moved since.
            SleepLog.setState(c, SleepLog.WATCHING);
        }
        // An update, though, is not a reason to decide someone woke up - the
        // watch is on a wrist that has not moved just because a new build
        // landed on it. Only the alarm needs re-arming, and it needs it
        // urgently: replacing a package cancels every alarm it owned.
        SleepService.schedule(c, rebooted ? 15000 : 5000);
    }
}
