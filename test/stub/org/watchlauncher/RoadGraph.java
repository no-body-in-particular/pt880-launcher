package org.watchlauncher;

/**
 * Enough RoadGraph to compile Route on the host.
 *
 * The real one memory-maps a file through android.util.Log and cannot be
 * built off the device, which would otherwise put Route - and the geometry
 * that decides how far there is left to drive - beyond reach of any test.
 * Only Route.fromNodes touches this, and no test here calls it.
 */
public class RoadGraph {
    public double lat(int n) { return 0; }
    public double lon(int n) { return 0; }
    public double metres(int a, int b) { return 0; }
    public int degree(int n) { return 0; }
    public int firstArc(int n) { return 0; }
    public int arcTarget(int k) { return 0; }
    public int arcCost(int k) { return 0; }
}
