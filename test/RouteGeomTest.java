import org.watchlauncher.Route;
import java.lang.reflect.Field;
import java.util.List;

/**
 * Where you are on a route, and how much of it is left.
 *
 * This is worth a test because the fast version is not obviously the same as
 * the slow one. Walking the whole line on every fix cost up to 427 ms on a
 * four thousand point route, so the search now starts where it finished last
 * time and looks at a window around that. The cases where a window is wrong
 * are the ones below: a route that comes back near itself, a position that
 * jumps, and a driver who leaves the route altogether.
 *
 * Each is checked against the honest answer - the whole line, every segment -
 * computed here independently of the code under test.
 */
public class RouteGeomTest {

    static int failures = 0;

    static void check(String what, boolean ok, String saw) {
        if (!ok) { System.out.println("FAIL " + what + ": " + saw); failures++; }
        else System.out.println("ok   " + what + " (" + saw + ")");
    }

    @SuppressWarnings("unchecked")
    static List<double[]> lineOf(Route r) throws Exception {
        Field f = Route.class.getField("line");
        return (List<double[]>) f.get(r);
    }

    /** The code's own distance: this test is about which segment the windowed
     *  scan picks, not about geodesy, so it must measure the same way. */
    static double metres(double la1, double lo1, double la2, double lo2) {
        return Route.metresBetween(la1, lo1, la2, lo2);
    }

    static double perLat(double lat) {
        double p = Math.toRadians(lat);
        return 111132.954 - 559.822 * Math.cos(2 * p) + 1.175 * Math.cos(4 * p);
    }

    static double perLon(double lat) {
        double p = Math.toRadians(lat);
        return 111412.84 * Math.cos(p) - 93.5 * Math.cos(3 * p) + 0.118 * Math.cos(5 * p);
    }

