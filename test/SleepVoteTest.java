import org.watchlauncher.SleepVote;
import org.watchlauncher.SleepWatcher;

/**
 * The vote, against the distributions it was set from.
 *
 * Every threshold here is a percentile of a measured burst, not a judgement. The cases that
 * matter are the ones where a signal must say nothing: the overlap between a sleeping pulse and
 * a sedentary waking one is real, and a vote that picks a side there is the same mistake as the
 * veto it replaces.
 */
public class SleepVoteTest {

    static int fails = 0;

    static void check(String what, boolean ok, String detail) {
        System.out.printf("  %-54s %s%s%n", what, ok ? "ok" : "FAILED",
                detail.isEmpty() ? "" : ("   " + detail));
        if (!ok) fails++;
    }

    static final int RESTING = 51;

    public static void main(String[] args) {
        System.out.println("sleep vote:");

        // --- movement: weak, and known to be weak ---------------------------------------------
        check("a motionless burst votes for sleep",
                SleepVote.movement(0.000) == SleepVote.FOR, "three quarters of a night");
        check("a real movement votes against",
                SleepVote.movement(0.089) == SleepVote.AGAINST, "");
        check("a shift in between says nothing",
                SleepVote.movement(0.020) == SleepVote.ABSTAIN, "sleepers turn over");

        // --- pulse: the cleanest divider, and still not clean ----------------------------------
        // Per burst: asleep p90 55, awake p10 56, against a resting of 51.
        check("the sleeping 90th percentile votes for sleep",
                SleepVote.pulse(55, RESTING) == SleepVote.FOR, "55 = resting + 4");
        check("a deep sleeping rate does too",
                SleepVote.pulse(49, RESTING) == SleepVote.FOR, "");
        check("the waking 10th percentile does not vote for sleep",
                SleepVote.pulse(56, RESTING) != SleepVote.FOR, "56 = resting + 5");
        check("a plainly active rate votes against",
                SleepVote.pulse(67, RESTING) == SleepVote.AGAINST, "awake p90");
        check("the overlap says nothing rather than picking",
                SleepVote.pulse(57, RESTING) == SleepVote.ABSTAIN,
                "asleep reaches 55, sedentary sits about 54");
        check("no pulse abstains rather than voting awake",
                SleepVote.pulse(0, RESTING) == SleepVote.ABSTAIN, "the whole point");
        check("no resting estimate abstains too",
                SleepVote.pulse(52, 0) == SleepVote.ABSTAIN, "");

        // --- angle: strongest for sleep, and per burst rather than per session -----------------
        check("below van Hees's figure votes for sleep",
                SleepVote.angle(0.048, true) == SleepVote.FOR, "asleep p25");
        check("a sleeper's third quartile does not vote against",
                SleepVote.angle(0.417, true) != SleepVote.AGAINST,
                "0.30 made a third of every night vote itself awake");
        check("an arm carried about votes against",
                SleepVote.angle(16.3, true) == SleepVote.AGAINST, "awake median");
        check("an unusable pair says nothing",
                SleepVote.angle(0.01, false) == SleepVote.ABSTAIN, "five minutes is not one step");
        check("no previous angle says nothing",
                SleepVote.angle(Double.NaN, true) == SleepVote.ABSTAIN, "");

        // --- the count: abstentions must not dilute -------------------------------------------
        int moveOnly = SleepVote.net(0.000, Double.NaN, false, 0, 0, -1);
        check("movement alone still carries a full verdict",
                moveOnly == SleepVote.FULL, moveOnly + " of " + SleepVote.FULL);
        check("nothing at all is no evidence, not a tie against",
                SleepVote.net(-1, Double.NaN, false, 0, 0, -1) == 0, "");

        // The night of 23-24 September, from 04:33: still, pulse 49 against a resting 51, angle
        // inside van Hees's figure. The shipped watcher gave up on this and never came back.
        check("a sleeping burst nets strongly for sleep",
                SleepVote.net(0.000, 0.05, true, 49, RESTING, -1) == SleepVote.FULL, "");
        // And the same wearer turning over, which used to cancel the night outright.
        int turning = SleepVote.net(0.089, 8.0, true, 49, RESTING, -1);
        check("turning over counts against but not fatally",
                turning < 0 && turning > -SleepVote.FULL, turning + "");

        // --- a watch on a bedside table outvotes the rest -------------------------------------
        check("off the wrist outvotes every other signal",
                SleepVote.net(0.000, 0.01, true, 49, RESTING, 0) < 0, "the stillest thing there is");
        check("worn is not evidence of sleep by itself",
                SleepVote.wear(1) == SleepVote.ABSTAIN, "it rules out one thing only");
        check("a detector that cannot say abstains",
                SleepVote.wear(-1) == SleepVote.ABSTAIN, "");

        // --- the accumulator -------------------------------------------------------------------
        check("a full vote for sleep credits the whole step",
                SleepWatcher.lean(0, 300, SleepVote.FULL) == 300, "");
        check("half a vote credits half",
                SleepWatcher.lean(0, 300, SleepVote.FULL / 2) == 150, "");
        check("no evidence moves it nowhere",
                SleepWatcher.lean(600, 300, 0) == 600, "");
        check("it cannot bank a whole night",
                SleepWatcher.lean(SleepWatcher.LEAN_CAP, 3600, SleepVote.FULL)
                        == SleepWatcher.LEAN_CAP, "or the morning spends hours undoing it");
        check("nor the reverse",
                SleepWatcher.lean(-SleepWatcher.LEAN_CAP, 3600, -SleepVote.FULL)
                        == -SleepWatcher.LEAN_CAP, "");
        check("a gap credited nothing changes nothing",
                SleepWatcher.lean(600, 0, -SleepVote.FULL) == 600, "");

        check("thirty minutes of sleep evidence starts a night",
                SleepWatcher.onset(SleepWatcher.ONSET_SEC), "");
        check("twenty-nine does not",
                !SleepWatcher.onset(SleepWatcher.ONSET_SEC - 60), "");
        check("twenty minutes against ends one",
                SleepWatcher.woke(-SleepWatcher.WAKE_SEC), "");
        check("crossing over starts the count again",
                SleepWatcher.afterChange() == 0,
                "or a long night has to be undone before it can end");

        // --- the case the whole thing exists for ------------------------------------------------
        // 60% still and 40% moving, asleep, pulse at resting. The shipped rule wants about three
        // to one and stalls here for ever; this has to reach the bar.
        int lean = 0;
        for (int i = 0; i < 200; i++) {
            boolean still = (i % 5) < 3;
            int net = still ? SleepVote.net(0.000, 0.05, true, 50, RESTING, -1)
                            : SleepVote.net(0.060, 8.0, true, 50, RESTING, -1);
            lean = SleepWatcher.lean(lean, 35, net);
            if (SleepWatcher.onset(lean)) break;
        }
        check("a night that is three parts still to two reaches the bar",
                SleepWatcher.onset(lean), "23 September, from 04:33");

        System.out.println(fails == 0 ? "sleep vote: all checks passed"
                                      : "sleep vote: " + fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
