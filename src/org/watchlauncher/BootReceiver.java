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
        // Watching cadence, not logging: whatever state it was in before the
        // reboot, the wrist has certainly moved since.
        SleepLog.setState(c, SleepLog.WATCHING);
        SleepService.schedule(c, 15000);
    }
}
