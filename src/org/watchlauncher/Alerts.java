package org.watchlauncher;

import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * The things beside the road: speed cameras, motorway exits, filling stations.
 *
 * None of them change how a route is chosen, so they are not in the graph.
 * They are a separate file the watch may or may not have, and without one
 * everything here answers "nothing near" and the rest of the app carries on.
 *
 * <h3>Why it is not just a list</h3>
 *
 * A country is a few thousand points and the position changes every ten
 * seconds. Scanning the lot each time is the mistake that made finding your
 * place on a route cost 427 ms, so the file is ordered by cell with an index,
 * the same shape as the road graph, and a lookup reads one small range.
 *
 * Memory-mapped rather than read: a continent's worth would be megabytes, and
 * this way the pages for the part of the world being driven through are the
 * only ones that ever arrive.
 */
public class Alerts {

    public static final int CAMERA = 1, EXIT = 2, FUEL = 3;

    private static final int HEADER = 4 + 4 + 4 + 32 + 8;    // to the cell table
    private static final int POINT_BYTES = 11;

    /** A file claiming more than this is corrupt, not ambitious. Europe's
     *  cameras and exits together are well under a million. */
    private static final long MAX_POINTS = 4000000L;
    private static final long MAX_CELLS = 4000000L;

    public static class Near {
        public int kind;
        public double lat, lon;
        public String name;        // may be null
        public double metres;      // from where you asked
    }

    private RandomAccessFile file;
    private ByteBuffer buf;
    private String country;
    private long stamp;

    private int count, cols, rows, nameCount;
    private double minx, miny, maxx, maxy;
    private int cellsAt, pointsAt, namesAt;
    private double cellDeg;

    private static Alerts shared;

    /** One per app: the mapping and its file descriptor are not per screen. */
    public static synchronized Alerts shared() {
        if (shared == null) shared = new Alerts();
        return shared;
    }

    public static File fileFor(String c) {
        return new File(MapTiles.DIR + "/" + (c == null ? "" : c) + ".alerts");
    }

