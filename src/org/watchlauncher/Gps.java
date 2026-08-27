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
     * Ask the receiver to take what help there is.
     *
     * A cold start is slow for a reason: with no idea of the time, no idea of
     * roughly where it is, and no almanac, the receiver has to find satellites
     * by searching for them, which is a minute or two of listening. Given a
     * clock and a rough position it knows which ones are overhead and where in
     * the sky to look, and it drops to seconds.
     *
     * Both are things this watch already has and was not handing over. The
     * clock is set from the network, and the tracker server holds a position
     * resolved from wifi and cell that the map is already using to centre
     * itself - good to a few hundred metres, which is far better than nothing
     * for deciding which satellites are up.
     *
     * These are the HAL's own commands. A receiver that does not implement one
     * returns false and is no worse off, so there is nothing to check for and
     * nothing to fall back to. They are worth sending again whenever the map
     * opens: assistance data goes stale, and the cost is a binder call.
     */
    public static void assist(LocationManager lm) {
        if (lm == null) return;
        String[] cmds = {
            // Downloads the extended almanac, if the platform has a source.
            "force_xtra_injection",
            // Tells it what the time is, which alone is most of the saving.
            "force_time_injection",
        };
        for (int i = 0; i < cmds.length; i++) {
            try {
                boolean ok = lm.sendExtraCommand(LocationManager.GPS_PROVIDER, cmds[i], null);
                Log.i("watchmap", "gps " + cmds[i] + ": " + ok);
            } catch (Throwable t) {
                // A HAL that does not know the command, or a provider that is
                // not there. Neither is a reason to stop.
            }
        }
    }

    /**
     * Whether the watch has been told to use its own receiver.
     *
     * Off by default, and that default is not caution for its own sake.
     * Turning the framework's provider on made the firmware's health sensor
     * wedge roughly seven times sooner - measured over a day, the stretches
     * where a pulse was arriving fell from a mean of 147 minutes to 22, and
     * the change lines up with the hour this was first switched on
     * automatically. The tracker firmware owns the receiver through its own
     * gpsd; asking the platform to open the same hardware is a plausible way
     * to upset the process that is also doing the health polling, and the
     * watch was then being restarted hourly by the server's recovery.
     *
     * So it is a choice rather than something the map does behind your back:
     * a faster fix, or a heart rate. Sports, hold A, "GPS provider".
     */
    private static final String PREF = "gps.wanted";

    public static boolean wanted(ShellActivity shell) {
        try {
            return shell.getSharedPreferences("watchlauncher", ShellActivity.MODE_PRIVATE)
                    .getBoolean(PREF, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setWanted(ShellActivity shell, boolean on) {
        try {
            shell.getSharedPreferences("watchlauncher", ShellActivity.MODE_PRIVATE)
                    .edit().putBoolean(PREF, on).commit();
        } catch (Exception e) { /* nothing to do */ }
    }

    /** Hand the receiver back to the firmware. */
    public static boolean disable(ShellActivity shell, LocationManager lm) {
        RootShell sh = shell == null ? null : shell.root();
        if (sh == null || !sh.isRoot()) return false;
        sh.exec("settings put secure location_providers_allowed -gps");
        boolean off = off(lm);
        Log.i("watchmap", off ? "gps provider disabled" : "could not disable the gps provider");
        return off;
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
