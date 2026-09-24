package org.watchlauncher;

/**
 * What each signal thinks of one burst, and what they think together.
 *
 * The watcher used to decide from movement alone, with the pulse able to veto an onset and
 * nothing else able to say anything. That shape has one failure it cannot avoid: a signal that
 * cannot answer is indistinguishable from one that answers no. A missing pulse doubled the
 * stillness a night needed; a burst of movement cancelled stillness outright, so a sleeper who
 * turns over was counted against their own night.
 *
 * The night of 23 September is the case. From 04:33 the wearer slept for another three hours at
 * a pulse of 48 to 52 against a resting 51, and the watcher never went back to logging, because
 * its own bursts ran 60% still and 40% moving and the rule wanted about three to one. The longest
 * unbroken still run was 812 seconds against a bar of 1800. Every other signal available at the
 * time said sleep and none of them were asked.
 *
 * So each signal votes, with the weight its evidence deserves, and a signal that cannot answer
 * abstains rather than voting against. Abstentions do not dilute: the net is taken over the
 * signals that actually voted, so movement alone still carries a full verdict when it is the
 * only thing there.
 *
 * Every threshold below is two numbers rather than one. Between them a signal says nothing,
 * which is the honest reading of a measurement that sits between the distributions it is meant
 * to tell apart - and those distributions were measured on this wearer, not assumed.
 */
public final class SleepVote {

    /*
     * The weights and thresholds below are measured, per burst, on this wrist.
     *
     * They were first set from session medians and applied per burst, which is a different
     * quantity and was wrong by a factor of fifteen on the angle - 30% of sleeping bursts turn
     * the arm further than a session median ever does, and every one of them voted awake. The
     * distributions, over 90 bursts from the night of 23-24 September and 33 from the waking
     * hours either side of it:
     *
     *                  asleep                        awake
     *     |dAng|   p25 0.048  med 0.131  p75 0.417   p25 0.847  med 16.3  p75 33.5
     *     pulse    p10 49     med 53     p90 55      p10 56     med 58    p90 67
     *     enmo     p25 0      med 0      p75 0       p25 0      med 0.012 p75 0.056
     *
     * Which is also the order of their weight. The pulse divides the two almost exactly at
     * resting plus four. The angle is the only measure that has been shown to tell a desk from
     * a bed on this wrist, and below van Hees's 0.13 it is three awake bursts in a hundred.
     * Movement is the weakest of the three and had the most weight: a wrist at a desk is
     * perfectly still for three quarters of its bursts, which is the complaint this all started
     * from.
     */

    public static final int FOR = 1;
    public static final int AGAINST = -1;
    public static final int ABSTAIN = 0;

    /** Net is scaled to this so the accumulator can stay in integers. */
    public static final int FULL = 1000;

    private SleepVote() { }

    // ------------------------------------------------------------------ movement

    /** Below this the wrist is doing nothing. The same figure the scorer uses. */
    public static final double STILL_ENMO = SleepRules.STILL_ENMO;

    /**
     * Above this the wrist is being moved rather than shifting.
     *
     * Measured on the night of 23 September, in the hours the wearer was plainly asleep: the
     * bursts that were not still read 0.0506, 0.0514 and 0.0896. Those are real movements and
     * they should count against, but they are also what a sleeper does, which is why the vote
     * is a vote and not a veto - three of them against nine still bursts still nets to sleep.
     */
    public static final double MOVING_ENMO = 0.050;

    /**
     * The weakest of the three, and it used to carry the most weight.
     *
     * It is present on every burst, which is not the same as being informative: a quarter of
     * sleeping bursts read above the still threshold and a quarter of waking ones read exactly
     * zero. Someone sitting at a desk is motionless most of the time, and telling that from
     * sleep is the whole problem.
     */
    public static final int W_MOVE = 1;

    public static int movement(double enmo) {
        if (enmo < 0) return ABSTAIN;
        if (enmo < STILL_ENMO) return FOR;
        if (enmo > MOVING_ENMO) return AGAINST;
        return ABSTAIN;
    }

    // ------------------------------------------------------------------ pulse

    /**
     * At or below its resting rate plus this, a pulse is positive evidence of sleep.
     *
     * Four, against a resting estimate of 51. Per burst across the night of 23-24 September
     * the sleeping rate ran to a 90th percentile of 55 and the waking one started at a 10th of
     * 56, so resting plus four sits in the gap rather than inside either distribution. Two, the
     * first guess here, was inside the sleeping one and threw away half of its own evidence.
     */
    public static final int PULSE_SLEEP_MARGIN = 4;

