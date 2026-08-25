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
     * @return null when there is no turn left to make
     */
    public String screenInstruction(double lat, double lon) {
        Turn t = nextTurn(lat, lon);
        if (t == null) return null;
        String what = action(t.kind);
        if (what == null) return null;
        int m = metresTo(lat, lon, t);
        if (t.kind == ARRIVE) return what;
        if (m <= 20) return what;              // at it now, no distance
        return "in " + screenDistance(m) + " " + what;
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
