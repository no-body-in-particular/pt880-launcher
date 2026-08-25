package org.watchlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

/**
 * Brings the watch to the incoming-call screen when the phone rings.
 *
 * Without this the stock in-call UI comes up, and it cannot be answered: it
 * expects a swipe, and there is no touchscreen. Our screen takes over and puts
 * answer on the button that is actually there.
 */
public class PhoneStateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent in) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(in.getAction())) return;
        String state = in.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (!TelephonyManager.EXTRA_STATE_RINGING.equals(state)) return;

        Intent ui = new Intent(c, ShellActivity.class);
        ui.putExtra(ShellActivity.EXTRA_APP, "incoming");
        ui.putExtra(ShellActivity.EXTRA_NUMBER,
                in.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER));
        ui.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            c.startActivity(ui);
        } catch (Exception e) {
            // Nothing to show is better than crashing the phone process's
            // broadcast; the stock UI is still there underneath.
        }
    }
}