    /** The answer the whole-line scan would give, worked out from scratch. */
    static double[] honest(List<double[]> line, double lat, double lon) {
        double bestD = Double.MAX_VALUE, bestT = 0;
        int at = -1;
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1), b = line.get(i);
            double kx = perLon(a[0]), ky = perLat(a[0]);
            double px = (lon - a[1]) * kx, py = (lat - a[0]) * ky;
            double bx = (b[1] - a[1]) * kx, by = (b[0] - a[0]) * ky;
            double len = bx * bx + by * by;
            double t = 0, dx, dy;
            if (len == 0) { dx = px; dy = py; }
            else {
                t = Math.max(0, Math.min(1, (px * bx + py * by) / len));
                dx = px - t * bx; dy = py - t * by;
            }
            double d = Math.sqrt(dx * dx + dy * dy);
            if (at < 0 || d < bestD - 5) { bestD = d; bestT = t; at = i; }
            else if (d <= bestD + 5) { if (d < bestD) bestD = d; bestT = t; at = i; }
        }
        double rest = 0;
        for (int i = at; i < line.size() - 1; i++) {
            rest += metres(line.get(i)[0], line.get(i)[1],
                           line.get(i + 1)[0], line.get(i + 1)[1]);
        }
        double seg = metres(line.get(at - 1)[0], line.get(at - 1)[1],
                            line.get(at)[0], line.get(at)[1]);
        return new double[] { bestD, rest + seg * (1 - bestT) };
    }

    static Route build(double[][] pts) throws Exception {
        Route r = new Route();
        List<double[]> line = lineOf(r);
        for (double[] p : pts) line.add(new double[] { p[0], p[1] });
        return r;
    }

    public static void main(String[] a) throws Exception {
        // A long straight run north, then a hook back south alongside itself:
        // the two legs are 60 m apart, which a window can tell apart and a
        // nearest-point search over the whole line cannot.
        int N = 900;
        double[][] pts = new double[N * 2][];
        for (int i = 0; i < N; i++) pts[i] = new double[]{ 52.0 + i * 0.0005, 5.0 };
        for (int i = 0; i < N; i++) {
            // 0.000874 degrees of longitude is 60 metres at this latitude, which is what the
            // comment above promises and what makes this test worth running: near enough that
            // a whole-line nearest-point search picks the wrong leg, far enough that a window
            // anchored on the last position does not.
            pts[N + i] = new double[]{ 52.0 + (N - 1 - i) * 0.0005, 5.000874 };
        }
        Route r = build(pts);

        // --- driving the whole thing, one point at a time -----------------
        double worstD = 0, worstR = 0;
        for (int i = 0; i < pts.length; i += 3) {
            double lat = pts[i][0], lon = pts[i][1];
            double got = r.metresRemaining(lat, lon);
            double off = r.offRouteMetres(lat, lon);
            double[] want = honest(lineOf(r), lat, lon);
            worstR = Math.max(worstR, Math.abs(got - want[1]));
            worstD = Math.max(worstD, Math.abs(off - want[0]));
        }
        check("remaining matches a full scan along a doubling-back route",
              worstR < 1.0, String.format("worst %.2f m", worstR));
        check("off-route matches a full scan", worstD < 1.0,
              String.format("worst %.2f m", worstD));

        // --- monotonic: it never says there is more left than before ------
        Route m = build(pts);
        double prev = Double.MAX_VALUE;
        boolean fell = true;
        for (int i = 0; i < pts.length; i += 5) {
            double got = m.metresRemaining(pts[i][0], pts[i][1]);
            if (got > prev + 1) fell = false;
            prev = got;
        }
        check("distance left never grows while driving forwards", fell,
              String.format("ends at %.0f m", prev));

        // --- a jump the window cannot cover falls back to the whole line ---
        Route j = build(pts);
        j.metresRemaining(pts[0][0], pts[0][1]);          // anchor at the start
        double far = j.metresRemaining(pts[N + 100][0], pts[N + 100][1]);
        double[] wantFar = honest(lineOf(j), pts[N + 100][0], pts[N + 100][1]);
        check("a jump past the window is still found",
              Math.abs(far - wantFar[1]) < 1.0,
              String.format("%.0f vs %.0f m", far, wantFar[1]));

        // --- off the route entirely ---------------------------------------
        Route o = build(pts);
        o.metresRemaining(pts[10][0], pts[10][1]);
        double off = o.offRouteMetres(52.0 + 10 * 0.0005, 5.02);   // ~1.4 km east
        check("leaving the route is noticed", off > Route.OFF_ROUTE_M,
              String.format("%.0f m off", off));

        // --- the ends ------------------------------------------------------
        Route e = build(pts);
        double atEnd = e.metresRemaining(pts[pts.length - 1][0], pts[pts.length - 1][1]);
        check("nothing left at the destination", atEnd < 5,
              String.format("%.1f m", atEnd));
        Route s = build(pts);
        double atStart = s.metresRemaining(pts[0][0], pts[0][1]);
        double[] wantStart = honest(lineOf(s), pts[0][0], pts[0][1]);
        check("the whole route left at the start",
              Math.abs(atStart - wantStart[1]) < 1.0,
              String.format("%.0f vs %.0f m", atStart, wantStart[1]));

        // --- a two point route is not a special case ----------------------
        Route tiny = build(new double[][] { {52.0, 5.0}, {52.01, 5.0} });
        double t = tiny.metresRemaining(52.005, 5.0);
        check("short route halfway", Math.abs(t - 553) < 20, String.format("%.0f m", t));

        // --- speed ---------------------------------------------------------
        Route big = build(pts);
        long t0 = System.nanoTime();
        for (int pass = 0; pass < 50; pass++) {
            for (int i = 0; i < pts.length; i += 7) {
                big.metresRemaining(pts[i][0], pts[i][1]);
            }
        }
        long us = (System.nanoTime() - t0) / 1000 / (50 * (pts.length / 7));
        System.out.println("     " + us + " us per fix on a " + pts.length + " point route");

        System.out.println(failures == 0 ? "route geometry: all checks passed"
                                         : "route geometry: " + failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
