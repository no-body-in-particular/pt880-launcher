package org.watchlauncher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Where to go, as a text file you push.
 *
 *     adb push destination.txt /sdcard/Documents/
 *
 *     Home:51.4667000,4.5000000
 *     Work:51.4667000,4.5000000
 *     # a hash comments a line out
 *
 * The same shape as contacts.txt, for the same reason: there is no touchscreen
 * and no keyboard, so anything that has to be typed has to arrive over adb.
 * A bare "lat,lon" line works too and names itself after its coordinates.
 */
public class Destination {

    private static final String[] PATHS = {
        "/sdcard/Documents/destination.txt",
        "/sdcard/Documents/destinations.txt",
        "/sdcard/documents/destination.txt",
        "/sdcard/destination.txt",
    };

    public static final String DEFAULT_PATH = "/sdcard/Documents/destination.txt";

    public final String name;
    public final double lat, lon;

    Destination(String name, double lat, double lon) {
        this.name = name;
        this.lat = lat;
        this.lon = lon;
    }

    public static String file() {
        for (int i = 0; i < PATHS.length; i++) {
            File f = new File(PATHS[i]);
            if (f.isFile() && f.canRead()) return PATHS[i];
        }
        return null;
    }

    public static List<Destination> load() {
        List<Destination> out = new ArrayList<Destination>();
        String path = file();
        if (path == null) return out;

        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            String line;
            while ((line = r.readLine()) != null) {
                Destination d = parse(line);
                if (d != null) out.add(d);
            }
        } catch (Exception e) {
            /* unreadable is the same as absent */
        } finally {
            try { if (r != null) r.close(); } catch (Exception e) { /* ignore */ }
        }
        return out;
    }

    /** "Name:lat,lon" or "lat,lon". */
    static Destination parse(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.length() == 0 || line.startsWith("#") || line.startsWith("//")) return null;

        String name = null;
        int colon = line.lastIndexOf(':');
        if (colon > 0) {
            name = line.substring(0, colon).trim();
            line = line.substring(colon + 1).trim();
        }
        String[] p = line.split("[,; ]+");
        if (p.length < 2) return null;
        try {
            double lat = Double.parseDouble(p[0].trim());
            double lon = Double.parseDouble(p[1].trim());
            // Coordinates the wrong way round are the commonest mistake in a
            // hand-written file, and a latitude past 90 is proof of it.
            //
            // The NaN test is not redundant: every comparison against NaN is
            // false, so a line reading "Home:NaN,NaN" passes a range check
            // and becomes a destination that can never be reached and never
            // reports why.
            if (Double.isNaN(lat) || Double.isNaN(lon)
                    || Double.isInfinite(lat) || Double.isInfinite(lon)) return null;
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
            if (name == null || name.length() == 0) {
                name = String.format("%.4f, %.4f", lat, lon);
            }
            return new Destination(name, lat, lon);
        } catch (Exception e) {
            return null;
        }
    }

    /** Write a commented example so an empty watch is one press from having
     *  something to edit rather than a dead end. */
    public static String createExample() {
        try {
            File f = new File(DEFAULT_PATH);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return null;
            if (f.exists()) return f.getAbsolutePath();
            FileOutputStream o = new FileOutputStream(f);
            try {
                o.write(("# One destination per line: a name, a colon, then"
                       + " latitude and longitude.\n"
                       + "#\n"
                       + "# Home:51.4667000,4.5000000\n"
                       + "# Work:51.4667000,4.5000000\n").getBytes("UTF-8"));
                o.flush();
            } finally {
                o.close();
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }
}
