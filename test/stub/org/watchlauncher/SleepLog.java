package org.watchlauncher;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Enough of SleepLog to run the scorer off the watch.
 *
 * The real one opens files through a Context and cannot be compiled outside Android, but the
 * scorer wants nothing from it except {@link Epoch} - so the night that is on the card can be
 * replayed on a desk instead of being argued about from summary statistics. Every wrong answer
 * this scorer has given was found by reading a night's numbers by hand and guessing at what the
 * code would do with them; this runs the code.
 *
 * Epoch is copied rather than shared because the real file is the one that ships. If a column is
 * added there and not here, the replay stops compiling, which is the right failure.
 */
public class SleepLog {

    public static final String DIR = "/sdcard/sleep";

    public static class Epoch {
        public long at;
        public double x, y, z;
        public double sd, enmo, range;
        public int samples;
        public int bpm;
        public double tempC;

        /** The arm's angle to the horizontal, which is what van Hees's method watches. */
        public double zAngle() {
            double flat = Math.sqrt(x * x + y * y);
            if (flat == 0 && z == 0) return 0;
            return Math.atan2(z, flat) * 180.0 / Math.PI;
        }
    }

    /** One night's log, read from a file pulled off the watch. */
    public static List<Epoch> readFile(String path) throws Exception {
        List<Epoch> out = new ArrayList<Epoch>();
        BufferedReader r = new BufferedReader(new FileReader(path));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                String[] p = line.split(",");
                if (p.length < 8) continue;
                Epoch e = new Epoch();
                e.at = Long.parseLong(p[0]);
                e.x = Double.parseDouble(p[1]);
                e.y = Double.parseDouble(p[2]);
                e.z = Double.parseDouble(p[3]);
                e.sd = Double.parseDouble(p[4]);
                e.enmo = Double.parseDouble(p[5]);
                e.range = Double.parseDouble(p[6]);
                e.samples = Integer.parseInt(p[7]);
                if (p.length > 8) e.bpm = (int) Double.parseDouble(p[8]);
                if (p.length > 9) e.tempC = Double.parseDouble(p[9]);
                out.add(e);
            }
        } finally {
            r.close();
        }
        return out;
    }
}
