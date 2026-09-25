package org.watchlauncher;

/**
 * When stillness becomes sleep, and when movement ends it.
 *
 * Pulled out of {@link SleepService} with nothing of Android in it, so the cases that actually
 * bite can be driven from a test rather than discovered a night at a time. Every rule here was
 * wrong at some point in a way a replay of one real night would have caught immediately:
 *
 * <ul>
 *   <li>the threshold sat inside the waking range, so an afternoon of sitting became a night;
 *   <li>the wake test counted bursts where the cadence changes underneath it, so a night that
 *       started never ended - one file held 3.2 hours of sleep inside 24 hours of logging;
 *   <li>stillness reset the movement count outright, so any pause in a waking morning undid it.
 * </ul>
 *
 * The numbers below come from replaying 2-3 September against a wearer who called out their own
 * times, and the reasoning for each is recorded where it is defined.
 */
public final class SleepRules {

    /**
     * Below this the wrist is not doing anything. ENMO is the vector magnitude less one g.
     *
     * This was 0.015 and its comment called it a guess until a real night said otherwise. Two
     * nights and the days around them have now said so. Asleep, this wrist reads a median ENMO
     * near 0.001; awake and about, 0.010 to 0.030. So 0.015 sat inside the waking range rather
     * than between the two, and called 64% of waking epochs still - and a stillness run that
     * only has to survive thirty minutes then completes in the middle of an afternoon. Replayed,
     * it did exactly that five times over two days: 13:32, 16:41, 17:34, 10:00, 16:29.
     *
     *     0.015    5 daytime onsets    keeps 96.7% of known sleep epochs
     *     0.010    2                   96.7%
     *     0.005    0                   95.6%
     *     0.003    0                   94.4%
     *
     * 0.005 is the loosest value producing none, and gives up a point of sleep to get there.
     */
    public static final double STILL_ENMO = 0.005;

    /** A gap longer than this is not evidence of anything, so credit only this much of it. The
     *  watcher's own interval: the wrist may have been off, or the alarm delayed. */
    public static final int STEP_CAP_SEC = 300;

    /**
     * How far above its resting rate a pulse may sit and still be sleep.
     *
     * This was 8, on a comment reading "asleep this wrist reads 51-55; sedentary and awake it
     * reads 60-85". Measured against a wearer who called out their own times, neither range is
     * right: asleep is 44-72 with a median of 48, awake and sedentary is 43-103 with a median of
     * 54. The two overlap heavily and the gap the margin was sized for is not there.
     *
     * With the resting estimate at 47.5, a margin of 8 puts the gate at 55.5 - inside the waking
     * range rather than above it, so it admitted almost everything:
     *
     *     margin  gate   sleep passes   waking wrongly passes
     *        4    51.5      81%              37%
     *        5    52.5      86%              42%
     *        8    55.5      95%              62%
     *
     * Five, because the check is not one-shot: it is asked again on every burst for as long as
     * the wrist stays still, so a reading that fails delays an onset rather than losing a night,
     * while one that wrongly passes starts an afternoon that runs for hours. And it is a second
     * line now - with the stillness threshold corrected no daytime onset survives to reach this
     * test at all - so it is worth keeping honest rather than loose.
     */
    public static final int SLEEP_BPM_MARGIN = 5;

    /**
     * Whether a pulse is close enough to its resting rate for stillness to be sleep.
     * A rate or a resting estimate of zero means the question cannot be asked.
     */
    public static boolean pulseSaysSleep(int bpm, int restingBpm) {
        if (bpm <= 0 || restingBpm <= 0) return false;
        return bpm <= restingBpm + SLEEP_BPM_MARGIN;
    }

    /**
     * Did this burst measure a wrist?
     *
     * Gravity is not optional. Whatever a watch is doing - still, moving, face down on a table -
     * the mean of its acceleration over five seconds is about one g, because the earth is always
     * pulling. A burst whose mean vector is not is not a still wearer and not a busy one; it is
     * the sensor, or the path to it, handing back something that is not acceleration.
     *
     * This was an all-zeroes test, which catches only the case where the buffer was never filled.
     * It missed the one that mattered. The night log for 6 September carries rows reading
     *
     *     meanX 3722   meanY 0.430   meanZ -0.189   n 2055
     *     meanX 6090   meanY 0.447   meanZ  0.028   n 2386
     *
     * where the watcher's own file, the same day and the same wrist, reads -0.089, 1.057,
     * -0.0015 with n=80. A mean of 3722 g is not a wrist, and n over two thousand inside a five
     * second burst is 411 samples a second from an accelerometer dumpsys reports as maxRate 200.
     *
     * It mattered because of what the scorer does next: the arm angle is atan2(z, hypot(x, y)),
     * and with x in the thousands that is zero for every row of the night. The angle test - the
     * whole of van Hees's method - has been reading a constant, and a walk went into the log
     * looking like the stillest sleep of the day.
     *
     * The band is wide on purpose. Real bursts sit near 1.00 and this only has to catch what is
     * not acceleration at all.
     */
    public static boolean measuredAWrist(double mx, double my, double mz) {
        double mag = Math.sqrt(mx * mx + my * my + mz * mz);
        return mag > 0.5 && mag < 2.0;
    }

