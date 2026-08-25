package org.watchlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * The offline map on the card, and the rules about filling it.
 *
 * Tiles are 4-bit greyscale PNGs, a few kilobytes each, kept at
 * {@code /sdcard/maps/<country>/<z>/<x>/<y>.png}. Nothing is ever fetched
 * twice: once a country is downloaded the watch needs no network at all, which
 * is the whole point on a device whose data connection exists to report its
 * position rather than to stream maps.
 *
 * <h3>Wifi only, for bulk</h3>
 *
 * A country is tens of thousands of tiles. That is not something to pull over
 * the cellular link a tracker shares with its own reporting, so bulk downloads
 * refuse to start without wifi and stop if it goes away. A single tile needed
 * right now is allowed over anything -- one tile is four kilobytes and the
 * alternative is a blank screen.
 */
public class MapTiles {

    /**
    /**
     * https, which this device can only manage because the app brings its own
     * TLS. The platform's has no AES-GCM at all -- the cipher suites are
     * absent from the system image rather than disabled -- and a modern server
     * offers nothing else. See {@link Tls12SocketFactory}.
     *
     * Overridable in /sdcard/Documents/map.txt.
     */
    private static final String DEFAULT_BASE = "https://coredump.ws/map/";
    private static final String CONFIG = "/sdcard/Documents/map.txt";

    public static final String DIR = "/sdcard/maps";

    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 15000;

    /** Decoded tiles held in memory. Sixteen covers a 240px screen several
     *  times over while panning, and each is 256x256 at one byte a pixel. */
    private static final int MEMORY_TILES = 16;

