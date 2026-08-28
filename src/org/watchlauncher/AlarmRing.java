package org.watchlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Vibrator;
import android.util.Log;

/**
 * Rings one of the alarms {@link AlarmClock} scheduled.
 *
 * Sound and vibration together and at full volume, for the same reason the
 * find-device command does it: an alarm that respects a quiet volume setting is
 * an alarm that does not wake anyone, which is the whole job.
 *
 * A one-shot: the alarm is re-armed for tomorrow as it fires, because
 * {@code AlarmManager.set} does not repeat and a daily alarm that rings once is
 * a worse bug than one that never rings - it looks like it works.
 */
public class AlarmRing extends BroadcastReceiver {

    private static final String TAG = "AlarmRing";

    private static final long[] PATTERN = {0, 600, 300, 600, 300, 600, 300, 600};

    @Override
    public void onReceive(Context c, Intent intent) {
        Log.i(TAG, "alarm: " + (intent == null ? "?" : intent.getAction()));
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_ALARM,
                        am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            }
            Ringtone r = RingtoneManager.getRingtone(c,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            if (r != null) r.play();

            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(PATTERN, -1);
        } catch (Throwable t) {
            Log.w(TAG, "could not ring", t);
        }

        // Re-arm from what was stored, so tomorrow's still happens.
        try {
            AlarmClock.rearm(c);
        } catch (Throwable t) {
            Log.w(TAG, "could not re-arm", t);
        }
    }
}
