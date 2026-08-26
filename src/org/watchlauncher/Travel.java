package org.watchlauncher;

import android.content.SharedPreferences;

/**
 * Car or bicycle.
 *
 * The two are different networks rather than the same one at a different
 * speed. A motorway is not rideable and a cycleway is not drivable, and in
 * this country there are 225,516 of the latter - so routing a bicycle on the
 * driving graph sends it along the roads a car would take, which is both
 * slower and, on a trunk road, illegal.
 *
 * The mode picks which graph file the watch loads and which one it downloads.
 * Nothing else changes: the same router, the same instructions, the same map,
 * because the map already draws cycleways.
 */
public final class Travel {

    private Travel() { }

    public static final int CAR = 0, BIKE = 1;

    private static final String PREF = "travel.mode";

    public static int mode(ShellActivity shell) {
        try {
            return prefs(shell).getInt(PREF, CAR);
        } catch (Exception e) {
            return CAR;
        }
    }

    public static void setMode(ShellActivity shell, int m) {
        prefs(shell).edit().putInt(PREF, m == BIKE ? BIKE : CAR).commit();
    }

    public static boolean bike(ShellActivity shell) { return mode(shell) == BIKE; }

    /** What to show on a menu row. */
    public static String name(int m) { return m == BIKE ? "bicycle" : "car"; }

    /** The query the graph endpoint wants. */
    public static String param(int m) { return m == BIKE ? "&mode=bike" : ""; }

    /**
     * The file this mode's graph lives in.
     *
     * Separate names so switching mode does not mean downloading again, and so
     * that having one does not look like having the other. Neither claims a
     * country: a graph holds a box around wherever it was downloaded and that
     * box may straddle a border, which is what its own header is for.
     */
    public static String graphFile(int m) {
        return m == BIKE ? "bike.graph" : "roads.graph";
    }

    private static SharedPreferences prefs(ShellActivity shell) {
        return shell.getSharedPreferences("watchlauncher", ShellActivity.MODE_PRIVATE);
    }
}