    public synchronized boolean open(String c) {
        if (c == null) return false;
        File f = fileFor(c);
        if (buf != null && c.equals(country) && f.lastModified() == stamp) return true;
        close();
        if (!f.isFile() || f.length() < HEADER) return false;
        try {
            file = new RandomAccessFile(f, "r");
            FileChannel ch = file.getChannel();
            ByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, f.length());
            b.order(ByteOrder.BIG_ENDIAN);

            if (b.get(0) != 'W' || b.get(1) != 'A' || b.get(2) != 'L'
                    || b.get(3) != '1' || b.get(4) != 1) {
                Log.w("watchmap", "not a WAL1 alert file: " + f);
                close();
                return false;
            }
            nameCount = b.getShort(6) & 0xFFFF;
            count = b.getInt(8);
            minx = b.getDouble(12);
            miny = b.getDouble(20);
            maxx = b.getDouble(28);
            maxy = b.getDouble(36);
            cols = b.getInt(44);
            rows = b.getInt(48);

            // Every offset in long arithmetic and every count checked before
            // anything is cast down: this file arrives over wifi onto FAT32
            // on cheap flash and may say anything at all.
            long cells = (long) cols * rows;
            if (count < 0 || count > MAX_POINTS || cols <= 0 || rows <= 0
                    || cells > MAX_CELLS
                    || !finite(minx) || !finite(miny) || !finite(maxx) || !finite(maxy)
                    || maxx <= minx || maxy <= miny) {
                Log.w("watchmap", "alert header out of range for " + f);
                close();
                return false;
            }
            cellsAt = 52;
            long pAt = cellsAt + (cells + 1) * 4L;
            long nAt = pAt + (long) count * POINT_BYTES;
            if (nAt > f.length()) {
                Log.w("watchmap", "alert file truncated: " + f.length()
                        + " bytes, header wants " + nAt);
                close();
                return false;
            }
            pointsAt = (int) pAt;
            namesAt = (int) nAt;
            cellDeg = (maxx - minx) / cols;
            if (!(cellDeg > 0)) { close(); return false; }

            buf = b;
            country = c;
            stamp = f.lastModified();
            Log.i("watchmap", "alerts " + c + ": " + count + " points, "
                    + cols + "x" + rows + " cells, " + nameCount + " names");
            return true;
        } catch (Throwable t) {
            Log.w("watchmap", "alerts " + c + ": " + t);
            close();
            return false;
        }
    }

    private static boolean finite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    public synchronized void close() {
        buf = null;
        country = null;
        count = cols = rows = nameCount = 0;
        try { if (file != null) file.close(); } catch (Exception e) { /* ignore */ }
        file = null;
    }

    public synchronized boolean loaded() { return buf != null; }

    /**
     * Everything of the given kind within a radius, nearest first.
     *
     * @param kind one of CAMERA, EXIT, FUEL, or 0 for any
     */
    public synchronized List<Near> near(double lat, double lon, int kind, double radiusM) {
        List<Near> out = new ArrayList<Near>();
        if (buf == null || radiusM <= 0) return out;
        if (Double.isNaN(lat) || Double.isNaN(lon)) return out;

        // How many cells the radius reaches, so a big radius still works and a
        // small one still only reads one cell.
        double degLat = radiusM / Geo.perLat(lat);
        double degLon = radiusM / Geo.perLon(lat);
        int spanX = (int) Math.ceil(degLon / cellDeg);
        int spanY = (int) Math.ceil(degLat / ((maxy - miny) / rows));
        if (spanX > 64) spanX = 64;
        if (spanY > 64) spanY = 64;

        int cx = (int) ((lon - minx) / cellDeg);
        int cy = (int) ((lat - miny) / ((maxy - miny) / rows));

        try {
            for (int y = cy - spanY; y <= cy + spanY; y++) {
                if (y < 0 || y >= rows) continue;
                for (int x = cx - spanX; x <= cx + spanX; x++) {
                    if (x < 0 || x >= cols) continue;
                    int cell = y * cols + x;
                    int from = buf.getInt(cellsAt + cell * 4);
                    int to = buf.getInt(cellsAt + (cell + 1) * 4);
                    if (from < 0 || to < from || to > count) continue;
                    for (int i = from; i < to; i++) {
                        int at = pointsAt + i * POINT_BYTES;
                        int k = buf.get(at + 8) & 0xFF;
                        if (kind != 0 && k != kind) continue;
                        double pla = buf.getInt(at) / 1e7;
                        double plo = buf.getInt(at + 4) / 1e7;
                        double d = Geo.metresFlat(lat, lon, pla, plo);
                        if (d > radiusM) continue;
                        Near n = new Near();
                        n.kind = k;
                        n.lat = pla;
                        n.lon = plo;
                        n.metres = d;
                        n.name = nameAt(buf.getShort(at + 9) & 0xFFFF);
                        out.add(n);
                    }
                }
            }
        } catch (Throwable t) {
            // A corrupt index can point anywhere. Whatever was found before it
            // is still good, and an alert layer is never worth a crash.
            Log.w("watchmap", "alerts lookup: " + t);
        }

        // Insertion sort: this is a handful of points, and Collections.sort
        // allocates a comparator and an array on every fix.
        for (int i = 1; i < out.size(); i++) {
            Near v = out.get(i);
            int j = i - 1;
            while (j >= 0 && out.get(j).metres > v.metres) {
                out.set(j + 1, out.get(j));
                j--;
            }
            out.set(j + 1, v);
        }
        return out;
    }

    /** The nearest of a kind, or null. */
    public Near nearest(double lat, double lon, int kind, double radiusM) {
        List<Near> l = near(lat, lon, kind, radiusM);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Walks the name table to the given index. The table is small and the
     *  result is only asked for on the few points that come back from a
     *  lookup, so there is no index into it. */
    private String nameAt(int idx) {
        if (idx >= nameCount || buf == null) return null;
        try {
            int at = namesAt;
            for (int i = 0; i < idx; i++) {
                at += 1 + (buf.get(at) & 0xFF);
            }
            int len = buf.get(at) & 0xFF;
            if (len == 0) return null;
            byte[] b = new byte[len];
            for (int i = 0; i < len; i++) b[i] = buf.get(at + 1 + i);
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
