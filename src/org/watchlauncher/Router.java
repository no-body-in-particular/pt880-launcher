package org.watchlauncher;

import android.util.Log;

/**
 * Routing on the watch, with no network at all.
 *
 * A* over the memory-mapped graph. The heuristic is the straight-line
 * distance to the target divided by the fastest speed on the network, which
 * can never overstate what is left - so the first time the target is settled
 * it is by the quickest route, not merely by a quick one.
 *
 * Measured on the same graph in PHP, the heuristic cuts a 40km route from
 * 198,000 nodes settled to 47,000. A cross-country run still reaches several
 * hundred thousand, which is why the working arrays are plain int[] sized to
 * the graph rather than hash maps: at that occupancy a sparse structure costs
 * more than a dense one, and this is a device where an allocation that only
 * sometimes fits is worse than one that always does.
 *
 * Those arrays are ten megabytes together for the Netherlands. They are
 * allocated when a route is asked for and dropped as soon as it is found,
 * because holding them for the whole drive would leave the map redrawing on
 * whatever heap was left over.
 */
public class Router {

    /** Fastest thing on the network, for the lower bound. Too high only
     *  makes the search wider; too low would make it wrong. */
    private static final double MAX_MPS = 110 / 3.6;

    /** Give up rather than grind: a search this wide has either been asked
     *  for something unreachable or is about to run the battery down. */
    private static final int MAX_SETTLED = 1200000;

    private final RoadGraph g;

    private int[] dist;
    private int[] parent;
    private byte[] stamp;
    private int generation = 0;

    /**
     * The most corridor this device will search.
     *
     * Working memory is nine bytes a node - a distance, a parent, and a
     * visited stamp - so this is the ceiling on what a route can cost:
     * about five megabytes, allocated while searching and dropped after.
     * Beyond it the search is refused and the caller falls back to the
     * server, which is a worse route than none only if you have signal, and
     * is certainly better than an OutOfMemoryError on a watch whose whole
     * heap is a few times this.
     *
     * An 80km area graph for the Netherlands is 1.45 million nodes, so a long
     * enough journey inside one really can reach this.
     */
    private static final int MAX_CORRIDOR_NODES = 600000;

    /*
     * The search is bounded to a corridor, not to the country.
     *
     * The working arrays are one entry per node, so sizing them to the whole
     * network means the Netherlands costs ten megabytes and Germany, which is
     * eight times the area, would want a hundred and fifty. That is not a
     * thing this heap can be asked for.
     *
     * Nodes are numbered in grid-cell order, so all the cells in one row of
     * the corridor are a single run of consecutive ids. A corridor is
     * therefore a few hundred runs at most, and a global id maps to a local
     * one with a binary search over their starts. The arrays are then sized
     * to the corridor, which depends on the length of the journey rather than
     * on the size of the country.
     */
    private int[] runStart;      // first global id of each run
    private int[] runEnd;        // one past the last
    private int[] runBase;       // local index of the first id in the run
    private int runs;
    private int localCount;

    private int[] heapNode = new int[1024];
    private int[] heapKey = new int[1024];
    private int heapSize = 0;

    public Router(RoadGraph graph) { this.g = graph; }

    /** Statistics from the last search, for the About screen and the log. */
    public int settled;
    public long millis;

    /**
     * @return node ids from start to target, or null if there is no way
     */
    public synchronized int[] path(double fromLat, double fromLon,
                                   double toLat, double toLon) {
        if (!g.loaded()) return null;
        // Both ends have to be on the map we hold. Without this the snap
        // quietly returns the nearest node it can find - which for a
        // destination off the edge of the downloaded box is somewhere on the
        // boundary, and the route would confidently lead to the wrong place.
        if (!g.covers(fromLat, fromLon) || !g.covers(toLat, toLon)) return null;
        int s = g.snap(fromLat, fromLon);
        int t = g.snap(toLat, toLon);
        if (s < 0 || t < 0) return null;
        if (s == t) return new int[] { s };

        long t0 = System.currentTimeMillis();

        // How wide a corridor the route is allowed.
        //
        // It was a third of the straight-line distance, which for a
        // cross-country run is a margin of eighty kilometres - and a buffer
        // that wide swallows the whole map whatever shape it is. A route
        // rarely wanders more than a few tens of kilometres sideways even
        // when it goes a long way round, so the margin grows slowly and
        // stops.
        double dy = (toLat - fromLat) * 110540;
        double dx = (toLon - fromLon) * 111320
                * Math.cos(Math.toRadians((fromLat + toLat) / 2));
        double straight = Math.sqrt(dx * dx + dy * dy);
        double margin = Math.min(35000, Math.max(10000, straight * 0.15));

        // Too much to search: narrow it before giving up. A tighter corridor
        // still finds the motorway route, which is what a long journey is.
        for (int tight = 0; tight < 3; tight++) {
            if (corridor(fromLat, fromLon, toLat, toLon, margin)) break;
            if (localCount == 0) return null;        // nothing there at all
            margin *= 0.6;
            if (tight == 2) return null;
        }

        int[] path = search(s, t);

        // Found nothing: widen and try again. A corridor can be too tight to
        // hold any road at all - a coast road, a route through mountains -
        // and that is not the same as there being no way there.
        for (int wider = 0; path == null && wider < 2; wider++) {
            margin *= 2.2;
            if (!corridor(fromLat, fromLon, toLat, toLon, margin)) break;
            path = search(s, t);
        }
        millis = System.currentTimeMillis() - t0;
        release();
        return path;
    }

