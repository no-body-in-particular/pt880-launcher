package org.watchlauncher;

import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A route, and what to say next.
 *
 * The server computes it once and the watch follows it offline. Both halves
 * matter on this device: a road graph for a country is not something a 1 GHz
 * watch should search, and a route is followed for an hour after being asked
 * for, long after the network may have gone.
 *
 * <h3>Instructions</h3>
 *
 * A turn carries a direction and a distance and no street name. On a wrist,
 * spoken, "in two hundred metres, turn left" is the whole of what is useful --
 * and the name is exactly what makes the sentence too long to finish before
 * the junction arrives.
 *
 * Each turn is announced as it comes up - a kilometre out, then five
 * hundred metres, then two hundred, then at the junction - and once
 * on top of it. Distances are rounded to something a person would say, because
 * "in one hundred and eighty-seven metres" is not an instruction, it is a
 * reading.
 */
public class Route {

    public static final int DEPART = 0, STRAIGHT = 1, SLIGHT_LEFT = 2, LEFT = 3,
            SHARP_LEFT = 4, SLIGHT_RIGHT = 5, RIGHT = 6, SHARP_RIGHT = 7,
            UTURN = 8, ROUNDABOUT = 9, ARRIVE = 10;

    /** Announced at this range, then again when it is imminent. */
    /**
     * How far ahead each notice is given, in metres, furthest first.
     *
     * One warning is not enough at road speed. The watch takes a fix every
     * ten seconds, which at 100 km/h is 278 metres of ground - so a single
     * window at 250 metres can be stepped straight over, leaving nothing but
     * the one spoken at the junction itself, about a second and a half of
     * notice. Several thresholds mean whichever one you happen to land inside
     * still gets said.
     */
    private static final int[] STAGES = {1000, 500, 200};

    /** Spoken at the junction itself, without a distance. */
    private static final int NOW_M = 50;

    /** Do not announce a turn further off than this in time, or a walker is
     *  told about a corner ten minutes before reaching it. Speed is unknown
     *  often enough that it has to have a sensible default. */
    private static final int MAX_LOOKAHEAD_S = 200;
    private static final float ASSUMED_MS = 8f;

    /** Past this from the line, the route is no longer being followed. */
    public static final int OFF_ROUTE_M = 80;

    /** Within this of the destination, the job is done. */
    public static final int ARRIVED_M = 30;

    /** Two segments this close to equally near count as a tie, squared
     *  because that is how the scan compares them. */
    private static final double NEAR2 = 5 * 5;

    public static class Turn {
        public int kind;
        public int metres;          // length of the step that follows
        public double lat, lon;
        /** Which advance notices have been given. Bit i is set once the
         *  notice for STAGES[i] has been spoken for this turn. */
        int spoken;
        boolean announced;
    }

    public final List<Turn> turns = new ArrayList<Turn>();
    public final List<double[]> line = new ArrayList<double[]>();
    public int totalMetres;

    /** What the plan thought the drive would take, in seconds, or 0 when that
     *  is not known - the server's route format does not carry it. Used only
     *  as the arrival estimate's starting guess, before there is enough real
     *  driving to average. */
    public int totalSeconds;

    /** Metres from each point of the line to the end of it, and the metres in
     *  a degree of longitude there. Built together on first use, because most
     *  routes are drawn and never asked. */
    private int[] toEnd;
    private double[] kxAt, kyAt;

