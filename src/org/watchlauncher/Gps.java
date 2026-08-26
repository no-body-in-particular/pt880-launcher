package org.watchlauncher;

import android.location.LocationManager;
import android.util.Log;

/**
 * Getting the receiver switched on.
 *
 * This watch ships with the gps provider left out of
 * {@code location_providers_allowed}, so the framework reports it disabled and
 * {@code requestLocationUpdates} on it does nothing at all. Nothing about that
 * is visible from the app: the call succeeds, no fixes arrive, and the only
 * symptom is a map that takes minutes to find itself and then moves in jumps -
 * because what it is actually showing is the tracker server's answer, resolved
 * from wifi and cell, not the receiver under it.
 *
 * Turning it on is one line in secure settings, which needs either a signature
 * permission or root. The root helper is already here for the terminal.
 *
 * It was a menu item on the sports screen and nowhere else, which is a poor
 * place for the switch that decides whether navigation works.
 */
public final class Gps {

    private Gps() { }

    public static boolean off(LocationManager lm) {
        if (lm == null) return true;
        try {
            return !lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Switch the provider on if it is off.
     *
     * Costs a root shell, so it is only worth calling where a real position
     * actually matters. Quiet on failure: a watch without the root helper
     * carries on using the server's position, which is what it did before.
     *
     * @return true if the provider is on when this returns
     */
    public static boolean enable(ShellActivity shell, LocationManager lm) {
        if (!off(lm)) return true;
        RootShell sh = shell == null ? null : shell.root();
        if (sh == null || !sh.isRoot()) return false;
        sh.exec("settings put secure location_providers_allowed +gps");
        boolean on = !off(lm);
        Log.i("watchmap", on ? "gps provider enabled" : "could not enable the gps provider");
        return on;
    }
}
