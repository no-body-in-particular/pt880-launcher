package org.watchlauncher;

import java.util.List;

/**
 * Deciding which points of a route line are worth drawing.
 *
 * Split out of the map view so it can be run on a desktop JVM against real
 * routes: it touches no Android class, so a test can prove what it hands the
 * renderer without a device. That matters here more than usual - every crash
 * this app has had has been in libhwui, and each time the cause was handing
 * it more work than a 2013 GPU could take.
 *
 * The rule is simple. A route from the server carries its full geometry, tens
 * of thousands of points, and at navigating zoom nearly all of them are off
 * the screen. Keep the ones that can be seen, keep the one either side of the
 * edge so the line arrives from off-screen rather than starting at it, and
 * drop any that land on a pixel already used.
 */
public final class RouteLine {

    /** How far past the edge a point still counts, in pixels. */
    public static final float MARGIN = 48;

    /**
     * The most points that will ever be emitted.
     *
     * A hard bound, not an expectation. With a 240px screen and duplicate
     * pixels dropped, a line can only be so long before it is retracing
     * itself; if geometry ever arrives that defeats that reasoning, the line
     * is cut short rather than the renderer being handed something it cannot
     * survive. A truncated route draws wrong. A crashed one draws nothing and
     * takes the watch with it.
     */
    public static final int MAX_POINTS = 2048;

    /** Unscaled, for callers drawing the map at its own size. */
    public static int project(List<double[]> line, int zoom, double cx, double cy,
                              int w, int h, float[] out) {
        return project(line, zoom, cx, cy, w, h, 1f, out);
    }

    /** A break in the line: the next point starts a new stroke. */
    public static final float BREAK = Float.NaN;

    private RouteLine() { }

    /**
     * @param line   route points as {lat, lon}
     * @param out    receives x, y pairs; BREAK in x marks a new stroke
     * @return how many floats of out were filled
     */
    /**
     * @param scale screen pixels per world pixel: 1 draws the tiles at their
     *              own size, 0.5 shows twice the ground, 2 magnifies. The map
     *              only ever stores one zoom level, so seeing further is done
     *              by drawing what is on the card smaller rather than by
     *              fetching a coarser tile that is not there.
     */
    public static int project(List<double[]> line, int zoom, double cx, double cy,
                              int w, int h, float scale, float[] out) {
        int at = 0;
        boolean pen = false;
        int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
        boolean prevVisible = false;
        float prevPx = 0, prevPy = 0;

        for (int i = 0, n = line.size(); i < n; i++) {
            if (at + 4 > out.length) break;

            double[] p = line.get(i);
            float px = (float) ((Mercator.xOf(p[1], zoom) * Mercator.TILE_PX - cx)
                    * scale + w / 2.0);
            float py = (float) ((Mercator.yOf(p[0], zoom) * Mercator.TILE_PX - cy)
                    * scale + h / 2.0);
            boolean vis = px > -MARGIN && px < w + MARGIN && py > -MARGIN && py < h + MARGIN;

            if (vis) {
                if (!pen) {
                    out[at++] = BREAK;
                    out[at++] = 0;
                    if (i > 0 && !prevVisible) {
                        out[at++] = prevPx;
                        out[at++] = prevPy;
                    } else {
                        out[at++] = px;
                        out[at++] = py;
                    }
                    pen = true;
                    lastX = Integer.MIN_VALUE;
                }
                int ix = (int) px, iy = (int) py;
                if (ix != lastX || iy != lastY) {
                    out[at++] = px;
                    out[at++] = py;
                    lastX = ix;
                    lastY = iy;
                }
            } else if (pen) {
                // One point past the edge, then lift, so the line leaves the
                // screen instead of stopping at it.
                out[at++] = px;
                out[at++] = py;
                pen = false;
            }
            prevVisible = vis;
            prevPx = px;
            prevPy = py;
        }
        return at;
    }
}
