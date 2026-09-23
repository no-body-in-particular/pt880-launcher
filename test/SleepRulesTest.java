import org.watchlauncher.SleepRules;

/**
 * The sleep and wake decision, against stretches whose answer is known.
 *
 * Every case here is one the shipped code got wrong at some point, and each was found by
 * replaying a real night rather than by reading the code - which is the reason this file exists.
 * The wearer called out their own times on the night of 3-4 September; the epochs come from
 * /sdcard/sleep and the expectations from what they said they were doing.
 *
 * The two rules under test are deliberately asymmetric. A wrist that moves is awake now, so the
 * stillness run ends outright; a sleeper who lies still for a minute of a waking morning has not
 * gone back to sleep, so movement only decays.
 */
public class SleepRulesTest {

    static int fails = 0;

    static void check(String what, boolean ok, String detail) {
        System.out.printf("  %-52s %s%s%n", what, ok ? "ok" : "FAILED",
                detail.isEmpty() ? "" : ("   " + detail));
        if (!ok) fails++;
    }

    /** Minutes of stillness needed before a log starts, and of movement before it ends. */
    static final int START_SEC = 30 * 60;
    static final int STOP_SEC  = 20 * 60;

    /**
     * Run a stretch through the watcher and report whether it ever declares sleep.
     *
     * @param enmo   one value per burst
     * @param gapSec seconds between bursts
     */
    static boolean startsALog(double[] enmo, int gapSec) {
        int held = 0;
        for (int i = 0; i < enmo.length; i++) {
            boolean still = SleepRules.still(enmo[i], 0, Double.NaN, false, 10.0);
            held = SleepRules.held(held, SleepRules.credit(gapSec * 1000L), still);
            if (held >= START_SEC) return true;
        }
        return false;
    }

    /** Run a stretch through the logger and report the burst at which the night closes, or -1. */
    static int closesAt(double[] enmo, int gapSec) {
        int moved = 0;
        for (int i = 0; i < enmo.length; i++) {
            boolean still = SleepRules.still(enmo[i], 0, Double.NaN, false, 10.0);
            moved = SleepRules.moved(moved, SleepRules.credit(gapSec * 1000L), still);
            if (!still && moved >= STOP_SEC) return i;
        }
        return -1;
    }

    /** Does a stretch that is still in (every-1)/every of its bursts reach the onset bar? */
    static boolean thirtyMinutesOfMostlyStill(int every) {
        int held = 0;
        for (int i = 0; i < 400; i++) {
            boolean still = (i % every != 0);
            held = SleepRules.held(held, 30, still);
            if (held >= START_SEC) return true;
        }
        return false;
    }

