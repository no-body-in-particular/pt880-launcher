package org.watchlauncher;

import java.util.ArrayList;
import java.util.List;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

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

    /**
     * A country is kept at full navigating detail, not an overview.
     *
     * That is a measured decision rather than an assumption. A z15 tile of
     * this map averages 448 bytes across a country - 4-bit greyscale line art
     * on black is almost all one repeated value, and PNG eats that - so the
     * whole of the Netherlands is about 64 MB, under three per cent of the
     * card. An earlier version kept countries at z13 on a guess of four
     * kilobytes a tile, which was nine times too pessimistic and bought
     * nothing but a worse map.
     *
     * A continent is still far too much, so the route corridor below stays:
     * it is what makes travelling abroad possible without carrying Europe.
     */
    public static final int COUNTRY_ZOOM = 15;

    /** Routes are at the same zoom; the corridor is what makes them cheap. */
    public static final int DETAIL_ZOOM = 15;

    /** How far either side of the route to take, in metres. */
    private static final int CORRIDOR_M = 1200;

    /** Tiles come in blocks of this many a side. 256 tiles a request turns a
     *  country from a hundred and fifty thousand round trips into six hundred. */
    private static final int BLOCK = 16;

    /** Measured mean for a z15 tile over a whole country, for the estimates.
     *  A city block runs nearer 2.3 kB and open water 192 bytes; this is the
     *  average that matters when sizing a download. */
    private static final int BYTES_PER_TILE = 500;

    public interface Progress {
        /** @return false to stop the job */
        boolean onProgress(int done, int total, int failed);
    }

    /*
     * One download at a time, for the whole process.
     *
     * The guard used to be a field on the map menu, and the menu is rebuilt
     * every time it is opened - so leaving the screen and coming back handed
     * out a second downloader while the first was still running, with no way
     * to stop either. The server log showed several interleaving request for
     * request, each getting a share of the wifi and the card, which is what
     * "the download gets slower the further it gets" actually was.
     */
    private static final Object LOCK = new Object();
    private static MapDownload current = null;

    /** @return a job, or null if one is already running. */
    public static MapDownload claim() {
        synchronized (LOCK) {
            if (current != null) return null;
            current = new MapDownload();
            return current;
        }
    }

    public static boolean running() {
        synchronized (LOCK) { return current != null; }
    }

    public static void cancelCurrent() {
        synchronized (LOCK) { if (current != null) current.cancel(); }
    }

    /** Live progress, readable by whichever screen happens to be showing. */
    public static String progress() {
        MapDownload j;
        synchronized (LOCK) { j = current; }
        if (j == null) return null;
        if (j.pTotal <= 0) return "starting";
        return j.pDone + "/" + j.pTotal + (j.pFailed > 0 ? (" " + j.pFailed + " failed") : "");
    }

    public void release() {
        synchronized (LOCK) { if (current == this) current = null; }
    }

    private volatile int pDone = 0, pTotal = 0, pFailed = 0;

    private volatile boolean cancelled = false;

    /** Tiles that were already on the card and not asked for again. */
    private int skipped = 0;

    public int skipped() { return skipped; }

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
        // Tiles drawn by an older renderer are overwritten as we go, rather
        // than deleted up front - see MapTiles.refreshing.
        boolean restyle = MapTiles.styleStale(name);
        if (restyle) MapTiles.beginStyleRefresh(name);
        try {
            if (!tiles.onWifi()) return -1;

            // No separate detail pass around the position any more: the
            // country itself is at detail zoom now, so nothing to add.
            int failed = blocks(tiles, name, minx, miny, maxx, maxy, COUNTRY_ZOOM, p);
            // Only a clean sweep earns the marker. A partial one leaves it
            // unset so the next download finishes the restyle.
            if (restyle && failed == 0 && !cancelled) MapTiles.endStyleRefresh(name);
            return failed;
        } finally {
            MapTiles.abortStyleRefresh();
        }
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

    /**
     * A rectangle, fetched as blocks rather than as tiles.
     *
     * Whole blocks are asked for even where part of one falls outside the
     * area: a block is one request either way, and trimming it would cost more
     * round trips than the few extra tiles are worth.
     */
    private int blocks(MapTiles tiles, String country,
                       double minx, double miny, double maxx, double maxy,
                       int z, Progress p) {
        hold(tiles.context());
        try {
            return blocksInner(tiles, country, minx, miny, maxx, maxy, z, p);
        } finally {
            MapTiles.writing(false);
            releasePower();
        }
    }

    // ------------------------------------------------------------- power

    /*
     * A download has to survive the screen going off, and on stock Android it
     * does not.
     *
     * Two things happen the moment the backlight dies. The CPU suspends, so a
     * plain background thread gets a sliver of a wakeup every so often rather
     * than running. And the wifi chip drops into power save, where it stops
     * listening continuously and waits for beacons instead - which does not
     * break the transfer, it just makes it crawl, and it stays that way for
     * as long as the screen is off. That is the "much slower after the screen
     * blinks off" exactly.
     *
     * A partial wake lock answers the first and a high performance wifi lock
     * the second. Both are held only while blocks are actually being fetched,
     * and both come off in the finally above - a wake lock left holding on a
     * watch is a flat battery by morning. The timeout is a second line of
     * defence against precisely that.
     */
    private static final long MAX_HOLD_MS = 2 * 60 * 60 * 1000L;

    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock radio;

    private void hold(Context c) {
        if (c == null) return;
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchlauncher.map");
            cpu.setReferenceCounted(false);
            cpu.acquire(MAX_HOLD_MS);
        } catch (Exception e) {
            cpu = null;
        }
        try {
            WifiManager wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
            radio = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "watchlauncher.map");
            radio.setReferenceCounted(false);
            radio.acquire();
        } catch (Exception e) {
            radio = null;
        }
    }

    private void releasePower() {
        try { if (cpu != null && cpu.isHeld()) cpu.release(); } catch (Exception e) { }
        try { if (radio != null && radio.isHeld()) radio.release(); } catch (Exception e) { }
        cpu = null;
        radio = null;
    }

    private int blocksInner(MapTiles tiles, String country,
                            double minx, double miny, double maxx, double maxy,
                            int z, Progress p) {
        int x0 = (int) Math.floor(Mercator.xOf(minx, z));
        int x1 = (int) Math.floor(Mercator.xOf(maxx, z));
        int y0 = (int) Math.floor(Mercator.yOf(maxy, z));    // north is smaller
        int y1 = (int) Math.floor(Mercator.yOf(miny, z));

        int total = (x1 - x0 + 1) * (y1 - y0 + 1);
        int done = 0, failed = 0;
        skipped = 0;
        MapTiles.writing(true);

        for (int x = x0; x <= x1; x += BLOCK) {
            for (int y = y0; y <= y1; y += BLOCK) {
                if (cancelled) return failed;
                // Checked every block: walking out of range mid-download
                // should stop it, not quietly finish over cellular.
                if (!tiles.onWifi()) return -1;

                int w = Math.min(BLOCK, x1 - x + 1);
                int h = Math.min(BLOCK, y1 - y + 1);
                int tilesHere = w * h;

                // Nothing already on the card is asked for again. This is what
                // makes a download resumable: interrupted halfway, the second
                // run skips everything the first one finished instead of
                // fetching the country twice.
                int already = tiles.haveInBlock(country, z, x, y, w, h);
                if (already >= tilesHere) {
                    done += tilesHere;
                    skipped += tilesHere;
                    pDone = Math.min(done, total); pTotal = total; pFailed = failed;
                    if (p != null && !p.onProgress(Math.min(done, total), total, failed)) {
                        return failed;
                    }
                    continue;
                }

                int missing = tilesHere - already;
                if (missing * 4 <= tilesHere) {
                    // Only a few holes: individual requests move less data than
                    // a whole block would, even counting the extra round trips.
                    for (int i = 0; i < w; i++) {
                        for (int j = 0; j < h; j++) {
                            if (tiles.have(country, z, x + i, y + j)) continue;
                            if (!tiles.fetch(country, z, x + i, y + j)) failed++;
                        }
                    }
                    skipped += already;
                } else {
                    int got = tiles.fetchPack(country, z, x, y, w, h);
                    if (got < 0) { failed += tilesHere; }
                    skipped += already;
                }
                done += tilesHere;
                pDone = Math.min(done, total); pTotal = total; pFailed = failed;
                if (p != null && !p.onProgress(Math.min(done, total), total, failed)) {
                    return failed;
                }
            }
        }
        if (p != null) p.onProgress(total, total, failed);
        return failed;
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
        long bytes = (long) tiles * BYTES_PER_TILE;
        if (bytes > 1048576L * 1024) return String.format("%.1f GB", bytes / 1073741824.0);
        if (bytes > 1048576L) return String.format("%.0f MB", bytes / 1048576.0);
        return (bytes / 1024) + " KB";
    }

    public static int countTiles(double minx, double miny, double maxx, double maxy,
                                 int z) {
        return box(minx, miny, maxx, maxy, z).size();
    }
}
