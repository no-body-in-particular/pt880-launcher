import org.watchlauncher.Drive;

/**
 * The speed and arrival estimator, driven over profiles whose answer is known
 * by construction.
 *
 * The cases that matter are the ones where the obvious implementation is
 * wrong: stopped at a light, where dividing by the current speed says you
 * will never arrive; a fix that jumps across the country, where believing it
 * says you are doing 400 km/h; and the watch waking up an hour later, where
 * averaging across the gap says you have been crawling.
 */
public class DriveTest {

    static int failures = 0;

    static void check(String what, boolean ok, String saw) {
        if (!ok) { System.out.println("FAIL " + what + ": " + saw); failures++; }
        else System.out.println("ok   " + what + " (" + saw + ")");
    }

    /** Metres north of a starting latitude, as a latitude. */
    static double north(double lat0, double m) { return lat0 + m / 110540.0; }

    public static void main(String[] a) {
        final double LAT = 52.0, LON = 5.0;

        // --- steady 100 km/h, no speed in the fixes -----------------------
        Drive d = new Drive();
        long t = 1000000;
        double run = 0;
        for (int i = 0; i < 40; i++) {          // 40 fixes, 10 s apart
            d.fix(t, north(LAT, run), LON, -1);
            t += 10000; run += 277.8;           // 100 km/h
        }
        check("steady 100 km/h read back", Math.abs(d.kmh() - 100) <= 2, d.kmh() + " km/h");
        check("made good matches", Math.abs(d.madeGoodMs() - 27.78) < 1.0,
              String.format("%.1f m/s", d.madeGoodMs()));
        int eta = d.etaSeconds(27780, -1);      // 27.78 km left
        check("eta at 100 km/h is ~1000 s", Math.abs(eta - 1000) < 60, eta + " s");
        check("formats as 17 min", "17 min".equals(Drive.shortTime(eta)), Drive.shortTime(eta));

        // --- now stop dead at a light for two minutes ---------------------
        for (int i = 0; i < 12; i++) { d.fix(t, north(LAT, run), LON, -1); t += 10000; }
        check("stopped shows 0 km/h", d.kmh() == 0, d.kmh() + " km/h");
        int stoppedEta = d.etaSeconds(27780, -1);
        check("eta survives a stop", stoppedEta > 0 && stoppedEta < 4000, stoppedEta + " s");
        check("eta got longer, not infinite", stoppedEta > eta, eta + " -> " + stoppedEta);

        // --- a fix from the far side of the country is not 400 km/h -------
        // Tested on a Drive that is moving, so that "unchanged" means the
        // jump was ignored rather than that there was nothing to change.
        Drive j = new Drive();
        long q = 2000000;
        double r3 = 0;
        for (int i = 0; i < 20; i++) { j.fix(q, north(LAT, r3), LON, -1); q += 10000; r3 += 250; }
        int before = j.kmh();
        check("moving before the jump", before > 80, before + " km/h");
        j.fix(q + 10000, LAT + 2.0, LON + 2.0, -1);
        check("teleport rejected", j.kmh() == before, before + " -> " + j.kmh());
        // And the next honest fix is measured from where the jump landed, not
        // from where the drive was before it.
        j.fix(q + 20000, LAT + 2.0 + 250 / 110540.0, LON + 2.0, -1);
        check("resyncs after a jump", j.kmh() > 50 && j.kmh() < 130, j.kmh() + " km/h");

        // --- waking up an hour later starts a new drive, not a crawl ------
        Drive w = new Drive();
        long u = 5000000;
        double r2 = 0;
        for (int i = 0; i < 20; i++) { w.fix(u, north(LAT, r2), LON, -1); u += 10000; r2 += 250; }
        float fast = w.madeGoodMs();
        u += 3600000;                              // an hour asleep
        w.fix(u, north(LAT, r2), LON, -1);
        check("gap does not poison the average",
              w.madeGoodMs() < 0 || w.madeGoodMs() > fast / 2,
              String.format("%.1f -> %.1f m/s", fast, w.madeGoodMs()));

        // --- the provider's own speed is preferred when it has one --------
        Drive p = new Drive();
        long v = 9000000;
        for (int i = 0; i < 20; i++) { p.fix(v, LAT, LON, 20f); v += 10000; }
        check("provider speed used when still", Math.abs(p.kmh() - 72) <= 2, p.kmh() + " km/h");

        // --- nothing to go on yields no estimate rather than a made-up one -
        Drive n = new Drive();
        check("no fixes, no speed", n.kmh() == -1, String.valueOf(n.kmh()));
        check("no fixes, no eta", n.etaSeconds(10000, -1) == -1,
              String.valueOf(n.etaSeconds(10000, -1)));
        check("planned speed used before driving",
              n.etaSeconds(10000, 20f) == 500, String.valueOf(n.etaSeconds(10000, 20f)));

        // --- the same fix delivered twice is not a stop -------------------
        // The emulator does this, and so does a watch reading a position the
        // tracker has already resolved.
        Drive r = new Drive();
        long z = 3000000;
        double r4 = 0;
        for (int i = 0; i < 20; i++) {
            r.fix(z, north(LAT, r4), LON, -1);      // the fix
            r.fix(z, north(LAT, r4), LON, -1);      // and again, same instant
            z += 10000; r4 += 277.8;
        }
        check("repeated fixes do not halve the speed",
              Math.abs(r.kmh() - 100) <= 3, r.kmh() + " km/h");

        // But repeating for a minute really is standing still.
        for (int i = 0; i < 8; i++) { z += 10000; r.fix(z, north(LAT, r4), LON, -1); }
        check("a long repeat is a stop", r.kmh() == 0, r.kmh() + " km/h");

        // --- a burst of fixes is not 344 km/h -----------------------------
        // Seen on the emulator and fixed there: two genuinely different
        // positions delivered a fraction of a second apart divide a hundred
        // metres by a tenth of a second. The position is only good to a few
        // metres, so the shorter the interval the more of the answer is the
        // error in it.
        Drive burst = new Drive();
        long y = 4000000;
        double r5 = 0;
        for (int i = 0; i < 60; i++) {
            // A steady 100 km/h, but delivered as pairs 200 ms apart.
            burst.fix(y, north(LAT, r5), LON, -1);
            r5 += 27.8; y += 200;
            burst.fix(y, north(LAT, r5), LON, -1);
            r5 += 250.0; y += 9800;
        }
        check("burst delivery does not inflate the speed",
              burst.kmh() > 70 && burst.kmh() < 130, burst.kmh() + " km/h");

        // --- formatting ---------------------------------------------------
        check("under a minute", "<1 min".equals(Drive.shortTime(30)), Drive.shortTime(30));
        check("hours and minutes", "1 h 12".equals(Drive.shortTime(72 * 60)),
              Drive.shortTime(72 * 60));
        check("negative is nothing", Drive.shortTime(-1) == null, "null");
        // 12:00 UTC plus 30 minutes, no offset
        check("arrival clock", "12:30".equals(Drive.arrivalClock(12 * 3600000L, 0, 1800)),
              Drive.arrivalClock(12 * 3600000L, 0, 1800));
        check("arrival clock wraps midnight",
              "00:10".equals(Drive.arrivalClock(23 * 3600000L + 50 * 60000L, 0, 1200)),
              Drive.arrivalClock(23 * 3600000L + 50 * 60000L, 0, 1200));

        System.out.println(failures == 0 ? "drive: all checks passed"
                                         : "drive: " + failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
