package org.watchlauncher;

/**
 * Web Mercator, the projection the tiles are cut in.
 *
 * Kept in one place so the watch and the server cannot drift apart: these are
 * the same four formulas as lib.php, and a tile drawn with a different
 * definition than it was rendered with lands a road in the wrong street.
 */
public final class Mercator {

    public static final int TILE_PX = 256;

    private Mercator() { }

    public static double xOf(double lon, int z) {
        return (lon + 180.0) / 360.0 * (1 << z);
    }

    public static double yOf(double lat, int z) {
        double r = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 << z);
    }

    public static double lonOf(double x, int z) {
        return x / (1 << z) * 360.0 - 180.0;
    }

    public static double latOf(double y, int z) {
        double n = Math.PI - 2.0 * Math.PI * y / (1 << z);
        return Math.toDegrees(Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))));
    }

    /** Metres per pixel at this latitude and zoom, for the scale bar and for
     *  deciding how far a download has to reach. */
    public static double metresPerPixel(double lat, int z) {
        return 156543.03392 * Math.cos(Math.toRadians(lat)) / (1 << z);
    }
}
