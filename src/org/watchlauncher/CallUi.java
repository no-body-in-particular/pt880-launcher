package org.watchlauncher;

import android.content.SharedPreferences;
import android.util.Log;

/**
 * Take the in-call screen away from the stock dialler.
 *
 * <h3>Why</h3>
 *
 * The stock in-call screen is a dead end on this watch. Ending a call there
 * wants a touchscreen, and the two hardware buttons are not something it
 * listens to, so an outgoing call cannot be cancelled at all: the screen
 * covers the launcher the moment you dial and there is no way back to the
 * button that would hang up.
 *
 * Asking for the foreground back does not work. The launcher tried, three
 * times over four seconds, and lost every time - the stock screen is started
 * by the telephony framework with NEW_TASK|CLEAR_TOP, which outranks an
 * activity politely reordering itself to the front.
 *
 * So the screen is stopped from existing. Only the Activity is disabled; the
 * service that actually runs the call lives in the same package and is left
 * alone. Measured on API 19 with it disabled: the call connects, the launcher
 * keeps the screen, and hanging up from the launcher returns the line to idle.
 *
 * <h3>What this does to the device</h3>
 *
 * It flips one component's enabled flag through the root helper. Nothing is
 * uninstalled, nothing is patched, no system file is written, and it survives
 * a reboot because that flag is where Android keeps it. {@link #giveBack} puts
 * it back, and the launcher offers that in the system menu so a watch is never
 * left in a state only adb can undo.
 *
 * It is done once per install rather than on every start: the flag persists,
 * and re-running it on every launch would spend a root shell on a question
 * already answered.
 */
public final class CallUi {

    private CallUi() { }

    private static final String PREF = "callui.taken";

    /**
     * Where the in-call screen lives, most likely first.
     *
     * Not one hardcoded name: KitKat moved this out of com.android.phone into
     * the dialler as com.android.incallui, and vendors move it again. Each
     * candidate is checked against the package manager before anything is
     * disabled, so a build that has none of them is left untouched rather than
     * having something arbitrary switched off.
     */
    private static final String[] CANDIDATES = {
        "com.android.dialer/com.android.incallui.InCallActivity",
        "com.android.incallui/com.android.incallui.InCallActivity",
        "com.android.incallui/.InCallActivity",
        "com.android.phone/.InCallScreen",
    };

    /** @return the component this device uses, or null if none is present */
    public static String find(ShellActivity shell) {
        RootShell root = shell.root();
        if (root == null) return null;
        for (int i = 0; i < CANDIDATES.length; i++) {
            String comp = CANDIDATES[i];
            String pkg = comp.substring(0, comp.indexOf('/'));
            String cls = comp.substring(comp.indexOf('/') + 1);
            String fq = cls.startsWith(".") ? pkg + cls : cls;
            String out = root.exec("pm dump " + pkg + " 2>/dev/null | grep -c " + fq);
            if (out == null) continue;
            out = out.trim();
            if (out.length() > 0 && !out.equals("0")) return comp;
        }
        return null;
    }

    public static boolean taken(ShellActivity shell) {
        return prefs(shell).getBoolean(PREF, false);
    }

    /**
     * Disable the stock in-call screen, once.
     *
     * Quiet about it: this runs at startup, it needs root, and a watch without
     * root should carry on being a watch. The only visible sign of failure is
     * that calls behave as they did before.
     *
     * @return true if the screen is ours now
     */
    public static boolean takeOver(ShellActivity shell) {
        if (taken(shell)) return true;
        RootShell root = shell.root();
        if (root == null || !root.isRoot()) return false;

        String comp = find(shell);
        if (comp == null) {
            Log.i("watchcall", "no stock in-call screen found; nothing to disable");
            return false;
        }
        if (!root.runQuiet("pm disable " + comp)) {
            Log.w("watchcall", "could not disable " + comp);
            return false;
        }
        Log.i("watchcall", "disabled " + comp + "; the launcher owns calls now");
        prefs(shell).edit().putBoolean(PREF, true).commit();
        return true;
    }

    /** Give the stock screen back. Offered in the system menu, so this is
     *  never a state that needs adb to leave. */
    public static boolean giveBack(ShellActivity shell) {
        RootShell root = shell.root();
        if (root == null || !root.isRoot()) return false;
        String comp = find(shell);
        if (comp == null) {
            // Nothing found now, but the flag may still be set from an install
            // that did find one. Clearing it lets a later start try again.
            prefs(shell).edit().putBoolean(PREF, false).commit();
            return false;
        }
        boolean ok = root.runQuiet("pm enable " + comp);
        if (ok) {
            Log.i("watchcall", "re-enabled " + comp);
            prefs(shell).edit().putBoolean(PREF, false).commit();
        }
        return ok;
    }

    private static SharedPreferences prefs(ShellActivity shell) {
        return shell.getSharedPreferences("watchlauncher", ShellActivity.MODE_PRIVATE);
    }
}
