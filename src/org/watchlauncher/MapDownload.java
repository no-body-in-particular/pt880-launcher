package org.watchlauncher;

import java.util.ArrayList;
import java.util.List;

/**
 * Filling the card with map, in bulk.
 *
 * Two shapes of job, and they exist for different reasons.
 *
 * A **country** is downloaded at an overview zoom. The whole of the
 * Netherlands at z13 is about five thousand tiles and twenty megabytes, which
 * is a sane thing to keep permanently; the same country at z15 is eighty-six
 * thousand tiles and would be most of the card for detail you only need where
 * you actually are.
 *
 * A **route** is downloaded at full zoom, but only along itself. A corridor a
 * kilometre either side of a fifty kilometre route is a few hundred tiles.
 * That is the insight that makes this work at all on a watch: you never need a
 * country in detail, only the thread you are travelling along.
 *
 * Both refuse to start without wifi. A country is tens of megabytes and the
 * cellular link belongs to the tracker's own reporting.
 */
public class MapDownload {

    /** Zoom for a whole country: enough to see where you are, not enough to
     *  navigate a junction. */
    public static final int COUNTRY_ZOOM = 13;

    /** Zoom for a route and for the area around you. */
    public static final int DETAIL_ZOOM = 15;

    /** How far either side of the route to take, in metres. */
    private static final int CORRIDOR_M = 1200;

    /** And around the current position, when a country is downloaded. */
    private static final int AROUND_M = 15000;

    public interface Progress {
        /** @return false to stop the job */
        boolean onProgress(int done, int total, int failed);
    }

    private volatile boolean cancelled = false;

    public void cancel() { cancelled = true; }

    /** One tile, as x/y at a zoom. */
    private static class Tile {
        final int x, y;
        Tile(int x, int y) { this.x = x; this.y = y; }
    }

    // ---------------------------------------------------------------- jobs

    /**
     * The overview for a whole country, plus detail around one point.
     *
     * @return tiles that failed, or -1 if it never started
     */
    public int country(MapTiles tiles, String name,
                       double minx, double miny, double maxx, double maxy,
                       double atLat, double atLon, Progress p) {
        if (!tiles.onWifi()) return -1;

        List<Tile> want = box(minx, miny, maxx, maxy, COUNTRY_ZOOM);
        if (!Double.isNaN(atLat)) {
            double dLat = AROUND_M / 111320.0;
            double dLon = AROUND_M / (111320.0 * Math.cos(Math.toRadians(atLat)));
            want.addAll(box(atLon - dLon, atLat - dLat, atLon + dLon, atLat + dLat,
                    DETAIL_ZOOM));
        }
        return run(tiles, name, want, p, COUNTRY_ZOOM);
    }

    /** The corridor along a route, at full detail. */
    public int route(MapTiles tiles, String name, Route r, Progress p) {
        if (!tiles.onWifi()) return -1;
        if (r == null || r.line.size() < 2) return 0;

        // A set, because consecutive route points share tiles many times over
        // and fetching one twice is a wasted request.
        java.util.LinkedHashMap<Long, Tile> want = new java.util.LinkedHashMap<Long, Tile>();
        for (int i = 0; i < r.line.size(); i++) {
            double[] pt = r.line.get(i);
            double dLat = CORRIDOR_M / 111320.0;
            double dLon = CORRIDOR_M / (111320.0 * Math.cos(Math.toRadians(pt[0])));
            List<Tile> here = box(pt[1] - dLon, pt[0] - dLat,
                                  pt[1] + dLon, pt[0] + dLat, DETAIL_ZOOM);
            for (int k = 0; k < here.size(); k++) {
                Tile t = here.get(k);
                want.put(((long) t.x << 32) | (t.y & 0xFFFFFFFFL), t);
            }
        }
        return run(tiles, name, new ArrayList<Tile>(want.values()), p, DETAIL_ZOOM);
    }

    // ---------------------------------------------------------------- guts

    private static List<Tile> box(double minx, double miny, double maxx, double maxy,
                                  int z) {
        int x0 = (int) Math.floor(Mercator.xOf(minx, z));
        int x1 = (int) Math.floor(Mercator.xOf(maxx, z));
        // y runs the other way: north is a smaller number.
        int y0 = (int) Math.floor(Mercator.yOf(maxy, z));
        int y1 = (int) Math.floor(Mercator.yOf(miny, z));

        List<Tile> out = new ArrayList<Tile>();
        int span = 1 << z;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                if (x < 0 || y < 0 || x >= span || y >= span) continue;
                out.add(new Tile(x, y));
            }
        }
        return out;
    }

    private int run(MapTiles tiles, String country, List<Tile> want,
                    Progress p, int zoom) {
        int done = 0, failed = 0;
        int total = want.size();
        for (int i = 0; i < total; i++) {
            if (cancelled) break;
            // Checked every tile, not just at the start: walking out of range
            // mid-download should stop it, not quietly finish over cellular.
            if ((i % 25) == 0 && !tiles.onWifi()) break;

            Tile t = want.get(i);
            if (!tiles.fetch(country, zoom, t.x, t.y)) failed++;
            done++;
            if (p != null && (done % 10) == 0) {
                if (!p.onProgress(done, total, failed)) break;
            }
        }
        if (p != null) p.onProgress(done, total, failed);
        return failed;
    }

    /** What a job would cost, before starting it. Tiles average about four
     *  kilobytes; the estimate is for a person deciding, not for accounting. */
    public static String estimate(int tiles) {
        long bytes = (long) tiles * 4096;
        if (bytes > 1048576L * 1024) return String.format("%.1f GB", bytes / 1073741824.0);
        if (bytes > 1048576L) return String.format("%.0f MB", bytes / 1048576.0);
        return (bytes / 1024) + " KB";
    }

    public static int countTiles(double minx, double miny, double maxx, double maxy,
                                 int z) {
        return box(minx, miny, maxx, maxy, z).size();
    }
}
