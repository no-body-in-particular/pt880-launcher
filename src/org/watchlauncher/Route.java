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

    /** Two segments this close to equally near count as a tie. */
    private static final double NEAR_M = 5;

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

    /** Metres from each point of the line to the end of it. Built on first
     *  use because most routes are drawn and never asked. */
    private int[] toEnd;

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
     * flies: the point of the number is that it is what the arrival estimate
     * is divided by, and driving round a firth is not the same journey as
     * looking across it.
     *
     * The position is put onto the nearest segment first, so standing fifty
     * metres off the line does not add fifty metres to the drive, and a route
     * that doubles back near itself is measured from the leg you are on
     * rather than from whichever leg happens to be closest - among segments
     * within a few metres of each other, the one furthest along wins, which
     * is the one you reach last.
     */
    public double metresRemaining(double lat, double lon) {
        if (line.size() < 2) return -1;
        suffix();

        double best = Double.MAX_VALUE;
        for (int i = 1; i < line.size(); i++) {
            double d = pointToSegment(lat, lon, line.get(i - 1)[0], line.get(i - 1)[1],
                                      line.get(i)[0], line.get(i)[1]);
            if (d < best) best = d;
        }
        if (best == Double.MAX_VALUE) return -1;

        // Among the segments that are equally close - the two legs of a road
        // driven out and back, or a hairpin - take the last one. Being wrong
        // that way says there is less driving left than there is, which the
        // next fix corrects; being wrong the other way makes the estimate
        // jump backwards every time you pass near an earlier part of the
        // route.
        int bestAt = -1;
        double bestAlong = 0;
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1), b = line.get(i);
            double d = pointToSegment(lat, lon, a[0], a[1], b[0], b[1]);
            if (d <= best + NEAR_M) {
                bestAt = i;
                bestAlong = alongSegment(lat, lon, a, b);
            }
        }
        if (bestAt < 0) return -1;

        // toEnd[i] is the distance from point i to the end, so what is left is
        // the rest of the segment being driven plus everything after it.
        double segment = metresBetween(line.get(bestAt - 1)[0], line.get(bestAt - 1)[1],
                                       line.get(bestAt)[0], line.get(bestAt)[1]);
        return toEnd[bestAt] + segment * (1 - bestAlong);
    }

    /** How far along a segment the nearest point to (lat,lon) is, 0 to 1. */
    private static double alongSegment(double lat, double lon, double[] a, double[] b) {
        double k = Math.cos(Math.toRadians(a[0]));
        double ax = (b[1] - a[1]) * k, ay = b[0] - a[0];
        double len = ax * ax + ay * ay;
        if (len <= 0) return 0;
        double t = (((lon - a[1]) * k) * ax + (lat - a[0]) * ay) / len;
        return t < 0 ? 0 : (t > 1 ? 1 : t);
    }

    private void suffix() {
        if (toEnd != null && toEnd.length == line.size()) return;
        int n = line.size();
        int[] t = new int[n];
        double run = 0;
        for (int i = n - 2; i >= 0; i--) {
            run += metresBetween(line.get(i)[0], line.get(i)[1],
                                 line.get(i + 1)[0], line.get(i + 1)[1]);
            t[i] = (int) Math.round(run);
        }
        toEnd = t;
    }

    public double offRouteMetres(double lat, double lon) {
        double best = Double.MAX_VALUE;
        for (int i = 1; i < line.size(); i++) {
            double d = pointToSegment(lat, lon,
                    line.get(i - 1)[0], line.get(i - 1)[1],
                    line.get(i)[0], line.get(i)[1]);
            if (d < best) best = d;
        }
        return best;
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
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Bearing from one point to another, degrees clockwise from north. */
    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double b = Math.toDegrees(Math.atan2(y, x));
        return (b + 360) % 360;
    }

    /** Flat-earth is fine over a route segment and much cheaper than the
     *  alternative on a processor this size. */
    private static double pointToSegment(double lat, double lon,
                                         double alat, double alon,
                                         double blat, double blon) {
        double kx = 111320.0 * Math.cos(Math.toRadians(alat));
        double ky = 110540.0;
        double px = (lon - alon) * kx, py = (lat - alat) * ky;
        double bx = (blon - alon) * kx, by = (blat - alat) * ky;
        double len = bx * bx + by * by;
        if (len == 0) return Math.sqrt(px * px + py * py);
        double t = Math.max(0, Math.min(1, (px * bx + py * by) / len));
        double dx = px - t * bx, dy = py - t * by;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