    /**
     * Build a route from a path through the on-device graph.
     *
     * The graph holds junctions, not the bends between them, so the line is
     * junction to junction. That is coarser than the server's geometry but it
     * is the same road, and at five metres a pixel the difference only shows
     * on a long sweeping curve.
     *
     * A turn is only emitted where roads actually meet - degree three or more.
     * Without that test every bend in a lane becomes an instruction, and the
     * watch spends the drive announcing corners nobody would call a turn.
     */
    static Route fromNodes(RoadGraph g, int[] path) {
        if (g == null || path == null || path.length < 2) return null;

        Route r = new Route();
        double total = 0;
        long deci = 0;
        for (int i = 0; i < path.length; i++) {
            r.line.add(new double[] { g.lat(path[i]), g.lon(path[i]) });
            if (i > 0) {
                total += g.metres(path[i - 1], path[i]);
                deci += arcCost(g, path[i - 1], path[i]);
            }
        }
        r.totalMetres = (int) Math.round(total);
        r.totalSeconds = (int) (deci / 10);

        Turn depart = new Turn();
        depart.kind = DEPART;
        depart.lat = g.lat(path[0]);
        depart.lon = g.lon(path[0]);
        r.turns.add(depart);

        for (int i = 1; i < path.length - 1; i++) {
            if (g.degree(path[i]) < 3) continue;          // a bend, not a fork

            double in = bearing(g.lat(path[i - 1]), g.lon(path[i - 1]),
                                g.lat(path[i]), g.lon(path[i]));
            double out = bearing(g.lat(path[i]), g.lon(path[i]),
                                 g.lat(path[i + 1]), g.lon(path[i + 1]));
            double d = out - in;
            while (d > 180) d -= 360;
            while (d < -180) d += 360;

            int kind = kindOf(d);
            if (kind == STRAIGHT) continue;

            Turn t = new Turn();
            t.kind = kind;
            t.lat = g.lat(path[i]);
            t.lon = g.lon(path[i]);
            t.metres = (int) Math.round(g.metres(path[i], path[i + 1]));
            r.turns.add(t);
        }

        Turn arrive = new Turn();
        arrive.kind = ARRIVE;
        arrive.lat = g.lat(path[path.length - 1]);
        arrive.lon = g.lon(path[path.length - 1]);
        r.turns.add(arrive);
        return r;
    }

    /** Thresholds a driver would recognise, not evenly spaced ones: anything
     *  under twenty degrees is following the road. */
    private static int kindOf(double d) {
        double a = Math.abs(d);
        if (a < 20) return STRAIGHT;
        if (a > 150) return UTURN;
        if (d > 0) {
            if (a < 50) return SLIGHT_RIGHT;
            return a > 110 ? SHARP_RIGHT : RIGHT;
        }
        if (a < 50) return SLIGHT_LEFT;
        return a > 110 ? SHARP_LEFT : LEFT;
    }

    /** Parse the server's binary. Returns null if it is not a route. */
    public static Route read(File f) {
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'W' || magic[1] != 'R' || magic[2] != 'T' || magic[3] != '1') {
                return null;
            }
            Route r = new Route();
            r.totalMetres = in.readInt();
            int steps = in.readUnsignedShort();
            int points = in.readUnsignedShort();

            for (int i = 0; i < steps; i++) {
                Turn t = new Turn();
                t.kind = in.readUnsignedByte();
                t.metres = in.readUnsignedShort();
                t.lat = in.readInt() / 1e7;
                t.lon = in.readInt() / 1e7;
                r.turns.add(t);
            }

            if (points > 0) {
                double lat = in.readInt() / 1e7;
                double lon = in.readInt() / 1e7;
                r.line.add(new double[]{lat, lon});
                for (int i = 1; i < points; i++) {
                    lat += in.readShort() / 1e6;
                    lon += in.readShort() / 1e6;
                    r.line.add(new double[]{lat, lon});
                }
            }
            return r.line.size() >= 2 ? r : null;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    public double[] destination() {
        return line.isEmpty() ? null : line.get(line.size() - 1);
    }

    /**
     * What should be said now, or null.
     *
     * Each turn speaks twice and then goes quiet, so a queue at a junction does
     * not produce the same instruction on every fix.
     */
    public String instruction(double lat, double lon) {
        return instruction(lat, lon, 0f);
    }

    /**
     * What to say now, or null.
     *
     * @param speedMs ground speed, or 0 if not known
     */
    public String instruction(double lat, double lon, float speedMs) {
        Turn next = null;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            if (t.announced) continue;
            double d = metresBetween(lat, lon, t.lat, t.lon);
            if (d < best) { best = d; next = t; }
        }
        if (next == null) return null;

        if (best <= NOW_M) {
            next.announced = true;
            next.spoken = -1;                       // every stage, done with
            return phrase(next.kind, 0);
        }

        float ms = speedMs > 0.5f ? speedMs : ASSUMED_MS;