    private SleepRules() { }

    /**
     * How much of the time since the last burst to count.
     *
     * Seconds rather than a count of bursts, because the cadence changes underneath a count:
     * six bursts is half an hour while watching and three minutes while logging, and bursts are
     * dropped besides - one night's file held 31 epochs in the 05:00 hour and 11 in the 08:00
     * one. At eleven an hour a rule written in bursts means something different by day than by
     * night, which is how a bar of "twenty minutes" became three and a half hours.
     */
    public static int credit(long sinceLastMs) {
        if (sinceLastMs <= 0) return 0;
        long ms = Math.min(sinceLastMs, STEP_CAP_SEC * 1000L);
        return (int) (ms / 1000L);
    }

    /**
     * Is the wrist doing nothing?
     *
     * The angle is only consulted at the fine cadence. Five minutes is long enough for a sleeper
     * to turn over, so comparing two snapshots that far apart reads an ordinary posture change as
     * movement - which flagged 43% of bursts and stopped a night being detected at all.
     */
    public static boolean still(double enmo, double angleDeg, double prevAngleDeg,
                                boolean fine, double angleTolDeg) {
        boolean turned = fine && !Double.isNaN(prevAngleDeg)
                && Math.abs(angleDeg - prevAngleDeg) > angleTolDeg;
        return enmo < STILL_ENMO && !turned;
    }

    /**
     * Seconds of stillness while watching for sleep. Movement spends the run down, not out.
     *
     * This reset to zero on any moving burst, on the reasoning that a wrist which moves is awake
     * now. A sleeper who turns over is not, and a five second burst catches that turn. Measured
     * on a night whose sleep is not in doubt - pulse 46 against a resting 48 - the hours the
     * wearer spent asleep still read as moving a quarter of the time:
     *
     *     hour 03   88% still     hour 04   74% still     hour 06   83% still
     *
     * Thirty minutes with no moving burst at all almost never happens at those rates. The
     * detector fired once in a night holding three sleeps, and logged twenty-one minutes of it.
     *
     * Spending the run down a step per moving burst accumulates whenever more than half the
     * bursts are still and decays otherwise, which is the line worth drawing: the hours above
     * are sleep and a sofa at half and half is not. On that night it found the afternoon nap
 * and the first night bout, where the reset found only the latter.
     *
     * Half a step was tried first and is too loose: at exactly half still it still creeps to
 * the bar, just slowly, and a quiet evening reaches it in two hours with a pulse close enough
     * to resting to pass the gate. Requiring a majority is a rule that states itself.
     * free to disagree with afterwards.
     *
     * The validation is partial, and worth saying so. The watcher keeps its own log only while
     * it is not logging a night, so a night it detected correctly is invisible to a replay - the
     * evidence can show sleep this missed, never sleep it already caught.
     */
    public static int held(int heldSec, int stepSec, boolean still) {
        int next = still ? heldSec + stepSec : heldSec - stepSec;
        return next < 0 ? 0 : next;
    }

    /**
     * Seconds of movement while logging. Stillness pays it back rather than erasing it.
     *
     * The bar has to survive a waking morning, and no waking morning is uninterrupted - sitting
     * down to eat or read lands a still burst. Erasing on the first one meant the count never
     * reached the bar and the night never closed. Decaying, a stretch that is three quarters
     * movement still clears twenty minutes in about forty.
     */
    public static int moved(int movedSec, int stepSec, boolean still) {
        int next = still ? movedSec - stepSec : movedSec + stepSec;
        return next < 0 ? 0 : next;
    }
    /**
     * Above this median change in arm angle from one epoch to the next, a session is somebody
     * sitting still rather than sleeping. Degrees.
     *
     * This replaces a gate on the within-burst range, which did not survive a fifth night. Its
     * comment recorded a clean split on two - the nights at 0.0182 and 0.0183, everything else
     * from 0.0293 up - and the split was an artefact. Range is the spread of the magnitude
     * inside one burst, so it grows with the length of the burst, and the night log has two
     * writers with windows that differ by a factor of forty: the recorder's five second bursts
     * and the vitals path's thirty to eighty second ones. Over four nights the same wrist reads
     *
     *     five second bursts     median range 0.0118
     *     vitals windows         median range 0.0625
     *
     * so a session's median said mostly which writer covered it. What covered a session tracks
     * the recorder's state, which tracks whether it is night - which is why two nights looked
     * separable. By the fifth the gate was inverted: it refused three real nights at 0.0230,
     * 0.0231 and 0.0303 and admitted a desk afternoon at 0.0219.
     *
     * The angle change does not have that defect - it is a difference between two epochs rather
     * than a spread inside one - and 0.13 is van Hees's own figure for five second epochs, which
     * is what the bursts are. Measured across five nights, on the pairs where it means what it
     * says:
     *
     *     asleep      0.019  0.049  0.052  0.063
     *     at a desk   0.231  6.320  6.518  11.289  15.925  18.536  20.429
     *
     * The nearest thing between them is 0.159, a session running 19:30 to 02:06 whose own hours
     * read 1.4, 2.3, 2.6, 9.9 and then 0.04, 0.09, 0.04 - an evening of sitting that the session
     * builder joined to the night that followed it. It is on the right side of the line for the
     * right reason.
     */
    public static final double SLEEP_ANGLE_CHANGE_MAX = 0.13;

