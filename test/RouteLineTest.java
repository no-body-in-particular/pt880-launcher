import org.watchlauncher.Mercator;
import org.watchlauncher.RouteLine;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * What the map hands the renderer, measured on real routes.
 *
 * Every crash this app has had was libhwui being given more than a 2013 GPU
 * could take, so the thing worth proving is a bound: however long the route,
 * however far it is dragged around, the Path stays small. Runs on a desktop
 * JVM against routes fetched from the live server - no device, no emulator.
 */
public class RouteLineTest {

    static int failures = 0;

    static void check(String what, boolean ok, String detail) {
        System.out.printf("  %-52s %s%s%n", what, ok ? "ok" : "FAILED",
                detail.isEmpty() ? "" : ("   " + detail));
        if (!ok) failures++;
    }

    /** The watch's route format, decoded here so the test needs nothing from
     *  Android. Matches Route.read and route.php:
     *  "WRT1" u32 metres u16 steps u16 points, steps, then the line as a
     *  first point in i32 at 1e7 and i16 deltas at 1e6. */
    static List<double[]> readLine(String path) throws Exception {
        DataInputStream in = new DataInputStream(new FileInputStream(path));
        try {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'W' || magic[1] != 'R' || magic[2] != 'T' || magic[3] != '1') {
                return null;
            }
            in.readInt();
            int steps = in.readUnsignedShort();
            int points = in.readUnsignedShort();
            for (int i = 0; i < steps; i++) {
                in.readUnsignedByte();
                in.readUnsignedShort();
                in.readInt();
                in.readInt();
            }
            List<double[]> line = new ArrayList<double[]>(points);
            if (points <= 0) return line;
            double lat = in.readInt() / 1e7, lon = in.readInt() / 1e7;
            line.add(new double[] { lat, lon });
            for (int i = 1; i < points; i++) {
                lat += in.readShort() / 1e6;
                lon += in.readShort() / 1e6;
                line.add(new double[] { lat, lon });
            }
            return line;
        } finally {
            in.close();
        }
    }

    /**
     * The scaled projection, which is how the map zooms without a second set
     * of tiles.
     *
     * Two things have to hold. A point at the centre stays at the centre
     * whatever the scale - otherwise zooming walks the map away from you -
     * and a point off-centre moves proportionally, so half scale puts it half
     * as far from the middle. Anything else and the tiles and the route line
     * disagree about where north is.
     */
    static void checkScale() {
        java.util.List<double[]> line = new java.util.ArrayList<double[]>();
        double clat = 51.4667000, clon = 4.5000000;
        line.add(new double[]{ clat, clon });                 // dead centre
        line.add(new double[]{ clat + 0.004, clon + 0.004 }); // a little away
        int w = 240, h = 240;
        double cx = Mercator.xOf(clon, 15) * Mercator.TILE_PX;
        double cy = Mercator.yOf(clat, 15) * Mercator.TILE_PX;

        float[] out = new float[64];
        float[] offAt = new float[3];
        float[] scales = { 0.5f, 1f, 2f };
        for (int i = 0; i < scales.length; i++) {
            int n = RouteLine.project(line, 15, cx, cy, w, h, scales[i], out);
            // The stream is a BREAK marker, then the pen-down point, then the
            // points themselves - and the pen-down repeats the first one. So
            // rather than index into it, find the centre and then the first
            // thing that is not the centre.
            float cxs = w / 2f, cys = h / 2f;
            boolean sawCentre = false;
            float ox = Float.NaN, oy = Float.NaN;
            for (int k = 0; k + 1 < n; k += 2) {
                if (Float.isNaN(out[k])) continue;
                if (Math.abs(out[k] - cxs) < 0.6f && Math.abs(out[k + 1] - cys) < 0.6f) {
                    sawCentre = true;
                    continue;
                }
                ox = out[k]; oy = out[k + 1];
                break;
            }
            if (!sawCentre) {
                System.out.printf("FAIL the centre point moved at scale %.1f%n", scales[i]);
                failures++;
            }
            if (Float.isNaN(ox)) {
                System.out.printf("FAIL no off-centre point emitted at scale %.1f%n", scales[i]);
                failures++;
                offAt[i] = 0;
                continue;
            }
            offAt[i] = (float) Math.hypot(ox - cxs, oy - cys);
        }
        // half the scale, half the distance from the middle; double, double.
        boolean ok = offAt[1] > 10f
                  && Math.abs(offAt[0] - offAt[1] / 2f) < 0.6f
                  && Math.abs(offAt[2] - offAt[1] * 2f) < 1.2f;
        if (!ok) {
            System.out.printf("FAIL offsets do not scale: %.1f %.1f %.1f%n",
                    offAt[0], offAt[1], offAt[2]);
            failures++;
        } else {
            System.out.printf("  ok   the centre holds and offsets scale (%.0f, %.0f, %.0f px)%n",
                    offAt[0], offAt[1], offAt[2]);
        }
    }

    public static void main(String[] args) throws Exception {
        float[] out = new float[RouteLine.MAX_POINTS * 2];
        int worst = 0;
        String worstWhere = "";

        for (String path : args) {
            List<double[]> line = readLine(path);
            if (line == null || line.size() < 2) {
                System.out.println("  could not read " + path);
                failures++;
                continue;
            }
            System.out.printf("%n%s: %d points%n", new File(path).getName(), line.size());

            // Every position along the route, plus a long way off it: the map
            // is centred on wherever the watch happens to be, including
            // somewhere the route never goes.
            List<double[]> centres = new ArrayList<double[]>();
            for (int i = 0; i < line.size(); i += Math.max(1, line.size() / 400)) {
                centres.add(line.get(i));
            }
            centres.add(new double[] { 52.0, 5.0 });
            centres.add(new double[] { 0.0, 0.0 });
            centres.add(new double[] { 60.0, 20.0 });

            int max = 0;
            for (double[] c : centres) {
                double cx = Mercator.xOf(c[1], 15) * Mercator.TILE_PX;
                double cy = Mercator.yOf(c[0], 15) * Mercator.TILE_PX;
                int n = RouteLine.project(line, 15, cx, cy, 240, 240, out);

                if (n > out.length) {
                    check("never writes past the buffer", false, "n=" + n);
                    return;
                }
                for (int i = 0; i < n; i += 2) {
                    if (!Float.isNaN(out[i]) && (Float.isInfinite(out[i]) || Float.isInfinite(out[i + 1]))) {
                        check("no infinite coordinates", false, "at " + i);
                        return;
                    }
                }
                max = Math.max(max, n / 2);
            }
            System.out.printf("   worst case from %d viewpoints: %d points (cap %d)%n",
                    centres.size(), max, RouteLine.MAX_POINTS);
            if (max > worst) {
                worst = max;
                worstWhere = new File(path).getName();
            }
            check("stays under the cap for " + new File(path).getName(),
                    max <= RouteLine.MAX_POINTS, max + " points");
        }

        System.out.println();
        checkScale();

        System.out.println();
        check("worst case across every route and viewpoint",
                worst <= RouteLine.MAX_POINTS, worst + " points in " + worstWhere);
        check("that is a fraction of a screen's worth of vertices",
                worst < 2000, worst + " for a 240x240 screen");

        System.out.println();
        System.out.println(failures == 0 ? "PASS" : (failures + " FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }
}
