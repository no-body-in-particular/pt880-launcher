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
     *  times over while panning, at 256x256 and two bytes a pixel - so about
     *  two megabytes, which this heap can hold. */
    private static final int MEMORY_TILES = 16;

    private final Context ctx;

    /**
     * Evicted tiles are dropped, never recycled.
     *
     * recycle() frees the pixels immediately, and hardware rendering does not
     * copy them: libhwui keeps the bitmap in a texture cache keyed by the
     * native object, and a display list built this frame can still reference
     * one recycled a moment later. Freeing it under the renderer is a
     * use-after-free inside libhwui - a SIGSEGV no Java handler can catch,
     * which as the home screen reads as the map flicking back to the
     * launcher rather than as a crash.
     *
     * The eviction thread and the drawing thread are not the same one, so
     * there is no moment at which recycling here is safe. Letting the
     * collector free them costs a little memory and nothing else: it will not
     * free a bitmap the renderer still holds a reference to, which is exactly
     * the guarantee recycle() throws away.
     *
     * Synchronised for the same reason. warm() decodes on the prefetch thread
     * while onDraw reads on the UI thread, and an unsynchronised
     * LinkedHashMap under concurrent access can corrupt its own chains -
     * especially this one, which reorders itself on every get().
     */
    private final Map<String, Bitmap> memory = java.util.Collections.synchronizedMap(
            new LinkedHashMap<String, Bitmap>(MEMORY_TILES, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > MEMORY_TILES;
        }
    });

    private String base = null;

    public MapTiles(Context c) {
        ctx = c.getApplicationContext();
    }

    Context context() { return ctx; }

    private static volatile int versionCode = -1;

    private int version() {
        if (versionCode < 0) {
            try {
                versionCode = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0).versionCode;
            } catch (Exception e) {
                versionCode = 0;
            }
        }
        return versionCode;
    }

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

    /*
     * A block of tiles is one file, not two hundred and fifty six.
     *
     * The card is FAT32 on slow flash. A country at z15 is about 150,000
     * tiles averaging 515 bytes, and stored one per file that is 150,000
     * directory entries, 150,000 allocations, and - since the cluster is far
     * larger than the tile - most of the space wasted on slack. Writing them
     * cost a measured half second per block of 256, which by the end was the
     * largest single component of a download.
     *
     * So the download unit and the storage unit are the same thing: the 16x16
     * block the server already packs. One file, one write, one directory
     * entry, and a fixed index at the head so a single tile is still a seek
     * and a read rather than a parse.
     *
     *     "WTB1"  u8 zoom  u8 bits  u32 baseX  u32 baseY  u32 count
     *     index:  256 x (u32 offset, u32 length)     length 0 = no such tile
     *     then the PNG bytes, in index order
     *
     * Offsets are from the start of the file. Big-endian, like everything
     * else the watch reads.
     */
    static final int BLOCK_BITS = 4;
    static final int BLOCK = 1 << BLOCK_BITS;              // 16
    private static final int SLOTS = BLOCK * BLOCK;        // 256
    private static final byte[] MAGIC = {'W', 'T', 'B', '1'};
    private static final int HEAD_LEN = 4 + 1 + 1 + 4 + 4 + 4;
    private static final int INDEX_LEN = SLOTS * 8;
    private static final int DATA_AT = HEAD_LEN + INDEX_LEN;

    /** Tiles live under b<zoom>, so a card written by the old one-file-per-tile
     *  build is simply not found - and shows up under Clean up as reclaimable
     *  rather than being deleted out from under a working map. */
    static String zoomDir(int z) { return "b" + z; }

    static File blockFile(String country, int z, int bx, int by) {
        return new File(DIR + "/" + country + "/" + zoomDir(z) + "/" + bx + "_" + by + ".wtb");
    }

    static int blockOf(int tile) { return tile >> BLOCK_BITS; }

    private static int slotOf(int x, int y) {
        return ((x & (BLOCK - 1)) * BLOCK) + (y & (BLOCK - 1));
    }

    /**
     * Block indexes, kept in memory.
     *
     * Two kilobytes each and read on every tile lookup, so a handful of the
     * most recent ones save a seek per tile while the map is being drawn.
     */
    private final Map<String, int[]> indexes =
            new LinkedHashMap<String, int[]>(8, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, int[]> e) {
            return size() > 8;
        }
    };

    /** @return offset/length pairs by slot, or null if there is no such block */
    private int[] index(String country, int z, int bx, int by) {
        String key = country + "/" + z + "/" + bx + "_" + by;
        synchronized (indexes) {
            int[] got = indexes.get(key);
            if (got != null) return got.length == 0 ? null : got;
        }

        int[] table = null;
        File f = blockFile(country, z, bx, by);
        if (f.isFile() && f.length() >= DATA_AT) {
            java.io.RandomAccessFile r = null;
            try {
                r = new java.io.RandomAccessFile(f, "r");
                byte[] head = new byte[HEAD_LEN];
                r.readFully(head);
                if (head[0] == MAGIC[0] && head[1] == MAGIC[1]
                        && head[2] == MAGIC[2] && head[3] == MAGIC[3]) {
                    byte[] idx = new byte[INDEX_LEN];
                    r.readFully(idx);
                    table = new int[SLOTS * 2];
                    for (int i = 0; i < SLOTS * 2; i++) {
                        int b = i * 4;
                        table[i] = ((idx[b] & 0xFF) << 24) | ((idx[b + 1] & 0xFF) << 16)
                                 | ((idx[b + 2] & 0xFF) << 8) | (idx[b + 3] & 0xFF);
                    }
                }
            } catch (Exception e) {
                table = null;
            } finally {
                try { if (r != null) r.close(); } catch (Exception e) { }
            }
        }
        synchronized (indexes) {
            indexes.put(key, table == null ? new int[0] : table);
        }
        return table;
    }

    private void forgetIndex(String country, int z, int bx, int by) {
        synchronized (indexes) {
            indexes.remove(country + "/" + z + "/" + bx + "_" + by);
        }
    }

    public boolean have(String country, int z, int x, int y) {
        if (refreshing(country)) return false;      // drawn by an older renderer
        int[] t = index(country, z, blockOf(x), blockOf(y));
        if (t == null) return false;
        return t[slotOf(x, y) * 2 + 1] > 0;
    }

    /** Whether the whole block is on the card already. */
    public boolean haveBlock(String country, int z, int bx, int by) {
        if (refreshing(country)) return false;
        return index(country, z, bx, by) != null;
    }

    /** From memory, then the card. Never from the network: drawing happens on
     *  the UI thread and the network does not belong there. */
    public Bitmap cached(String country, int z, int x, int y) {
        String key = country + "/" + z + "/" + x + "/" + y;
        Bitmap b = memory.get(key);
        if (b != null && !b.isRecycled()) return b;

        int bx = blockOf(x), by = blockOf(y);
        int[] t = index(country, z, bx, by);
        if (t == null) return null;
        int slot = slotOf(x, y) * 2;
        int off = t[slot], len = t[slot + 1];
        if (len <= 0) return null;

        java.io.RandomAccessFile r = null;
        try {
            r = new java.io.RandomAccessFile(blockFile(country, z, bx, by), "r");
            r.seek(off);
            byte[] png = new byte[len];
            r.readFully(png);

            // Check it really is a PNG before handing it to the decoder.
            //
            // BitmapFactory is libskia, and skia on a 2013 build does not
            // always fail politely on a buffer that is not an image - it can
            // take the process down with a native crash, which no Java
            // handler can catch and which reads as the launcher restarting
            // rather than as a fault. A wrong offset in a block index would
            // otherwise be pointed straight at it.
            if (len < 8 || (png[0] & 0xFF) != 0x89 || png[1] != 'P'
                    || png[2] != 'N' || png[3] != 'G') {
                Log.w("watchmap", "not a png at " + country + "/" + z + "/"
                        + x + "/" + y + " off=" + off + " len=" + len);
                return null;
            }

            BitmapFactory.Options o = new BitmapFactory.Options();
            // 565 halves the memory against 8888 and loses nothing that
            // sixteen palette entries could show.
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            b = BitmapFactory.decodeByteArray(png, 0, len, o);
            if (b != null) memory.put(key, b);
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Exception e) { }
        }
    }

    /** Decode into the memory cache off the UI thread, so onDraw never has to.
     *  A miss during drawing is a PNG decode inside a frame, which is exactly
     *  the kind of thing that makes a map feel like treacle. */
    public void warm(String country, int z, int x, int y) {
        if (have(country, z, x, y)) cached(country, z, x, y);
    }

    /**
     * Blocking. @return true if the tile is on the card afterwards.
     *
     * Fetches the whole block containing it, because the block is the unit
     * the card stores. Browsing therefore pulls rather more than the one tile
     * being looked at - and then every neighbouring tile is already there,
     * which is what panning wants anyway.
     */
    public boolean fetch(String country, int z, int x, int y) {
        if (have(country, z, x, y)) return true;
        int bx = blockOf(x), by = blockOf(y);
        return fetchPack(country, z, bx << BLOCK_BITS, by << BLOCK_BITS,
                BLOCK, BLOCK) > 0 && have(country, z, x, y);
    }

    /** Blocking download to a file, written via a temporary name so an
     *  interrupted transfer cannot leave a half tile that looks cached. */
    public boolean download(String url, File out) {
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream os = null;
        boolean drained = false;
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
            drained = true;                 // read to EOF: reusable
            return tmp.renameTo(out);
        } catch (Exception e) {
            return false;
        } finally {
            try { if (os != null) os.close(); } catch (Exception e) { /* ignore */ }
            try { if (in != null) in.close(); } catch (Exception e) { /* ignore */ }
            if (c != null && !drained) c.disconnect();
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
    /*
     * Why these methods do not call disconnect().
     *
     * On Android, HttpURLConnection.disconnect() does not merely finish with
     * the response - it closes the socket and drops it from the pool. Calling
     * it after every block meant every block began with a fresh TLS
     * handshake, and this device's TLS is BouncyCastle in pure Java: ECDHE
     * plus an RSA-2048 signature check on a 2013 ARM with no crypto
     * instructions. That is seconds of arithmetic, identical for a 1kB block
     * and a 240kB one, which is exactly the shape the timings had - a fixed
     * 3.9s per block with transfer speed itself perfectly healthy.
     *
     * Closing the stream instead hands the connection back to the pool, and
     * the next block reuses the already-negotiated session. disconnect() is
     * still right when the response was not read to the end, since a socket
     * with unread bytes on it cannot be reused - so it is kept for the error
     * paths only.
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
                + "&tn=" + lastNetMs + "&tw=" + lastWriteMs
                // Which build is talking. Without it a measurement taken
                // across an update cannot be told apart from one that was
                // not, which is exactly what happened to the first set.
                + "&v=" + version();
        HttpURLConnection c = null;
        java.io.DataInputStream in = null;
        boolean drained = false;
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

            // Read the whole block into memory first. It is a few hundred
            // kilobytes at worst, and the index has to know every offset
            // before the first byte can be written.
            long netMs = 0, writeMs = 0;
            long t0 = System.currentTimeMillis();

            int bx = blockOf(x), by = blockOf(y);
            int[] table = new int[SLOTS * 2];
            byte[][] png = new byte[SLOTS][];
            int written = 0;
            int payload = 0;

            for (int i = 0; i < count; i++) {
                int tx = in.readInt();
                int ty = in.readInt();
                int len = in.readInt();
                if (len < 0 || len > 1048576) return -1;
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                // A tile outside this block would corrupt the index; the
                // server is asked for an aligned block, so this is a guard
                // against a reply that does not match the request.
                if (blockOf(tx) != bx || blockOf(ty) != by) continue;
                int slot = slotOf(tx, ty);
                png[slot] = bytes;
                payload += len;
                written++;
            }
            netMs = System.currentTimeMillis() - t0;

            t0 = System.currentTimeMillis();
            int at = DATA_AT;
            for (int slot = 0; slot < SLOTS; slot++) {
                if (png[slot] == null) continue;
                table[slot * 2] = at;
                table[slot * 2 + 1] = png[slot].length;
                at += png[slot].length;
            }

            File out = blockFile(country, z, bx, by);
            File dir = out.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return -1;

            java.io.BufferedOutputStream os = null;
            try {
                os = new java.io.BufferedOutputStream(
                        new FileOutputStream(out), 32768);
                os.write(MAGIC);
                os.write(z);
                os.write(BLOCK_BITS);
                writeInt(os, bx << BLOCK_BITS);
                writeInt(os, by << BLOCK_BITS);
                writeInt(os, written);
                for (int i = 0; i < SLOTS * 2; i++) writeInt(os, table[i]);
                for (int slot = 0; slot < SLOTS; slot++) {
                    if (png[slot] != null) os.write(png[slot]);
                }
                os.flush();
            } catch (Exception e) {
                try { if (os != null) os.close(); } catch (Exception ignored) { }
                os = null;
                out.delete();
                return -1;
            } finally {
                try { if (os != null) os.close(); } catch (Exception e) { }
            }
            forgetIndex(country, z, bx, by);
            writeMs = System.currentTimeMillis() - t0;

            lastNetMs = netMs;
            lastWriteMs = writeMs;
            drained = true;                 // whole body read: reusable
            return written;
        } catch (Exception e) {
            return -1;
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) { /* ignore */ }
            // Only tear the socket down if the body was not read to the end.
            if (c != null && !drained) c.disconnect();
        }
    }

    /** Why the last request failed, in one word, for the screen. */
    private String lastError = null;

    private static void writeInt(java.io.OutputStream os, int v) throws java.io.IOException {
        os.write((v >>> 24) & 0xFF);
        os.write((v >>> 16) & 0xFF);
        os.write((v >>> 8) & 0xFF);
        os.write(v & 0xFF);
    }

    public String lastError() { return lastError; }

    /** Text from an endpoint, for the small answers. */
    public String get(String url) {
        HttpURLConnection c = null;
        boolean drained = false;
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
            drained = true;
            return b.toString();
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName().replace("Exception", "");
            Log.w("watchmap", "GET " + url + " -> " + e.getClass().getSimpleName()
                    + " " + String.valueOf(e.getMessage()));
            return null;
        } finally {
            if (c != null && !drained) c.disconnect();
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
                                if (!zooms[j].getName().equals(zoomDir(keepZoom))) {
                                    // Anything this build does not read: the
                                    // z13 overviews from before a country was
                                    // measured small enough to keep at z15,
                                    // and the one-file-per-tile trees from
                                    // before blocks were stored whole.
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
                if (keepZoom > 0 && !zooms[j].getName().equals(zoomDir(keepZoom))) {
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
     * Bumped when the server starts drawing tiles differently: sixteen greys
     * to sixteen colours, and then to thirty-two with ground cover and
     * buildings under the roads. Without it a card
     * holding half a country in the old style and half in the new shows the
     * seam between them, and nothing would ever replace the old half, because
     * as far as the downloader is concerned those tiles are present.
     */
    public static final int STYLE = 4;

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