    /**
     * Work out which runs of node ids the search may touch.
     *
     * @return false if the corridor is somehow empty
     */
    private boolean corridor(double aLat, double aLon, double bLat, double bLon,
                             double margin) {
        double dLat = margin / 110540.0;
        double dLon = margin / (111320.0 * Math.cos(Math.toRadians((aLat + bLat) / 2)));

        int y0 = g.cellY(Math.min(aLat, bLat) - dLat);
        int y1 = g.cellY(Math.max(aLat, bLat) + dLat);
        int cols = g.gridCols();

        int need = y1 - y0 + 1;
        if (need <= 0) return false;
        if (runStart == null || runStart.length < need) {
            runStart = new int[need];
            runEnd = new int[need];
            runBase = new int[need];
        }

        runs = 0;
        localCount = 0;
        for (int y = y0; y <= y1; y++) {
            // Where the line is at this row's latitude, rather than where the
            // two ends are. Boxing the ends of a long diagonal takes in a
            // great deal of country the route was never going to touch; a
            // band that follows the line is a third smaller, and costs
            // nothing extra to express - a row is still one run of ids,
            // because a row of cells is numbered left to right.
            double bandS = g.south() + y * RoadGraph.cellDegrees() - dLat;
            double bandN = bandS + RoadGraph.cellDegrees() + 2 * dLat;

            double t1, t2;
            if (Math.abs(bLat - aLat) < 1e-9) {
                if (aLat < bandS || aLat > bandN) continue;      // parallel
                t1 = 0; t2 = 1;
            } else {
                t1 = (bandS - aLat) / (bLat - aLat);
                t2 = (bandN - aLat) / (bLat - aLat);
                if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
                if (t1 < 0) t1 = 0;
                if (t2 > 1) t2 = 1;
                if (t2 < t1) continue;                            // misses it
            }

            double lon1 = aLon + t1 * (bLon - aLon);
            double lon2 = aLon + t2 * (bLon - aLon);
            int x0 = g.cellX(Math.min(lon1, lon2) - dLon);
            int x1 = g.cellX(Math.max(lon1, lon2) + dLon);

            int from = g.cellFirstNode(y * cols + x0);
            int to = g.cellFirstNode(y * cols + x1 + 1);
            if (to <= from) continue;
            runStart[runs] = from;
            runEnd[runs] = to;
            runBase[runs] = localCount;
            localCount += to - from;
            runs++;
        }
        if (localCount <= 0) return false;

        if (localCount > MAX_CORRIDOR_NODES) {
            Log.w("watchnav", "corridor of " + localCount + " nodes is too wide");
            return false;
        }

        if (dist == null || dist.length < localCount) {
            try {
                dist = new int[localCount];
                parent = new int[localCount];
                stamp = new byte[localCount];
            } catch (OutOfMemoryError e) {
                // Asked for more than was left. Give it all back and let the
                // caller use the server rather than take the process down.
                dist = null;
                parent = null;
                stamp = null;
                Log.w("watchnav", "no room to route: " + localCount + " nodes");
                return false;
            }
            generation = 0;
        }
        return true;
    }

