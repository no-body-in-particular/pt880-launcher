package org.watchlauncher;

/**
 * Whether the watcher should be logging a night, from the running count of the votes.
 *
 * One signed accumulator in place of the two it replaces. {@link SleepRules#held} counted
 * seconds of stillness towards an onset and {@link SleepRules#moved} counted seconds of movement
 * towards a wake, each decaying when the other's evidence arrived. Two counters with the same
 * job disagree at the edges: a stretch that is 60% still builds held slowly and moved slowly and
 * can sit for hours reaching neither bar, which is precisely what the small hours of 23 September
 * did - three more hours of sleep after the watcher had already given up on the night.
 *
 * Here time is credited in one direction or the other according to what the signals actually
 * said, so a stretch that is three parts sleep to two parts movement moves towards sleep at the
 * difference rather than stalling. The bars either side are the same thirty and twenty minutes
 * as before, and mean the same thing: sustained evidence, not a single burst.
 */
public final class SleepWatcher {

    /** Sustained evidence of sleep, in seconds, before a night starts being logged. */
    public static final int ONSET_SEC = 30 * 60;

    /** Sustained evidence of waking before it stops. Less, because ending late costs a night
     *  only its tail, where starting early costs an afternoon its whole length. */
    public static final int WAKE_SEC = 20 * 60;

    /**
     * How far the count may run past the bar in either direction.
     *
     * Without this, eight hours of sound sleep banks eight hours of credit, and the morning then
     * has to spend all of it before the night can end - the watcher would log halfway through
     * the following afternoon. Capping just past the far bar keeps the decision about the last
     * hour rather than the whole night.
     */
    public static final int LEAN_CAP = 45 * 60;

    private SleepWatcher() { }

    /**
     * Move the count on by one burst.
     *
     * @param leanSec where it stood
     * @param stepSec how much time this burst speaks for, capped by the caller the way every
     *                other gap in this codebase is - a silence is not evidence
     * @param net     {@link SleepVote#net}, from -FULL awake to +FULL asleep
     */
    public static int lean(int leanSec, int stepSec, int net) {
        if (stepSec <= 0) return leanSec;
        long next = leanSec + (long) stepSec * net / SleepVote.FULL;
        if (next > LEAN_CAP) next = LEAN_CAP;
        if (next < -LEAN_CAP) next = -LEAN_CAP;
        return (int) next;
    }

    /** Enough sustained evidence to start logging a night. */
    public static boolean onset(int leanSec) {
        return leanSec >= ONSET_SEC;
    }

    /** Enough sustained evidence that the night has ended. */
    public static boolean woke(int leanSec) {
        return leanSec <= -WAKE_SEC;
    }

    /**
     * What the count should be set to on crossing into the other state.
     *
     * Zero, not the bar it just crossed. Carrying the credit over means a night that took an
     * hour of evidence to start needs an hour of evidence undone before it can end, on top of
     * the twenty minutes that are supposed to decide it.
     */
    public static int afterChange() {
        return 0;
    }
}