    /** A stretch of one value. */
    static double[] flat(int n, double v) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = v;
        return a;
    }

    /** Mostly moving, with one still burst in every `every` - a waking morning. */
    static double[] mostlyMoving(int n, int every) {
        double[] a = new double[n];
        for (int i = 0; i < n; i++) a[i] = (i % every == 0) ? 0.001 : 0.050;
        return a;
    }

    public static void main(String[] args) {
        System.out.println("sleep rules:");

        // --- the threshold sits between sleeping and being awake ------------------------------
        // Measured medians from two nights: asleep about 0.001, awake and about 0.010 to 0.030.
        check("a sleeping wrist reads still",
                SleepRules.still(0.001, 0, Double.NaN, false, 10.0), "");
        check("a wrist at 0.010 does not",
                !SleepRules.still(0.010, 0, Double.NaN, false, 10.0), "");
        check("nor one at 0.030",
                !SleepRules.still(0.030, 0, Double.NaN, false, 10.0), "");

        // --- an afternoon of sitting is not a night -------------------------------------------
        // This is the case that put "sleep 13:32 -> wake 19:16" on the server. At the old
        // threshold of 0.015 a sedentary afternoon read still throughout and cleared the bar.
        check("sitting still at 0.010 never starts a log",
                !startsALog(flat(200, 0.010), 300), "two hours of it");

        // --- a real night does start one ------------------------------------------------------
        check("a still wrist at 0.001 starts a log",
                startsALog(flat(12, 0.001), 300), "");

        // --- the cadence must not change what the rule means ----------------------------------
        // The bug this replaces counted bursts, so the same rule meant twenty minutes at the fine
        // cadence and three and a half hours at the rate bursts actually arrived by day.
        int fine = closesAt(flat(200, 0.050), 30);
        int coarse = closesAt(flat(200, 0.050), 300);
        check("wake takes the same time at either cadence",
                fine >= 0 && coarse >= 0
                        && Math.abs((fine + 1) * 30 - (coarse + 1) * 300) <= 300,
                "fine " + ((fine + 1) * 30) + "s, coarse " + ((coarse + 1) * 300) + "s");

        // --- a waking morning is not uninterrupted --------------------------------------------
        // Erasing the count on the first still burst is why a night that started never ended:
        // sitting down to eat or read lands one, and no morning goes twenty minutes without.
        check("a mostly-moving morning still closes the night",
                closesAt(mostlyMoving(300, 4), 30) >= 0, "one still burst in four");

        // --- but a still night does not ------------------------------------------------------
        check("a still night never closes it",
                closesAt(flat(300, 0.001), 30) < 0, "");

        // --- gaps are not evidence -----------------------------------------------------------
        // A dropped burst or a delayed alarm must not be credited as ten minutes of anything.
        check("a long gap is credited only the cap",
                SleepRules.credit(3600 * 1000L) == SleepRules.STEP_CAP_SEC,
                SleepRules.credit(3600 * 1000L) + "s");
        check("a normal gap is credited in full",
                SleepRules.credit(30 * 1000L) == 30, "");

        // --- movement ends a stillness run outright, stillness only decays a movement one -----
        check("movement spends the stillness run down, not out",
                SleepRules.held(1500, 30, false) == 1470, "a sleeper who turns over is asleep");
        check("and cannot take it below zero",
                SleepRules.held(10, 300, false) == 0, "");
        check("three quarters still reaches the bar",
                thirtyMinutesOfMostlyStill(4), "one moving burst in four");
        check("but half and half does not",
                !thirtyMinutesOfMostlyStill(2), "an evening on a sofa");
        check("stillness only pays back the movement run",
                SleepRules.moved(1200, 30, true) == 1170, "");
        check("and cannot take it below zero",
                SleepRules.moved(10, 300, true) == 0, "");

        // --- the pulse gate must sit above the sleeping range, not inside the waking one -----
        // Measured on this wearer: asleep median 48, awake and sedentary median 54. A margin of
        // 8 on a resting estimate of 47.5 puts the gate at 55.5, which passes both.
        check("a sleeping pulse passes the gate",
                SleepRules.pulseSaysSleep(48, 47), "median asleep against resting 47");
        check("a sedentary waking pulse does not",
                !SleepRules.pulseSaysSleep(54, 47), "median awake against resting 47");
        check("the old margin of 8 would have passed it",
                54 <= 47 + 8, "which is why it was 62% wrong");
        check("no pulse cannot answer the question",
                !SleepRules.pulseSaysSleep(0, 47), "");
        check("no resting estimate cannot either",
                !SleepRules.pulseSaysSleep(48, 0), "");

        // --- a burst has to be acceleration before it is anything else --------------------
        // Real rows from 6 September, night log against watcher file, same wrist same day.
        check("a real burst is about one gravity",
                SleepRules.measuredAWrist(-0.089, 1.057, -0.0015), "the watcher's own row");
        check("a mean of 3722 g is not a wrist",
                !SleepRules.measuredAWrist(3722.0, 0.430, -0.189), "");
        check("nor 6090",
                !SleepRules.measuredAWrist(6090.0, 0.447, 0.028), "");
        check("nor 2112, which read as the stillest sleep of the day",
                !SleepRules.measuredAWrist(2112.0, 0.780, 0.546), "");
        check("an unfilled buffer is still caught",
                !SleepRules.measuredAWrist(0, 0, 0), "the case the old test covered");
        check("a watch face down on a table is kept",
                SleepRules.measuredAWrist(0.02, -0.01, -0.999), "still a wrist reading");

        // --- a session is sleep by how far the arm turns, not by how much one burst spread ---
        //
        // Every figure below is a real session's median, measured over five nights on the pairs
        // where the quantity means what van Hees's threshold says. The four unambiguous nights
        // sit an order of magnitude inside the line; the desk sessions sit from twice to a
        // hundred and fifty times outside it.
        check("a night at 0.019 is sleep",
                SleepRules.angleSaysSleep(0.019), "22 Sep 17:22-21:51");
        check("a night at 0.049 is sleep",
                SleepRules.angleSaysSleep(0.049), "19 Sep 22:44-05:13");
        check("a night at 0.052 is sleep",
                SleepRules.angleSaysSleep(0.052), "20 Sep 23:42-03:57, which the range refused");
        check("a night at 0.063 is sleep",
                SleepRules.angleSaysSleep(0.063), "22 Sep 22:48-05:52");
        check("a desk afternoon at 0.231 is not",
                !SleepRules.angleSaysSleep(0.231), "21 Sep 12:03-16:21");
        check("nor one at 18.536",
                !SleepRules.angleSaysSleep(18.536), "20 Sep 13:49-16:07, which the range admitted");
        check("nor one at 20.429",
                !SleepRules.angleSaysSleep(20.429), "22 Sep 10:46-12:22");

        // An evening of sitting joined to the night that followed it: 1.4, 2.3, 2.6, 9.9 by the
        // hour and then 0.04, 0.09, 0.04. Refusing the session as built is the right answer to
        // the question actually asked of it.
        check("an evening blended into a night is refused",
                !SleepRules.angleSaysSleep(0.159), "21 Sep 19:30-02:06");

        // --- a session nothing comparable covered cannot be judged ----------------------------
        check("no comparable pair is not sleep",
                !SleepRules.angleSaysSleep(Double.NaN), "refused rather than assumed");
        check("a negative median is not sleep",
                !SleepRules.angleSaysSleep(-1.0), "");

        // --- which pairs may be compared at all -----------------------------------------------
        // Both of these traps cost a real night. Five minutes apart the arm has had five minutes
        // to move, and a vitals window averages its angle over eighty seconds rather than five.
        check("two bursts one logging step apart may be compared",
                SleepRules.anglePairUsable(35, 80, 80), "30s alarm plus a 5s burst");
        check("one delayed alarm is still a pair",
                SleepRules.anglePairUsable(60, 80, 80), "");
        check("two bursts at the watching cadence may not",
                !SleepRules.anglePairUsable(300, 80, 80), "five minutes is not one step");
        check("a vitals window may not be compared to a burst",
                !SleepRules.anglePairUsable(35, 80, 3885), "its angle spans eighty seconds");
        check("nor two vitals windows to each other",
                !SleepRules.anglePairUsable(35, 2957, 3764), "");
        check("a row with no samples is not a measurement",
                !SleepRules.anglePairUsable(35, 80, 0), "");
        check("nor is a pair with no time between them",
                !SleepRules.anglePairUsable(0, 80, 80), "");

        // --- the median, since the gate is one -------------------------------------------------
        check("the median of the used entries ignores the rest",
                SleepRules.median(new double[]{0.05, 0.01, 0.09, 999.0}, 3) == 0.05,
                "a scratch array is only filled as far as k");
        check("no entries at all is not a number",
                Double.isNaN(SleepRules.median(new double[]{1.0}, 0)), "");

        System.out.println(fails == 0 ? "sleep rules: all checks passed"
                                      : "sleep rules: " + fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
