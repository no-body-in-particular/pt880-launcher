package org.watchlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Turns each alarm into one sampling burst.
 *
 * The wake lock is taken here, before the service is started, because the
 * watch is free to go back to sleep the moment this method returns -- and it
 * would, in the gap between starting the service and the service running. The
 * service releases it when the burst is written.
 *
 * The burst itself is not done here: a receiver has about ten seconds before
 * the system considers it stuck, and a five-second sample plus a file write
 * is too close to that line to be worth risking every thirty seconds all
 * night.
 */
public class SleepAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent in) {
        if (!SleepLog.enabled(c)) {
            SleepService.cancel(c);
            return;
        }
        SleepService.holdWakeLock(c);
        try {
            c.startService(new Intent(c, SleepService.class));
        } catch (Exception e) {
            // Nothing to do but let the next alarm try again; the lock times
            // out on its own so a failure here cannot drain the battery.
        }
    }
}
