package org.watchlauncher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Scheduling the alarm clocks the server sets.
 *
 * The reading half is {@link AlarmParse}, which has no android imports so the
 * test can put real frames through it. This half is the part that needs a
 * device: AlarmManager slots, a stored copy, and re-arming.
 */
public final class AlarmClock {

    private static final String TAG = "AlarmClock";

    private static final String KEY_ALARMS = "client_alarms";

    /** Base for the alarm PendingIntents, so each slot can be replaced or cancelled alone. */
    private static final int REQUEST_BASE = 7100;

    private AlarmClock() { }

    /**
     * Replace the whole set.
     *
     * Every slot is cancelled first, including the ones the new set does not fill: the server
     * sends the complete list, so an alarm missing from it has been deleted, and one that keeps
     * ringing because nothing cancelled it is the failure people actually notice.
     */
    public static synchronized void apply(Context c, List<AlarmParse.Alarm> alarms) {
        if (c == null) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        for (int slot = 0; slot < AlarmParse.MAX_ALARMS; slot++) {
            try {
                am.cancel(intent(c, slot));
            } catch (Throwable t) { /* nothing scheduled in that slot */ }
        }

        StringBuilder saved = new StringBuilder();
        int slot = 0;
        for (int i = 0; alarms != null && i < alarms.size()
                && slot < AlarmParse.MAX_ALARMS; i++) {
            AlarmParse.Alarm a = alarms.get(i);
            if (a == null || !a.valid()) continue;

            if (saved.length() > 0) saved.append(',');
            saved.append(a.toString());

            if (a.on) {
                try {
                    am.set(AlarmManager.RTC_WAKEUP, nextAt(a), intent(c, slot));
                } catch (Throwable t) {
                    Log.w(TAG, "could not schedule " + a, t);
                }
            }
            slot++;
        }

        TrackerService.prefs(c).edit().putString(KEY_ALARMS, saved.toString()).commit();
        Log.i(TAG, "alarms set: " + (saved.length() == 0 ? "none" : saved.toString()));
    }

    /**
     * Re-schedule what was stored.
     *
     * Needed twice over. AlarmManager forgets everything across a reboot, and replacing the
     * package cancels every alarm it owned, so BootReceiver calls this; and {@code set()} is
     * one-shot, so an alarm re-arms itself for tomorrow as it fires. Reading the saved set back
     * rather than keeping it in memory means a launcher restarted in between still knows what
     * to ring.
     */
    public static synchronized void rearm(Context c) {
        if (c == null) return;
        String saved = TrackerService.prefs(c).getString(KEY_ALARMS, "");
        if (saved.length() == 0) return;

        List<AlarmParse.Alarm> alarms = new ArrayList<AlarmParse.Alarm>();
        String[] rows = saved.split(",");
        for (int i = 0; i < rows.length; i++) {
            AlarmParse.Alarm a = fromSaved(rows[i]);
            if (a != null) alarms.add(a);
        }
        if (!alarms.isEmpty()) apply(c, alarms);
    }

    /** The inverse of {@link AlarmParse.Alarm#toString}: "07:30 on". */
    static AlarmParse.Alarm fromSaved(String row) {
        if (row == null) return null;
        row = row.trim();
        int space = row.indexOf(' ');
        if (space < 0) return null;
        String hhmm = row.substring(0, space);
        int colon = hhmm.indexOf(':');
        if (colon < 0) return null;

        AlarmParse.Alarm a = new AlarmParse.Alarm();
        try {
            a.hour = Integer.parseInt(hhmm.substring(0, colon));
            a.minute = Integer.parseInt(hhmm.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        a.on = "on".equalsIgnoreCase(row.substring(space + 1).trim());
        return a.valid() ? a : null;
    }

    /** What the watch currently has, for a screen or a status reply. */
    public static String describe(Context c) {
        String s = TrackerService.prefs(c).getString(KEY_ALARMS, "");
        return s.length() == 0 ? "none" : s;
    }

    /** The next occurrence of this wall-clock time, today if it is still ahead. */
    static long nextAt(AlarmParse.Alarm a) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, a.hour);
        cal.set(Calendar.MINUTE, a.minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return cal.getTimeInMillis();
    }

    private static PendingIntent intent(Context c, int slot) {
        Intent i = new Intent(c, AlarmRing.class);
        i.setAction("org.watchlauncher.ALARM_" + slot);
        return PendingIntent.getBroadcast(c, REQUEST_BASE + slot, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