    /** Global node id to an index into the working arrays, or -1 if the node
     *  lies outside the corridor. */
    private int local(int node) {
        int lo = 0, hi = runs - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (node < runStart[mid]) hi = mid - 1;
            else if (node >= runEnd[mid]) lo = mid + 1;
            else return runBase[mid] + (node - runStart[mid]);
        }
        return -1;
    }

    private int[] search(int s, int t) {
        int ls = local(s), lt = local(t);
        if (ls < 0 || lt < 0) return null;
        // A generation counter instead of clearing ten megabytes on every
        // search: a stamp that is not this generation means "not visited".
        // The stamp is a byte, so the generation cycles rather than growing.
        // On the wrap the array is cleared once - which is the only time the
        // five megabytes are touched wholesale, and is still cheaper than
        // clearing them on every search, which is what the counter avoids.
        generation++;
        if (generation > 255) {
            java.util.Arrays.fill(stamp, (byte) 0);
            generation = 1;
        }
        heapSize = 0;

        final double tla = g.lat(t), tlo = g.lon(t);
        final double kx = Math.cos(Math.toRadians(tla));

        dist[ls] = 0;
        parent[ls] = -1;
        stamp[ls] = (byte) generation;
        push(s, heuristic(s, tla, tlo, kx));

        settled = 0;
        boolean found = false;
        while (heapSize > 0) {
            int u = pop();
            if (u == t) { found = true; break; }
            settled++;
            if (settled > MAX_SETTLED) {
                Log.w("watchnav", "search gave up after " + settled + " nodes");
                break;
            }
            int lu = local(u);
            if (lu < 0) continue;
            int du = dist[lu];
            int from = g.firstArc(u);
            int to = g.firstArc(u + 1);
            for (int k = from; k < to; k++) {
                int v = g.arcTarget(k);
                int lv = local(v);
                if (lv < 0) continue;              // outside the corridor
                int nd = du + g.arcCost(k);
                if (stamp[lv] != (byte) generation || nd < dist[lv]) {
                    stamp[lv] = (byte) generation;
                    dist[lv] = nd;
                    parent[lv] = u;                // parents are global ids
                    push(v, nd + heuristic(v, tla, tlo, kx));
                }
            }
        }
        if (!found) return null;

        int len = 0;
        for (int u = t; u != -1; u = parent[local(u)]) len++;
        int[] out = new int[len];
        int i = len - 1;
        for (int u = t; u != -1; u = parent[local(u)]) out[i--] = u;

        Log.i("watchnav", "route " + len + " hops, " + settled + " settled, "
                + localCount + " nodes in corridor");
        return out;
    }

    /** Let the working arrays go. Ten megabytes is not something to hold on
     *  to between routes on a heap this size. */
    private void release() {
        dist = null;
        parent = null;
        stamp = null;
        runStart = null;
        runEnd = null;
        runBase = null;
        runs = 0;
        heapNode = new int[1024];
        heapKey = new int[1024];
        heapSize = 0;
    }

    private int heuristic(int node, double tla, double tlo, double kx) {
        double dy = (g.lat(node) - tla) * 110540;
        double dx = (g.lon(node) - tlo) * 111320 * kx;
        return (int) (Math.sqrt(dx * dx + dy * dy) / MAX_MPS * 10);
    }

    // ------------------------------------------------------------ the heap

    private void push(int node, int key) {
        if (heapSize == heapNode.length) {
            int[] a = new int[heapSize * 2];
            int[] b = new int[heapSize * 2];
            System.arraycopy(heapNode, 0, a, 0, heapSize);
            System.arraycopy(heapKey, 0, b, 0, heapSize);
            heapNode = a;
            heapKey = b;
        }
        int i = heapSize++;
        heapNode[i] = node;
        heapKey[i] = key;
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (heapKey[p] <= heapKey[i]) break;
            swap(p, i);
            i = p;
        }
    }

    private int pop() {
        int top = heapNode[0];
        heapSize--;
        if (heapSize > 0) {
            heapNode[0] = heapNode[heapSize];
            heapKey[0] = heapKey[heapSize];
            int i = 0;
            while (true) {
                int l = i * 2 + 1, r = l + 1, m = i;
                if (l < heapSize && heapKey[l] < heapKey[m]) m = l;
                if (r < heapSize && heapKey[r] < heapKey[m]) m = r;
                if (m == i) break;
                swap(m, i);
                i = m;
            }
        }
        return top;
    }

    private void swap(int a, int b) {
        int n = heapNode[a]; heapNode[a] = heapNode[b]; heapNode[b] = n;
        int k = heapKey[a]; heapKey[a] = heapKey[b]; heapKey[b] = k;
    }
}