    private final Context ctx;
    private final Map<String, Bitmap> memory =
            new LinkedHashMap<String, Bitmap>(MEMORY_TILES, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            if (size() <= MEMORY_TILES) return false;
            Bitmap b = e.getValue();
            if (b != null && !b.isRecycled()) b.recycle();
            return true;
        }
    };

    private String base = null;

    public MapTiles(Context c) {
        ctx = c.getApplicationContext();
    }

    Context context() { return ctx; }

    /** Whether the backlight is on, for the download diagnostics. */
    boolean screenOn() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    ctx.getSystemService(Context.POWER_SERVICE);
            return pm.isScreenOn();
        } catch (Exception e) {
            return true;
        }
    }

    // ---------------------------------------------------------------- config

    public String base() {
        if (base != null) return base;
        base = DEFAULT_BASE;
        try {
            File f = new File(CONFIG);
            if (f.isFile() && f.canRead()) {
                java.io.BufferedReader r =
                        new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("http")) {
                        base = line.endsWith("/") ? line : (line + "/");
                        break;
                    }
                }
                r.close();
            }
        } catch (Exception e) { /* the default stands */ }
        return base;
    }

    // ---------------------------------------------------------------- network

    public boolean onWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected()
                    && n.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- tiles

    public static File fileFor(String country, int z, int x, int y) {
        return new File(DIR + "/" + country + "/" + z + "/" + x + "/" + y + ".png");
    }

    public boolean have(String country, int z, int x, int y) {
        if (refreshing(country)) return false;      // drawn by an older renderer
        File f = fileFor(country, z, x, y);
        return f.isFile() && f.length() > 0;
    }

    /** From memory, then the card. Never from the network: drawing happens on
     *  the UI thread and the network does not belong there. */
    public Bitmap cached(String country, int z, int x, int y) {
        String key = country + "/" + z + "/" + x + "/" + y;
        Bitmap b = memory.get(key);
        if (b != null && !b.isRecycled()) return b;

        File f = fileFor(country, z, x, y);
        if (!f.isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            // The tiles are greyscale; 565 halves the memory against 8888 and
            // loses nothing that sixteen greys could show.
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b != null) memory.put(key, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * How many tiles of this block are already on the card.
     *
     * One listing per x column rather than a stat per tile. The card is
     * FAT32, where a name lookup is a linear scan of the directory, so asking
     * about 16 files in the same directory one at a time scans it 16 times.
     */
    public int haveInBlock(String country, int z, int x, int y, int w, int h) {
        if (refreshing(country)) return 0;
        int n = 0;
        for (int i = 0; i < w; i++) {
            String[] names = new File(DIR + "/" + country + "/" + z + "/" + (x + i)).list();
            if (names == null || names.length == 0) continue;
            java.util.HashSet<String> here = new java.util.HashSet<String>(names.length * 2);
            for (int k = 0; k < names.length; k++) here.add(names[k]);
            for (int j = 0; j < h; j++) {
                if (here.contains((y + j) + ".png")) n++;
            }
        }
        return n;
    }

    /** Decode into the memory cache off the UI thread, so onDraw never has to.
     *  A miss during drawing is a PNG decode inside a frame, which is exactly
     *  the kind of thing that makes a map feel like treacle. */
    public void warm(String country, int z, int x, int y) {
        if (have(country, z, x, y)) cached(country, z, x, y);
    }

    /** Blocking. @return true if the tile is on the card afterwards. */
    public boolean fetch(String country, int z, int x, int y) {
        if (have(country, z, x, y)) return true;
        String url = base() + "tile.php?c=" + country + "&z=" + z + "&x=" + x + "&y=" + y;
        return download(url, fileFor(country, z, x, y));
    }

    /** The road vectors for a tile, alongside its picture. */
    public boolean fetchRoads(String country, int z, int x, int y) {
        File f = new File(DIR + "/" + country + "/" + z + "/" + x + "/" + y + ".rds");
        if (f.isFile() && f.length() > 0) return true;
        String url = base() + "roads.php?c=" + country + "&z=" + z + "&x=" + x + "&y=" + y;
        return download(url, f);
    }

    /** Blocking download to a file, written via a temporary name so an
     *  interrupted transfer cannot leave a half tile that looks cached. */
    public boolean download(String url, File out) {
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream os = null;
        File tmp = new File(out.getAbsolutePath() + ".part");
        try {
            File dir = out.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;

            c = (HttpURLConnection) new URL(url).openConnection();
            if (c instanceof HttpsURLConnection) {
                // API 19 supports TLS 1.2 but does not enable it.
                SSLSocketFactory f = Tls12SocketFactory.create(ctx);
                if (f != null) ((HttpsURLConnection) c).setSSLSocketFactory(f);
            }
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return false;

            in = c.getInputStream();
            os = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.close();
            os = null;
            return tmp.renameTo(out);
        } catch (Exception e) {
            return false;
        } finally {
            try { if (os != null) os.close(); } catch (Exception e) { /* ignore */ }
            try { if (in != null) in.close(); } catch (Exception e) { /* ignore */ }
            if (c != null) c.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }

    /**
     * A block of tiles in one request, written straight to the card.
     *
     * A country at z15 is a hundred and fifty thousand tiles. One request each
     * is that many round trips, and at even fifty milliseconds apiece the
     * download is two hours of waiting for half-kilobyte files. A 16x16 block
     * is 256 tiles in one response, so the transfer is bounded by the data
     * rather than by the latency.
     *
     *   "WPK1"  u8 zoom  u32 count
     *   per tile:  u32 x  u32 y  u32 length  bytes
     *
     * @return how many tiles were written, or -1 if the request failed
     */
    public int fetchPack(String country, int z, int x, int y, int w, int h) {
        // The screen state rides along so the server log can measure the
        // screen-on/screen-off gap against the real workload, rather than
        // against a synthetic transfer that may not behave the same way.
        // pack.php ignores it.
        String url = base() + "pack.php?c=" + country + "&z=" + z
                + "&x=" + x + "&y=" + y + "&w=" + w + "&h=" + h
                + "&s=" + (screenOn() ? 1 : 0)
                // The previous block's split, so the log says where the time
                // went: tn is milliseconds on the network, tw milliseconds
                // writing tiles to the card.
                + "&tn=" + lastNetMs + "&tw=" + lastWriteMs;
        HttpURLConnection c = null;
        java.io.DataInputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            if (c instanceof HttpsURLConnection) {
                SSLSocketFactory f = Tls12SocketFactory.create(ctx);
                if (f != null) ((HttpsURLConnection) c).setSSLSocketFactory(f);
            }
            c.setConnectTimeout(CONNECT_MS);
            // Generous: the server may be rendering 256 tiles for the first
            // time, which is a few seconds of work before a byte comes back.
            c.setReadTimeout(60000);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return -1;

            in = new java.io.DataInputStream(
                    new java.io.BufferedInputStream(c.getInputStream(), 32768));
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'W' || magic[1] != 'P' || magic[2] != 'K' || magic[3] != '1') {
                return -1;
            }
            in.readUnsignedByte();                     // zoom, already known
            int count = in.readInt();
            if (count < 0 || count > 4096) return -1;

            int written = 0;
            long netMs = 0, writeMs = 0;
            String madeDir = null;
            boolean restyle = refreshing(country);

            for (int i = 0; i < count; i++) {
                long t0 = System.currentTimeMillis();
                int tx = in.readInt();
                int ty = in.readInt();
                int len = in.readInt();
                if (len < 0 || len > 1048576) return -1;
                byte[] png = new byte[len];
                in.readFully(png);
                netMs += System.currentTimeMillis() - t0;

                t0 = System.currentTimeMillis();
                File out = fileFor(country, z, tx, ty);
                // The size check alone is not enough during a style refresh:
                // recolouring changes no index and no length, so every stale
                // tile matches byte for byte in size and would be skipped.
                if (!restyle && out.isFile() && out.length() == len) {
                    written++;
                    writeMs += System.currentTimeMillis() - t0;
                    continue;
                }

                // One directory check per column rather than per tile. Every
                // tile in a pack shares a handful of parents, and on FAT32 a
                // stat is a linear scan of the directory.
                File dir = out.getParentFile();
                if (dir != null) {
                    String path = dir.getPath();
                    if (!path.equals(madeDir)) {
                        if (!dir.isDirectory() && !dir.mkdirs()) continue;
                        madeDir = path;
                    }
                }

                // Written straight to its name, not via a temporary one.
                // Create-plus-rename is two directory updates per tile, and a
                // pack is 256 tiles - which on this card was most of the cost
                // of a block. A torn write leaves a short file, and a short
                // file is refetched, so the rename was buying less than it
                // charged.
                FileOutputStream os = null;
                try {
                    os = new FileOutputStream(out);
                    os.write(png);
                    written++;
                } catch (Exception e) {
                    out.delete();
                } finally {
                    if (os != null) try { os.close(); } catch (Exception e) { }
                }
                writeMs += System.currentTimeMillis() - t0;
            }
            lastNetMs = netMs;
            lastWriteMs = writeMs;
            return written;
        } catch (Exception e) {
            return -1;
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) { /* ignore */ }
            if (c != null) c.disconnect();
        }
    }

    /** Why the last request failed, in one word, for the screen. */
    private String lastError = null;

    public String lastError() { return lastError; }

    /** Text from an endpoint, for the small answers. */
    public String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            if (c instanceof HttpsURLConnection) {
                SSLSocketFactory f = Tls12SocketFactory.create(ctx);
                if (f != null) ((HttpsURLConnection) c).setSSLSocketFactory(f);
            }
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            if (c.getResponseCode() != 200) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream()));
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
            r.close();
            return b.toString();
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName().replace("Exception", "");
            Log.w("watchmap", "GET " + url + " -> " + e.getClass().getSimpleName()
                    + " " + String.valueOf(e.getMessage()));
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * How much of the card the maps are using, and how much of that is dead.
     *
     * Both cached and computed on a thread. Walking the tile tree means
     * stat-ing every file in it, and a downloaded country is a hundred and
     * fifty thousand of them - which the map menu was doing on the UI thread
     * every time it drew a row.
     */
    private static volatile long cachedBytes = -1;
    private static volatile long cachedStale = -1;
    private static volatile long cachedAt = 0;
    private static volatile boolean counting = false;

    /** Set while a bulk download is writing tiles. */
    private static volatile boolean writing = false;

    public static void writing(boolean on) {
        writing = on;
        if (!on) forgetSizes();
    }

    /** What the last caller said was in use, so that a total asked for on its
     *  own does not start a scan that reports nothing as reclaimable. */
    private static volatile String keptCountry = null;
    private static volatile int keptZoom = -1;

    public static long bytesOnCard() {
        scan(keptCountry, keptZoom);
        return cachedBytes < 0 ? 0 : cachedBytes;
    }

    /** Bytes that {@link #cleanup} would free, given what is in use now. */
    public static long reclaimable(String keepCountry, int keepZoom) {
        scan(keepCountry, keepZoom);
        return cachedStale < 0 ? 0 : cachedStale;
    }

    private static void scan(final String keepCountry, final int keepZoom) {
        if (keepCountry != null && !keepCountry.equals(keptCountry)) {
            keptCountry = keepCountry;
            cachedAt = 0;                       // the answer was for elsewhere
        }
        if (keepZoom > 0) keptZoom = keepZoom;
        long now = System.currentTimeMillis();
        if (cachedBytes >= 0 && now - cachedAt < 60000) return;
        if (counting) return;
        // Not while a download is running. The walk costs two syscalls per
        // tile already on the card, so it gets more expensive the further a
        // country download gets - and it spends them on the same card the
        // downloader is writing to, which is why downloads appeared to slow
        // down as they progressed. The figure would be stale on arrival
        // anyway, with the tree changing underneath it.
        if (writing) return;
        counting = true;
        new Thread(new Runnable() {
            public void run() {
                long total = 0, stale = 0;
                File root = new File(DIR);
                File[] countries = root.listFiles();
                if (countries != null) {
                    for (int i = 0; i < countries.length; i++) {
                        File c = countries[i];
                        if (!c.isDirectory()) { total += c.length(); continue; }
                        long here = size(c);
                        total += here;
                        if (keepCountry != null && !c.getName().equals(keepCountry)) {
                            stale += here;                 // a country we left
                            continue;
                        }
                        if (keepZoom > 0) {
                            File[] zooms = c.listFiles();
                            if (zooms == null) continue;
                            for (int j = 0; j < zooms.length; j++) {
                                if (!zooms[j].isDirectory()) continue;
                                if (!zooms[j].getName().equals(String.valueOf(keepZoom))) {
                                    // A zoom nothing draws any more - the z13
                                    // overviews from before a country was
                                    // measured small enough to keep at z15.
                                    stale += size(zooms[j]);
                                }
                            }
                        }
                    }
                }
                cachedBytes = total;
                cachedStale = stale;
                cachedAt = System.currentTimeMillis();
                counting = false;
            }
        }).start();
    }

    /** Forget the cached figures, so the next read counts again. */
    public static void forgetSizes() {
        cachedAt = 0;
    }

    /**
     * Delete what is no longer drawn: other countries, and zoom levels this
     * build does not use.
     *
     * Deliberately conservative. It never touches the country in use at the
     * zoom in use, so it cannot delete the map under your feet, and it is
     * reported in megabytes rather than done silently.
     *
     * @return bytes freed
     */
    public static long cleanup(String keepCountry, int keepZoom) {
        long freed = 0;
        File root = new File(DIR);
        File[] countries = root.listFiles();
        if (countries == null) return 0;

        for (int i = 0; i < countries.length; i++) {
            File c = countries[i];
            if (!c.isDirectory()) continue;
            if (keepCountry != null && !c.getName().equals(keepCountry)) {
                freed += delete(c);
                continue;
            }
            File[] zooms = c.listFiles();
            if (zooms == null) continue;
            for (int j = 0; j < zooms.length; j++) {
                if (!zooms[j].isDirectory()) continue;
                if (keepZoom > 0 && !zooms[j].getName().equals(String.valueOf(keepZoom))) {
                    freed += delete(zooms[j]);
                }
            }
        }
        forgetSizes();
        return freed;
    }

    private static long delete(File f) {
        long n = 0;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (int i = 0; i < kids.length; i++) n += delete(kids[i]);
            }
        } else {
            n = f.length();
        }
        return f.delete() ? n : 0;
    }

    /**
     * Which rendering the tiles on the card were made with.
     *
     * Bumped when the server starts drawing tiles differently - the move from
     * sixteen greys to sixteen colours, for instance. Without it a card
     * holding half a country in the old style and half in the new shows the
     * seam between them, and nothing would ever replace the old half, because
     * as far as the downloader is concerned those tiles are present.
     */
    public static final int STYLE = 2;

    /**
     * The country being re-rendered, if any.
     *
     * Refreshing a style is deliberately NOT a delete followed by a download.
     * Deleting a country is tens of thousands of unlinks on a FAT32 card, one
     * at a time, with the screen showing "starting" throughout and no way to
     * tell it apart from a hang - which is exactly how it looked. Instead the
     * tiles are treated as absent for the duration and overwritten in place,
     * so the map stays usable the whole way through and nothing is ever
     * deleted that has not already been replaced.
     */
    private static volatile String refreshing = null;

    /** Where the last pack's time went, reported on the next request. */
    private static volatile long lastNetMs = 0, lastWriteMs = 0;

    private static File styleFile(String country) {
        return new File(DIR + "/" + country + "/.style");
    }

    static boolean refreshing(String country) {
        String r = refreshing;
        return r != null && r.equals(country);
    }

    /** @return true if this country's tiles predate the current renderer. */
    public static boolean styleStale(String country) {
        if (country == null) return false;
        File dir = new File(DIR + "/" + country);
        if (!dir.isDirectory()) return false;          // nothing to refresh
        File mark = styleFile(country);
        try {
            if (!mark.isFile()) return true;
            byte[] b = new byte[16];
            java.io.FileInputStream in = new java.io.FileInputStream(mark);
            int n = in.read(b);
            in.close();
            return n <= 0 || Integer.parseInt(new String(b, 0, n).trim()) != STYLE;
        } catch (Exception e) {
            return true;
        }
    }

    public static void beginStyleRefresh(String country) { refreshing = country; }

    /** Only called when the refresh actually completed; a partial one leaves
     *  the marker alone so the next download picks the rest up. */
    public static void endStyleRefresh(String country) {
        refreshing = null;
        if (country == null) return;
        try {
            File dir = new File(DIR + "/" + country);
            if (dir.isDirectory() || dir.mkdirs()) {
                java.io.FileOutputStream o =
                        new java.io.FileOutputStream(styleFile(country));
                o.write(String.valueOf(STYLE).getBytes());
                o.close();
            }
        } catch (Exception e) {
            // Losing the marker only costs one extra refresh later.
        }
        forgetSizes();
    }

    public static void abortStyleRefresh() { refreshing = null; }

    /** Megabytes, for a row on a 240px screen. */
    public static String mb(long bytes) {
        if (bytes >= 1048576L * 1024) return String.format("%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1048576L) return (bytes / 1048576L) + " MB";
        if (bytes >= 1024) return (bytes / 1024) + " KB";
        return bytes + " B";
    }

    /** Recursive size. listFiles() returns null for a plain file, which saves
     *  an isFile() stat on every tile - and tiles are all of the entries. */
    private static long size(File f) {
        File[] kids = f.listFiles();
        if (kids == null) return f.length();
        long total = 0;
        for (int i = 0; i < kids.length; i++) total += size(kids[i]);
        return total;
    }
}
