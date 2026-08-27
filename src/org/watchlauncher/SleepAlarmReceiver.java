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
 *
 * <p>It carries the pulse watchdog's alarm too, which is why the action is
 * checked first. That check is not decoration: the sleep burst bails out when
 * sleep logging is switched off, and without the branch it would swallow the
 * watchdog's alarm along with it - leaving the pulse fallback silently dead for
 * anyone who does not log their sleep.
 */
public class SleepAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent in) {
        if (in != null && PpgWatchdog.ACTION_CHECK.equals(in.getAction())) {
            PpgWatchdog.check(c);
            return;
        }

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
