package org.watchlauncher;

/**
 * Distances on the ground.
 *
 * One copy, because there were three and they disagreed. The route line
 * measured a degree of latitude as 110540 metres, the haversine in Route used
 * a 6371 km sphere and made it 111195, and the speed estimator had its own
 * 110540 again. Two numbers for the same thing, 0.59% apart, and both wrong:
 * 110540 is the value at the equator, and a degree of latitude is 111267
 * metres in the Netherlands and 111412 in Scotland.
 *
 * The error was one-sided - every distance short, every arrival time
 * optimistic - and it grew with latitude, which matters for a watch meant to
 * work in whatever country it is switched on in.
 *
 * The series below are the usual ones for WGS84, good to about a metre in a
 * degree. That is far past what a route measured from junction to junction
 * can make use of, and it costs two cosines.
 *
 * No Android in here: everything that measures anything needs it, including
 * the parts that have to be testable on the host.
 */
public final class Geo {

    private Geo() { }

    /** Metres in a degree of latitude at this latitude. */
    public static double perLat(double lat) {
        double p = Math.toRadians(lat);
        return 111132.954 - 559.822 * Math.cos(2 * p) + 1.175 * Math.cos(4 * p);
    }

    /** Metres in a degree of longitude at this latitude. */
    public static double perLon(double lat) {
        double p = Math.toRadians(lat);
        return 111412.84 * Math.cos(p) - 93.5 * Math.cos(3 * p) + 0.118 * Math.cos(5 * p);
    }

    /**
     * Between two points.
     *
     * Haversine, on the sphere whose radius makes a degree of latitude come
     * out right halfway between them, rather than on a global mean that is
     * half a per cent out at every latitude anyone lives at.
     */
    public static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double r = perLat((lat1 + lat2) / 2) * 180 / Math.PI;
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Between two points that are close together.
     *
     * Flat-earth, which over the tens or hundreds of metres between two fixes
     * or two junctions is indistinguishable from the haversine and much
     * cheaper - no trigonometry at all if the caller has already asked for
     * the two scales.
     */
    public static double metresFlat(double lat1, double lon1, double lat2, double lon2) {
        double mid = (lat1 + lat2) / 2;
        double dy = (lat2 - lat1) * perLat(mid);
        double dx = (lon2 - lon1) * perLon(mid);
        return Math.sqrt(dx * dx + dy * dy);
    }
}