    /**
     * Two epochs further apart than this are not neighbours and their angles cannot be compared.
     *
     * The same trap as the range, one level up. Five minutes apart the arm has had five minutes
     * to move, so the change between two bursts at the watching cadence is not the change
     * between two at the logging cadence, and a session that spans both would be judged mostly
     * on which it spent longer in. Sixty seconds takes the logging cadence's own interval -
     * thirty seconds plus a five second burst, or about thirty-five - with room for one delayed
     * alarm, and refuses everything else.
     */
    public static final int ANGLE_PAIR_MAX_SEC = 60;

    /**
     * An epoch gathered from more samples than this is not a five second burst.
     *
     * The vitals path writes what its own measurement watched, which is thirty to eighty seconds
     * and about three thousand samples. Its angle is a mean over that whole window, so it is not
     * the quantity van Hees's threshold describes. Eighty samples is a burst; two hundred leaves
     * room for a slow one without admitting a vitals window.
     */
    public static final int ANGLE_MAX_SAMPLES = 200;

    /**
     * Can these two epochs be compared? They must be neighbours in time and both five second
     * bursts, or the difference between their angles measures the cadence rather than the wrist.
     */
    public static boolean anglePairUsable(long gapSec, int samplesA, int samplesB) {
        if (gapSec <= 0 || gapSec > ANGLE_PAIR_MAX_SEC) return false;
        if (samplesA <= 0 || samplesA > ANGLE_MAX_SAMPLES) return false;
        if (samplesB <= 0 || samplesB > ANGLE_MAX_SAMPLES) return false;
        return true;
    }

    /** Above this the arm is being carried about, which is the one thing a session's median
     *  angle change can still be trusted to say. Measured per burst: 64% of waking bursts are
     *  above it against 16% of sleeping ones. */
    public static final double ANGLE_CARRIED_DEG = 5.0;

    /**
     * What to make of a session, from the median change in arm angle across it.
     *
     * This was a yes or a no, and the wearer's labels have shown there is no line to draw. Real
     * sleep runs 0.019 to 0.2709 and the one labelled desk afternoon sits at 0.231, inside it.
     * Movement, pulse, posture and the spread of posture were all measured against the same
     * labels and none of them divides the two either.
     *
     * So: below van Hees's figure, say sleep and mean it. Above the angle at which the arm is
     * plainly being carried about, refuse. Between them, and when the measure could not be
     * computed at all, count it as sleep and mark it doubtful - refusing those was costing about
     * three hours a day of real naps against roughly one desk afternoon a week wrongly counted,
     * and the wearer would rather correct a marked number than be handed a quiet one.
     *
     * @return "sleep", "doubtful" or "not"
     */
    public static String sessionVerdict(double medianChangeDeg) {
        if (Double.isNaN(medianChangeDeg) || medianChangeDeg < 0) return "doubtful";
        if (medianChangeDeg > ANGLE_CARRIED_DEG) return "not";
        if (medianChangeDeg < SLEEP_ANGLE_CHANGE_MAX) return "sleep";
        return "doubtful";
    }

    /** Whether a verdict's minutes go into the day's total. Doubtful ones do; that is the point
     *  of counting them and marking them rather than choosing between the two. */
    public static boolean countsAsSleep(String verdict) {
        return "sleep".equals(verdict) || "doubtful".equals(verdict);
    }

    /** The median of the first {@code n} entries, which this sorts. NaN if there are none. */
    public static double median(double[] v, int n) {
        if (v == null || n <= 0) return Double.NaN;
        double[] c = new double[n];
        System.arraycopy(v, 0, c, 0, n);
        java.util.Arrays.sort(c);
        return c[n / 2];
    }
}
