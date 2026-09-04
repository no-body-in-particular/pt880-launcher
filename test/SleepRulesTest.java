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
        check("movement clears the stillness run",
                SleepRules.held(1500, 30, false) == 0, "");
        check("stillness only pays back the movement run",
                SleepRules.moved(1200, 30, true) == 1170, "");
        check("and cannot take it below zero",
                SleepRules.moved(10, 300, true) == 0, "");

        System.out.println(fails == 0 ? "sleep rules: all checks passed"
                                      : "sleep rules: " + fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
