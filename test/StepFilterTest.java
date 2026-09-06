import org.watchlauncher.StepFilter;

/**
 * The step filter, against the day that prompted it.
 *
 * Every increment below is a real one, taken from the tracker's own series for 6 September
 * together with the pulse recorded nearest it. The wearer's resting rate that day was 47. The
 * chip reported 7,310 steps; the wearer had been in a car for part of it.
 */
public class StepFilterTest {

    static int fails = 0;

    static void check(String what, boolean ok, String detail) {
        System.out.printf("  %-50s %s%s%n", what, ok ? "ok" : "FAILED",
                detail.isEmpty() ? "" : ("   " + detail));
        if (!ok) fails++;
    }

    static final int RESTING = 47;

    /** steps, minutes, pulse - as the tracker recorded them. */
    static final int[][] DAY = {
        {40,10,51},   {130,10,50},  {20,10,61},   {190,10,61},  {80,10,58},
        {20,10,59},   {10,10,47},   {230,10,47},  {40,6,45},    {10,10,62},
        {10,10,59},   {40,10,59},   {170,10,45},
        {1770,10,46},                                  // 177/min in a car
        {1960,8,47},                                   // 258/min in a car
        {1120,10,55}, {80,10,47},   {20,10,55},   {30,10,51},   {40,10,49},
        {340,10,54},
        {570,7,52},                                    // 78/min, pulse at rest
        {280,8,52},                                    // 34/min, pulse at rest
        {110,10,52},
    };

    static int credited(int[] r) {
        return StepFilter.credit(r[0], r[1] * 60000L, r[2], RESTING);
    }

    public static void main(String[] args) {
        System.out.println("step filter:");

        int raw = 0, kept = 0;
        for (int i = 0; i < DAY.length; i++) { raw += DAY[i][0]; kept += credited(DAY[i]); }

        check("the day as the chip counted it", raw == 7310, raw + " steps");
        check("more than half of it is refused", kept < raw / 2, kept + " kept");
        check("what is left is a plausible day", kept > 2000 && kept < 3500, kept + " steps");

        // --- the four that mattered ----------------------------------------------------------
        check("258 a minute at a pulse of 47 is refused",
                StepFilter.credit(1960, 8 * 60000L, 47, RESTING) == 0, "");
        check("177 a minute at a pulse of 46 is refused",
                StepFilter.credit(1770, 10 * 60000L, 46, RESTING) == 0, "");
        check("78 a minute at a pulse of 52 is refused",
                StepFilter.credit(570, 7 * 60000L, 52, RESTING) == 0, "");

        // --- and what must survive ------------------------------------------------------------
        // Pottering about a room is slow and the pulse stays down. That is real walking and the
        // rest test does not apply to it.
        check("22 a minute at a pulse of 47 is kept",
                StepFilter.credit(230, 10 * 60000L, 47, RESTING) == 230, "pottering");
        check("a brisk walk with the pulse up is kept",
                StepFilter.credit(1120, 10 * 60000L, 55, RESTING) == 1120, "112/min at 55 bpm");
        check("a genuine run is kept",
                StepFilter.credit(1600, 10 * 60000L, 150, RESTING) == 1400, "trimmed to the cap");

        // --- degrading without a pulse ---------------------------------------------------------
        // The cap alone refuses only the impossible, which is the point: with nothing to say the
        // body was at rest, a fast cadence might be real.
        check("with no pulse the cap still trims the impossible",
                StepFilter.credit(1960, 8 * 60000L, 0, RESTING) == 1120, "8 min at 140/min");
        check("with no resting estimate the cap still applies",
                StepFilter.credit(1960, 8 * 60000L, 47, 0) == 1120, "");

        // --- nonsense in, nothing out ----------------------------------------------------------
        check("a counter that went backwards credits nothing",
                StepFilter.credit(-5, 60000L, 60, RESTING) == 0, "");
        check("no elapsed time credits nothing",
                StepFilter.credit(100, 0, 60, RESTING) == 0, "");

        // --- the counter restarts when the watch does ------------------------------------
        // Its own reading is since boot, so a fall is a reboot rather than steps being undone.
        check("an ordinary rise is the difference",
                StepFilter.rise(1200, 1250) == 50, "");
        check("a reading below the last is a reboot",
                StepFilter.rise(5000, 30) == 30, "credit what was walked since it");
        check("nothing seen yet credits nothing",
                StepFilter.rise(-1, 4000) == 0, "not four thousand on a fresh install");
        check("a counter that has not moved rises by nothing",
                StepFilter.rise(1200, 1200) == 0, "");

        System.out.println(fails == 0 ? "step filter: all checks passed"
                                      : "step filter: " + fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