    /**
     * Above resting plus this, a pulse is evidence against.
     *
     * Seven rather than the veto's five, because this one has to survive being wrong. A rate
     * three or four above resting is where sleeping and sitting still genuinely overlap on this
     * wearer - asleep reaches 55 and sedentary sits about 54 - and a signal that cannot tell
     * them apart should say so rather than pick.
     */
    public static final int PULSE_AWAKE_MARGIN = 7;

    public static final int W_PULSE = 2;

    public static int pulse(int bpm, int restingBpm) {
        if (bpm <= 0 || restingBpm <= 0) return ABSTAIN;
        if (bpm <= restingBpm + PULSE_SLEEP_MARGIN) return FOR;
        if (bpm > restingBpm + PULSE_AWAKE_MARGIN) return AGAINST;
        return ABSTAIN;
    }

    // ------------------------------------------------------------------ arm angle

    /** Van Hees's own figure, for the five second epochs these bursts are. */
    public static final double ANGLE_SLEEP_DEG = SleepRules.SLEEP_ANGLE_CHANGE_MAX;

    /**
     * Above this the arm is being carried about rather than resting.
     *
     * Five degrees, per burst. This was 0.30, taken from the gap between session medians -
     * asleep 0.019 to 0.107 and the nearest thing that was not 0.231 - and a session median is
     * not a burst. Per burst a sleeping wrist reaches 0.417 at its third quartile and 13.8 at
     * its ninetieth, because sleepers turn over; at 0.30 nearly a third of every real night
     * voted itself awake. Above five degrees is 64% of waking bursts and 16% of sleeping ones,
     * which is the most a single burst of this can honestly claim.
     *
     * The positive side keeps van Hees's own 0.13 and needs no widening: below it there are 50%
     * of sleeping bursts against 3% of waking ones.
     */
    public static final double ANGLE_AWAKE_DEG = 5.0;

    /**
     * The heaviest of the three. It is the only measure that has been shown to separate a desk
     * from a bed on this wrist, which is the distinction the whole watcher exists to make.
     */
    public static final int W_ANGLE = 3;

    /**
     * @param changeDeg how far the arm's angle moved since the previous burst
     * @param usable    whether the two bursts were close enough together for that to mean what
     *                  the thresholds say - five minutes apart the arm has had five minutes to
     *                  move, and the number measures the cadence rather than the wrist
     */
    public static int angle(double changeDeg, boolean usable) {
        if (!usable || Double.isNaN(changeDeg) || changeDeg < 0) return ABSTAIN;
        if (changeDeg < ANGLE_SLEEP_DEG) return FOR;
        if (changeDeg > ANGLE_AWAKE_DEG) return AGAINST;
        return ABSTAIN;
    }

    // ------------------------------------------------------------------ wear

    /**
     * A watch on a bedside table is the stillest thing in the house, and this is the one veto.
     *
     * Everything else here is a vote because every other signal is evidence about a sleeper who
     * might be asleep or might not. This one is not evidence about the wearer at all: it says
     * the other three are measuring a table, and no weight on a table's stillness is worth
     * counting. It was a weight of five to begin with, which did not even outvote the other
     * three - they come to six - so a watch lying still off the wrist read as sleep with every
     * signal agreeing. A veto that has to be arithmetically lucky is not a veto.
     *
     * It only ever votes against. A detector that says the watch is worn has ruled out the one
     * thing it can rule out and said nothing about whether its wearer is asleep, and a detector
     * that cannot answer returns -1, which is not evidence of anything.
     */
    public static int wear(int worn) {
        return worn == 0 ? AGAINST : ABSTAIN;
    }

    // ------------------------------------------------------------------ the count

    /**
     * What the signals say together, from {@code -FULL} (awake) to {@code +FULL} (asleep).
     *
     * Divided by the weight that voted rather than the weight that exists, so abstaining costs a
     * signal its say and nothing else. Nobody voting is zero, which the accumulator reads as no
     * evidence either way rather than as a tie.
     *
     * @param worn 1 worn, 0 not, -1 the detector could not say
     */
    public static int net(double enmo, double changeDeg, boolean angleUsable,
                          int bpm, int restingBpm, int worn) {
        // The veto, before the count. See W_WEAR's neighbours: this is not a heavy vote, it is
        // the finding that the other three measured furniture.
        if (wear(worn) == AGAINST) return -FULL;

        int m = movement(enmo), p = pulse(bpm, restingBpm);
        int a = angle(changeDeg, angleUsable);

        int sum = m * W_MOVE + p * W_PULSE + a * W_ANGLE;
        int cast = (m != ABSTAIN ? W_MOVE : 0) + (p != ABSTAIN ? W_PULSE : 0)
                 + (a != ABSTAIN ? W_ANGLE : 0);
        if (cast == 0) return 0;
        return sum * FULL / cast;
    }
}