        // Furthest first, and the first one that is both due and unsaid wins.
        // Crossing several between fixes therefore speaks only the nearest,
        // rather than three notices in a row at one junction.
        for (int i = 0; i < STAGES.length; i++) {
            int bit = 1 << i;
            if ((next.spoken & bit) != 0) continue;
            if (best > STAGES[i]) continue;
            // Everything further out is now moot whether or not it was said.
            for (int j = 0; j <= i; j++) next.spoken |= (1 << j);
            if (best / ms > MAX_LOOKAHEAD_S) return null;
            return phrase(next.kind, (int) best);
        }
        return null;
    }

    /** The nearest upcoming turn, for the screen. */
    public Turn nextTurn(double lat, double lon) {
        Turn next = null;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            if (t.announced) continue;
            double d = metresBetween(lat, lon, t.lat, t.lon);
            if (d < best) { best = d; next = t; }
        }
        return next;
    }

    public int metresTo(double lat, double lon, Turn t) {
        return (int) Math.round(metresBetween(lat, lon, t.lat, t.lon));
    }

    /** How far off the line we are, for the off-route test. */
    /** The cost the graph put on going from a to b, in deciseconds, or 0 if
     *  they are not actually joined. */
    private static int arcCost(RoadGraph g, int a, int b) {
        int end = g.firstArc(a + 1);
        for (int k = g.firstArc(a); k < end; k++) {
            if (g.arcTarget(k) == b) return g.arcCost(k);
        }
        return 0;
    }

    /** The speed the route was planned at, metres per second, or -1. */
    public float plannedMs() {
        if (totalSeconds <= 0 || totalMetres <= 0) return -1;
        return totalMetres / (float) totalSeconds;
    }

    /**
     * How much driving is left, in metres, from wherever you are now.
     *
     * Measured along the route rather than to the destination as the crow
     * flies: the point of the number is that the arrival estimate is divided
     * by it, and driving round a firth is not the same journey as looking
     * across it.
     */
    public double metresRemaining(double lat, double lon) {
        return locate(lat, lon) ? atRemaining : -1;
    }

    /** How far the given position is from the route. */
    public double offRouteMetres(double lat, double lon) {
        return locate(lat, lon) ? atOff : Double.MAX_VALUE;
    }

    /** Where the last call to locate() put us, so a second question about the
     *  same position costs nothing. */
    private double atLat = Double.NaN, atLon = Double.NaN;
    private double atOff, atRemaining;

    /**
     * Which piece of the route we are on, and how much of it is left.
     *
     * <h3>Why this is not a scan of the whole line</h3>
     *
     * It was, and on a 358 km route with four thousand points it took between
     * 47 and 427 milliseconds - on the emulator, which is faster than the
     * watch - and it ran on the UI thread on every fix. Two questions were
     * being asked, how far off the route we are and how much of it is left,
     * and each walked the whole line; the arrival estimate then made it three
     * walks. Every one of them called Math.cos once per segment.
     *
     * Two things fix it. The cosines are precomputed with the distances, so
     * the loop has no trigonometry in it at all. And a drive moves along a
     * route rather than jumping about it, so the search starts from where it
     * ended last time and looks only at a window around it. The whole line is
     * only walked when nothing in that window is near - which is what leaving
     * the route, or being handed a new one, actually looks like.
     *
     * @return false if there is no line to be on
     */
    private boolean locate(double lat, double lon) {
        if (line.size() < 2) return false;
        if (lat == atLat && lon == atLon) return true;
        suffix();

        /*
         * The window is only trusted while it is continuing a drive.
         *
         * Until the first match there is nothing for it to continue from, and
         * starting it at the head of the line is not a neutral guess: on a
         * route that comes back near where it started - any loop, and any
         * there-and-back - the first point of the line is within eighty
         * metres of the last, so a window at the head happily matches a
         * position that is actually at the destination and reports the whole
         * route still to drive.
         */
        int found = -1;
        if (located) {
            found = scan(lat, lon, Math.max(1, cursor - BACK),
                         Math.min(line.size() - 1, cursor + AHEAD));
        }
        // Nothing near in the window means the drive is not where it was: a
        // jump, a reroute, or leaving the road. Then the whole line is worth
        // the walk.
        if (found < 0 || bestD > OFF_ROUTE_M) {
            int all = scan(lat, lon, 1, line.size() - 1);
            if (all >= 0) found = all;
        }
        if (found < 0) return false;
        located = true;

        cursor = found;
        double[] a = line.get(found - 1), b = line.get(found);
        double segment = metresBetween(a[0], a[1], b[0], b[1]);
        atOff = bestD;
        atRemaining = toEnd[found] + segment * (1 - bestT);
        atLat = lat;
        atLon = lon;
        return true;
    }

    /** How far back and forward of the last position to look. Ten seconds of
     *  motorway is under three hundred metres and the points are tens of
     *  metres apart, so this is minutes of driving either way. */
    private static final int BACK = 32, AHEAD = 256;

    private int cursor = 1;
    /** Whether cursor means anything yet. */
    private boolean located = false;
    private double bestD, bestT;

    /**
     * The nearest segment between from and to, leaving its distance in bestD
     * and how far along it in bestT.
     *
     * Among segments equally close - the two legs of a road driven out and
     * back, or a hairpin - the last one wins. Being wrong that way says there
     * is less driving left than there is, and the next fix corrects it; being
     * wrong the other way makes the estimate jump backwards every time the
     * drive passes near an earlier part of the route.
     */
    private int scan(double lat, double lon, int from, int to) {
        int at = -1;
        double best = Double.MAX_VALUE, bestAlong = 0;
        for (int i = from; i <= to; i++) {
            double[] a = line.get(i - 1), b = line.get(i);
            double kx = kxAt[i - 1], ky = kyAt[i - 1];
            double px = (lon - a[1]) * kx, py = (lat - a[0]) * ky;
            double bx = (b[1] - a[1]) * kx, by = (b[0] - a[0]) * ky;
            double len = bx * bx + by * by;
            double t, dx, dy;
            if (len == 0) {
                t = 0; dx = px; dy = py;
            } else {
                t = (px * bx + py * by) / len;
                if (t < 0) t = 0; else if (t > 1) t = 1;
                dx = px - t * bx; dy = py - t * by;
            }
            // Compared squared, so the loop has no square roots either.
            double d2 = dx * dx + dy * dy;
            if (at < 0 || d2 < best - NEAR2) {          // clearly nearer
                best = d2; at = i; bestAlong = t;
            } else if (d2 <= best + NEAR2) {            // a tie: the later wins
                if (d2 < best) best = d2;
                at = i; bestAlong = t;
            }
        }
        if (at < 0) return -1;
        bestD = Math.sqrt(best);
        bestT = bestAlong;
        return at;
    }

    /**
     * Build the tables now, on whatever thread is asking.
     *
     * They are built on first use otherwise, and first use is the first fix
     * after a route loads - on the UI thread, where a four thousand point
     * route costs a third of a second and the watch visibly stops. Every
     * route arrives on a background thread, so it can be paid for there.
     */
    public void prepare() {
        if (line.size() >= 2) suffix();
    }

    /** Distance to the end from each point, and the metres-per-degree of
     *  longitude there. Both are fixed for the life of a route and both were
     *  being recomputed inside the loop that uses them. */
    private void suffix() {
        if (toEnd != null && toEnd.length == line.size()) return;
        int n = line.size();
        int[] t = new int[n];
        double[] kx = new double[n];
        double[] ky = new double[n];
        double run = 0;
        kx[n - 1] = Geo.perLon(line.get(n - 1)[0]);
        ky[n - 1] = Geo.perLat(line.get(n - 1)[0]);
        for (int i = n - 2; i >= 0; i--) {
            double[] a = line.get(i), b = line.get(i + 1);
            kx[i] = Geo.perLon(a[0]);
            ky[i] = Geo.perLat(a[0]);
            // Flat, using the scales just computed, rather than a haversine
            // per point. Consecutive points of a route are tens of metres
            // apart, where the two agree to well under a millimetre, and the
            // haversine's four trigonometric calls per point were most of the
            // half second this took on a four thousand point route.
            double dy = (b[0] - a[0]) * ky[i];
            double dx = (b[1] - a[1]) * kx[i];
            run += Math.sqrt(dx * dx + dy * dy);
            t[i] = (int) Math.round(run);
        }
        toEnd = t;
        kxAt = kx;
        kyAt = ky;
        cursor = 1;
        located = false;
        atLat = Double.NaN;
    }


    /** What to do at a turn, as a phrase. Shared by the voice and the screen
     *  so the two never word the same manoeuvre differently. */
    public static String action(int kind) {
        switch (kind) {
            case SLIGHT_LEFT:  return "bear left";
            case LEFT:         return "turn left";
            case SHARP_LEFT:   return "turn sharp left";
            case SLIGHT_RIGHT: return "bear right";
            case RIGHT:        return "turn right";
            case SHARP_RIGHT:  return "turn sharp right";
            case UTURN:        return "make a u turn";
            case ROUNDABOUT:   return "at the roundabout";
            case ARRIVE:       return "you have arrived";
            case DEPART:       return null;
            default:           return "continue straight ahead";
        }
    }

    /** Short enough for a 240px line: "300 m", "1.2 km". */
    public static String screenDistance(int m) {
        if (m >= 1000) return (Math.round(m / 100.0) / 10.0) + " km";
        if (m > 300) return (Math.round(m / 100.0) * 100) + " m";
        if (m > 80) return (Math.round(m / 50.0) * 50) + " m";
        return (Math.round(m / 10.0) * 10) + " m";
    }

    /**
     * The next turn as a line for the map: "in 300 m turn left".
     *
     * Never blank while a route is loaded. Once the last manoeuvre has been
     * spoken there is no next turn, and the line used to disappear for the
     * rest of the drive - which is exactly when you most want to see that the
     * watch is still navigating. So it falls back to the destination.
     *
     * @return null only when there is nothing left to say at all
     */
    public String screenInstruction(double lat, double lon) {
        Turn t = drawableTurn(lat, lon);
        if (t != null) {
            String what = action(t.kind);
            int m = metresTo(lat, lon, t);
            if (t.kind == ARRIVE) return what;
            if (m <= 20) return what;              // at it now, no distance
            return "in " + screenDistance(m) + " " + what;
        }

        double[] end = destination();
        if (end == null) return null;
        int m = (int) Math.round(metresBetween(lat, lon, end[0], end[1]));
        if (m <= ARRIVED_M) return "you have arrived";
        return "destination in " + screenDistance(m);
    }

    /**
     * The nearest turn still worth drawing.
     *
     * Unlike nextTurn this skips manoeuvres that have no phrase - the depart
     * marker at the head of every route - rather than returning one and
     * leaving the caller with nothing to print. It reads the announced flag
     * but never sets it: what the screen shows must not consume the voice's
     * record of what it has already said.
     */
    private Turn drawableTurn(double lat, double lon) {
        Turn best = null;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            if (t.announced) continue;
            if (action(t.kind) == null) continue;
            double d = metresBetween(lat, lon, t.lat, t.lon);
            if (d < bestD) { bestD = d; best = t; }
        }
        return best;
    }

    private static String phrase(int kind, int metres) {
        String turn = action(kind);
        if (turn == null) return null;
        // Arrival is announced as itself; "in 300 metres, you have arrived"
        // is not something a person says.
        if (kind == ARRIVE) return turn;
        if (metres <= 0) return turn;
        return "in " + spokenDistance(metres) + ", " + turn;
    }

    /** Rounded to what a person would say. Nobody says "187 metres". */
    static String spokenDistance(int m) {
        if (m >= 1000) return (Math.round(m / 100.0) / 10.0) + " kilometres";
        if (m > 300) return (Math.round(m / 100.0) * 100) + " metres";
        if (m > 80) return (Math.round(m / 50.0) * 50) + " metres";
        return (Math.round(m / 10.0) * 10) + " metres";
    }

    public static String turnWord(int kind) {
        switch (kind) {
            case SLIGHT_LEFT:  return "bear left";
            case LEFT:         return "left";
            case SHARP_LEFT:   return "sharp left";
            case SLIGHT_RIGHT: return "bear right";
            case RIGHT:        return "right";
            case SHARP_RIGHT:  return "sharp right";
            case UTURN:        return "u-turn";
            case ROUNDABOUT:   return "roundabout";
            case ARRIVE:       return "arrive";
            case DEPART:       return "start";
            default:           return "straight";
        }
    }

    // ---------------------------------------------------------------- geometry

    public static double metresBetween(double lat1, double lon1,
                                       double lat2, double lon2) {
        return Geo.metres(lat1, lon1, lat2, lon2);
    }

    /**
     * Metres in a degree of latitude, and of longitude, at a given latitude.
     *
     * Everything here used to use 110540 for the first and 111320 times the
     * cosine for the second. Those are the values at the equator: a degree of
     * latitude is 110574 m there and 111267 m in the Netherlands, so every
     * distance this watch reported was 0.65% short - and 0.78% short in
     * Scotland, because the error grows with latitude. On a 358 km route that
     * is two kilometres, and it is not a rounding difference that cancels: it
     * is one-sided, and it made arrival times optimistic everywhere north of
     * the tropics.
     *
     * These are the usual series for the WGS84 ellipsoid, good to a metre in
     * a degree, which is far past what a route measured between junctions can
     * make use of.
     */
    static double metresPerLat(double lat) { return Geo.perLat(lat); }

    static double metresPerLon(double lat) { return Geo.perLon(lat); }

    /** Bearing from one point to another, degrees clockwise from north. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double b = Math.toDegrees(Math.atan2(y, x));
        return (b + 360) % 360;
    }

}
