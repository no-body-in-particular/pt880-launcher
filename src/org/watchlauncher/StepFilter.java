package org.watchlauncher;

/**
 * Which of the chip's steps the wearer actually took.
 *
 * The DA217 counts steps in hardware and nothing here can change how it counts. What it counts,
 * on a wrist in a moving vehicle, is road vibration. Measured over one day - 7,310 steps reported,
 * with the increments timed against the pulse the vitals path was recording anyway:
 *
 *     258 steps/min   1,960 steps   pulse 47
 *     177 steps/min   1,770 steps   pulse 46
 *      78 steps/min     570 steps   pulse 52
 *      34 steps/min     280 steps   pulse 52
 *
 * 258 a minute is about twice a sprinter's cadence, and the wearer's resting pulse is 47. Half
 * the day's total arrived at rates no one can walk, with a heart rate underneath them saying the
 * body was at rest. Filtered, the same day gives about 2,730 - which matches what the accelerometer
 * says: the wrist was still through the stretches those bursts landed in.
 *
 * The pulse is the discriminator rather than GPS speed, which was the obvious choice and is the
 * worse one: speed cannot tell a runner from a passenger, and a filter keyed on it would throw
 * away exactly the steps someone most wants counted. A heart rate tells them apart directly - a
 * runner's is high, a passenger's is not - and it is already read every three minutes.
 *
 * When there is no fresh pulse the cadence cap stands alone. That is weaker, and deliberately so:
 * a cap only refuses the impossible, where the pulse test refuses the merely implausible.
 */
public final class StepFilter {

    /**
     * Above this, in steps a minute, the wearer is doing something rather than pottering.
     *
     * Below it the pulse test is not applied at all, because a slow drift of steps with a resting
     * heart rate is what standing up and moving about a room looks like, and that is real. The
     * day measured has several such stretches - 22 a minute at a pulse of 47, 17 at 45 - and they
     * are kept.
     */
    public static final int REST_RATE_PER_MIN = 30;

    /**
     * The fastest cadence to credit, in steps a minute.
     *
     * Elite distance runners turn over at about 180 and ordinary running is 160 to 170, so 140 is
     * above anything this wearer will do on foot and well below the 258 the chip reported from a
     * car. Anything faster is trimmed to this rather than dropped, because a burst of real
     * walking that overlaps a rough road should not vanish entirely.
     */
    public static final int MAX_RATE_PER_MIN = 140;

    /**
     * What the counter rose by between two readings.
     *
     * The chip counts since boot, so a reading lower than the one before it means the watch
     * restarted and the counter began again - the rise is then the whole of the new reading,
     * which is what has been walked since the reboot. Before anything has been seen there is no
     * rise at all: crediting the counter's entire history as one increment would hand a fresh
     * install several thousand steps it did not watch anybody take.
     */
    public static int rise(int rawWas, int rawNow) {
        if (rawNow < 0) return 0;
        if (rawWas < 0) return 0;
        return (rawNow < rawWas) ? rawNow : rawNow - rawWas;
    }

    private StepFilter() { }

    /**
     * How many of {@code rawInc} steps to believe.
     *
     * @param rawInc      what the counter rose by
     * @param elapsedMs   the time it rose in
     * @param bpm         the most recent pulse, or 0 if none is fresh
     * @param restingBpm  this wearer's resting rate, or 0 if not yet learned
     */
    public static int credit(int rawInc, long elapsedMs, int bpm, int restingBpm) {
        if (rawInc <= 0) return 0;
        if (elapsedMs <= 0) return 0;

        double minutes = elapsedMs / 60000.0;
        if (minutes <= 0) return 0;
        double rate = rawInc / minutes;

        // A body at rest is not taking thirty steps a minute, whatever the wrist felt.
        if (rate > REST_RATE_PER_MIN && SleepRules.pulseSaysSleep(bpm, restingBpm)) return 0;

        int cap = (int) (MAX_RATE_PER_MIN * minutes);
        return rawInc < cap ? rawInc : cap;
    }
}
