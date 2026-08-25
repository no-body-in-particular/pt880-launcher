package org.watchlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

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

    /** Overridable, but not secret: unlike the tracker URL this carries no
     *  identifier and no token, so a default in the source costs nothing. */
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
                SSLSocketFactory f = Tls12SocketFactory.create();
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
        String url = base() + "pack.php?c=" + country + "&z=" + z
                + "&x=" + x + "&y=" + y + "&w=" + w + "&h=" + h;
        HttpURLConnection c = null;
        java.io.DataInputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            if (c instanceof HttpsURLConnection) {
                SSLSocketFactory f = Tls12SocketFactory.create();
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
            for (int i = 0; i < count; i++) {
                int tx = in.readInt();
                int ty = in.readInt();
                int len = in.readInt();
                if (len < 0 || len > 1048576) return -1;
                byte[] png = new byte[len];
                in.readFully(png);

                File out = fileFor(country, z, tx, ty);
                if (out.isFile() && out.length() == len) { written++; continue; }
                File dir = out.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) continue;
                // Written under a temporary name and moved, so an interrupted
                // pack cannot leave a half tile that looks cached.
                File tmp = new File(out.getAbsolutePath() + ".part");
                FileOutputStream os = new FileOutputStream(tmp);
                try { os.write(png); } finally { os.close(); }
                if (tmp.renameTo(out)) written++; else tmp.delete();
            }
            return written;
        } catch (Exception e) {
            return -1;
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) { /* ignore */ }
            if (c != null) c.disconnect();
        }
    }

    /** Text from an endpoint, for the small answers. */
    public String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            if (c instanceof HttpsURLConnection) {
                SSLSocketFactory f = Tls12SocketFactory.create();
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
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** How much of the card the maps are using. */
    public static long bytesOnCard() {
        return size(new File(DIR));
    }

    private static long size(File f) {
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles();
        if (kids == null) return 0;
        long total = 0;
        for (int i = 0; i < kids.length; i++) total += size(kids[i]);
        return total;
    }
}
