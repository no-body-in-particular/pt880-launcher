package org.watchlauncher;

import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * The country's road network, read straight off the card.
 *
 * Fifty megabytes for the Netherlands, which is far more than this watch's
 * heap - so it is memory-mapped rather than read. The pages come and go under
 * the kernel's control and count against no Java allocation at all, which is
 * the only way a graph this size can live on a device whose whole heap is a
 * fraction of it.
 *
 * Everything is fixed width and big-endian, so a lookup is an offset
 * calculation rather than a parse. See build_graph.php on the server for how
 * it is made and why the shape is what it is.
 *
 *     "WGR1"  u8 version  u8 0  u16 0
 *     u32 nodes  u32 arcs  u32 cols  u32 rows
 *     f64 minx, miny, maxx, maxy
 *     nodes:  i32 lat*1e7, i32 lon*1e7
 *     adj:    u32 first-arc index per node, plus a tail
 *     arcs:   u32 target, u32 cost in deciseconds
 *     grid:   u32 first-node index per cell, plus a tail
 *             u32 node id, grouped by cell
 */
public class RoadGraph {

    private static final double CELL_DEG = 0.01;

    private ByteBuffer buf;
    private RandomAccessFile file;

    private int nodes, arcs, cols, rows;
    private double minx, miny, maxx, maxy;
    private int nodesAt, adjAt, arcsAt, gridAt;

    private String country;
    private long stamp;

    /**
     * One graph file, not one per country.
     *
     * It holds a box around wherever you last downloaded, and the box may
     * well straddle a border - which is a good reason for its name not to
     * claim a country. Its own header says what ground it covers.
     */
    public static File fileFor(String country) {
        return new File(MapTiles.DIR + "/roads.graph");
    }

    public static File fileFor() { return fileFor(null); }

    public boolean loaded() { return buf != null; }

    public String country() { return country; }

    public int nodeCount() { return nodes; }

    /** @return true if this country's graph is on the card and usable. */
    public synchronized boolean open(String c) {
        File f = fileFor();
        if (buf != null && f.lastModified() == stamp) return true;
        close();
        if (!f.isFile() || f.length() < 56) return false;
        try {
            file = new RandomAccessFile(f, "r");
            FileChannel ch = file.getChannel();
            ByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, f.length());
            b.order(ByteOrder.BIG_ENDIAN);

            if (b.get(0) != 'W' || b.get(1) != 'G' || b.get(2) != 'R'
                    || b.get(3) != '2' || b.get(4) != 2) {
                close();
                return false;
            }
            nodes = b.getInt(8);
            arcs = b.getInt(12);
            cols = b.getInt(16);
            rows = b.getInt(20);
            minx = b.getDouble(24);
            miny = b.getDouble(32);
            maxx = b.getDouble(40);
            maxy = b.getDouble(48);

            nodesAt = 56;
            adjAt = nodesAt + nodes * 8;
            arcsAt = adjAt + (nodes + 1) * 4;
            gridAt = arcsAt + arcs * 6;

            long need = (long) gridAt + ((long) cols * rows + 1) * 4;
            if (need > f.length()) {
                Log.w("watchnav", "graph truncated: need " + need + " have " + f.length());
                close();
                return false;
            }

            buf = b;
            country = c;
            stamp = f.lastModified();
            Log.i("watchnav", "graph " + c + ": " + nodes + " nodes, " + arcs + " arcs");
            return true;
        } catch (Throwable t) {
            // Includes OutOfMemoryError from map() on a device short of
            // address space, which is a refusal to work rather than a crash.
            Log.w("watchnav", "graph open failed: " + t);
            close();
            return false;
        }
    }

    public synchronized void close() {
        buf = null;
        country = null;
        try { if (file != null) file.close(); } catch (Exception e) { }
        file = null;
    }

    public double lat(int n) { return buf.getInt(nodesAt + n * 8) / 1e7; }
    public double lon(int n) { return buf.getInt(nodesAt + n * 8 + 4) / 1e7; }

    public int firstArc(int n) { return buf.getInt(adjAt + n * 4); }
    public int arcTarget(int k) { return buf.getInt(arcsAt + k * 6); }
    public int arcCost(int k) { return buf.getShort(arcsAt + k * 6 + 4) & 0xFFFF; }

    /** How many ways meet here. Two means a bend in a road, not a junction. */
    public int degree(int n) { return firstArc(n + 1) - firstArc(n); }

    // The grid, exposed so a search can bound itself to a corridor rather
    // than to the whole country. Nodes are numbered in cell order, so a run
    // of cells is a run of consecutive node ids - which is what makes a
    // corridor cheap to express.

    public int gridCols() { return cols; }
    public int gridRows() { return rows; }
    public double west() { return minx; }
    public double south() { return miny; }
    public static double cellDegrees() { return CELL_DEG; }

    public int cellOf(double la, double lo) {
        int cx = (int) ((lo - minx) / CELL_DEG);
        int cy = (int) ((la - miny) / CELL_DEG);
        if (cx < 0) cx = 0; if (cx >= cols) cx = cols - 1;
        if (cy < 0) cy = 0; if (cy >= rows) cy = rows - 1;
        return cy * cols + cx;
    }

    public int cellX(double lo) {
        int cx = (int) ((lo - minx) / CELL_DEG);
        return cx < 0 ? 0 : (cx >= cols ? cols - 1 : cx);
    }

    public int cellY(double la) {
        int cy = (int) ((la - miny) / CELL_DEG);
        return cy < 0 ? 0 : (cy >= rows ? rows - 1 : cy);
    }

    /** First node id in this cell; the cell's nodes run to the next one. */
    public int cellFirstNode(int cell) {
        if (cell < 0) cell = 0;
        if (cell > cols * rows) cell = cols * rows;
        return buf.getInt(gridAt + cell * 4);
    }

    public boolean covers(double la, double lo) {
        return lo >= minx && lo <= maxx && la >= miny && la <= maxy;
    }

    /**
     * The nearest node to a position.
     *
     * Rings outward through the grid rather than scanning every node, which
     * on a million and a half of them is the difference between instant and
     * unusable. Gives up after a few kilometres: further than that and the
     * position is not on this map.
     *
     * @return node id, or -1
     */
    public int snap(double la, double lo) {
        if (buf == null) return -1;
        int cx = (int) ((lo - minx) / CELL_DEG);
        int cy = (int) ((la - miny) / CELL_DEG);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        double kx = Math.cos(Math.toRadians(la));

        for (int ring = 0; ring < 6; ring++) {
            for (int dy = -ring; dy <= ring; dy++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    // Only the edge of the ring; the inside was done already.
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dy) != ring) continue;
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                    int c = y * cols + x;
                    int from = buf.getInt(gridAt + c * 4);
                    int to = buf.getInt(gridAt + (c + 1) * 4);
                    for (int id = from; id < to; id++) {
                        double dla = lat(id) - la;
                        double dlo = (lon(id) - lo) * kx;
                        double d = dla * dla + dlo * dlo;
                        if (d < bestD) { bestD = d; best = id; }
                    }
                }
            }
            // A hit in this ring cannot be beaten by more than one ring out.
            if (best >= 0 && ring >= 1) break;
        }
        return best;
    }

    /** Metres between two nodes, near enough at these distances. */
    public double metres(int a, int b) {
        double la = lat(a), lb = lat(b);
        double dy = (lb - la) * 110540;
        double dx = (lon(b) - lon(a)) * 111320 * Math.cos(Math.toRadians((la + lb) / 2));
        return Math.sqrt(dx * dx + dy * dy);
    }
}
