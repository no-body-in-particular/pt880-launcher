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
     * Seconds of stillness while watching for sleep. Movement ends the run outright.
     *
     * Asymmetric with {@link #moved} on purpose: a wrist that moves is awake now, whereas a
     * sleeper who lies still for a minute in the morning has not gone back to sleep.
     */
    public static int held(int heldSec, int stepSec, boolean still) {
        return still ? heldSec + stepSec : 0;
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
}
