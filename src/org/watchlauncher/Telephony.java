package org.watchlauncher;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.TelephonyManager;
import android.view.KeyEvent;

import java.lang.reflect.Method;

/**
 * Placing, answering and ending calls without a touchscreen.
 *
 * Placing one is easy and public: {@code ACTION_CALL}. Answering and ending
 * are neither. The framework's own methods for both live behind
 * {@code ITelephony}, which is hidden, and on this build they are guarded by
 * {@code MODIFY_PHONE_STATE} -- a signature permission no ordinary app can
 * hold. So each has three attempts, cheapest first:
 *
 *   1. reflection into {@code ITelephony}, which works if the guard happens
 *      not to bite on this vendor build;
 *   2. a media-button broadcast, which the phone app answers on 4.4 the same
 *      way it answers a headset hook press;
 *   3. {@code input keyevent} through the root shell, which is what the
 *      terminal already has open and which nothing can refuse.
 *
 * The third is the reason the root helper is worth installing even if you
 * never open the terminal.
 */
public final class Telephony {

    private Telephony() { }

    public static TelephonyManager manager(Context c) {
        return (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
    }

    public static int callState(Context c) {
        try { return manager(c).getCallState(); }
        catch (Exception e) { return TelephonyManager.CALL_STATE_IDLE; }
    }

    public static boolean hasNetwork(Context c) {
        try {
            TelephonyManager t = manager(c);
            return t.getSimState() == TelephonyManager.SIM_STATE_READY;
        } catch (Exception e) {
            return false;
        }
    }

    /** Dial. The system in-call screen comes up on top of us; the call screen
     *  puts itself back in front a moment later. */
    public static boolean dial(Context c, String number) {
        if (number == null || number.length() == 0) return false;
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean answer(ShellActivity shell) {
        if (viaTelephony(shell, "answerRingingCall")) return true;
        // KEYCODE_HEADSETHOOK is what a wired headset's single button sends,
        // and the dialler treats it as answer while a call is ringing.
        if (mediaButton(shell, KeyEvent.KEYCODE_HEADSETHOOK)) return true;
        return shell.root().runQuiet("input keyevent 79");
    }

    public static boolean hangUp(ShellActivity shell) {
        if (viaTelephony(shell, "endCall")) return true;
        return shell.root().runQuiet("input keyevent 6");    // KEYCODE_ENDCALL
    }

    /** {@code TelephonyManager.getITelephony()} is @hide, and so is everything
     *  on the interface it returns. */
    private static boolean viaTelephony(Context c, String method) {
        try {
            TelephonyManager tm = manager(c);
            Method get = TelephonyManager.class.getDeclaredMethod("getITelephony");
            get.setAccessible(true);
            Object telephony = get.invoke(tm);
            if (telephony == null) return false;
            Method m = telephony.getClass().getMethod(method);
            m.invoke(telephony);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** An ordered ACTION_MEDIA_BUTTON pair, down then up, exactly as the
     *  headset driver sends it. */
    private static boolean mediaButton(Context c, int keyCode) {
        try {
            send(c, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            send(c, new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void send(Context c, KeyEvent ev) {
        Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON);
        i.putExtra(Intent.EXTRA_KEY_EVENT, ev);
        c.sendOrderedBroadcast(i, null);
    }
}
