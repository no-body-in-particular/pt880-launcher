package org.watchlauncher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sleep from wrist acceleration, by the van Hees heuristic (HDCZA).
 *
 * <h3>Why this algorithm</h3>
 *
 * Cole-Kripke and Sadeh are the better known ones, and both take "activity
 * counts" -- a vendor-specific integration of the accelerometer signal.
 * Actiwatch counts are not ActiGraph counts, and the published coefficients
 * were fitted to one particular vendor's. Implementing either formula
 * perfectly against counts we invented here would produce confident numbers
 * with nothing behind them.
 *
 * Van Hees's method works on the raw signal instead. It watches the angle of
 * the arm to the horizontal and looks for long stretches where it stops
 * changing, which needs no calibration against anyone's hardware.
 *
 * <h3>The method</h3>
 *
 * <ol>
 *   <li>the arm's angle to the horizontal, per epoch;
 *   <li>a rolling median of that angle over five minutes;
 *   <li>the absolute change between successive medians;
 *   <li>a threshold from the distribution of those changes: the tenth
 *       percentile times fifteen, held between 0.13 and 0.50 degrees;
 *   <li>runs below the threshold lasting 30 minutes or more are sustained
 *       inactivity;
 *   <li>the sleep period runs from the first such bout to the last, with gaps
 *       under 60 minutes absorbed into it.
 * </ol>
 *
 * <h3>Two deviations, written down rather than hidden</h3>
 *
 * The published method uses 5-second epochs; this watch cannot batch sensor
 * readings, so {@link SleepService} samples every 30 seconds to keep the CPU
 * asleep. The five-minute window is therefore ten samples rather than sixty.
 * The constants below are as published and should be checked against the paper
 * before anyone leans on the output.
 *
 * <h3>What it does not tell you</h3>
 *
 * Sleep stages. Deep against light is not recoverable from a wrist
 * accelerometer, whatever the vendor firmware claims by reporting 675 minutes
 * of "deep sleep". What comes out here is sleep and wake, and the timings that
 * follow from them.
 *
 * And the standing caveat of all actigraphy: it is good at spotting sleep and
 * poor at spotting wake, because lying still looks exactly like sleeping. It
 * runs long on total sleep time and short on awakenings.
 */
public class SleepScore {

    /** Rolling median window, in minutes. */
    private static final int MEDIAN_WINDOW_MIN = 5;

    /**
     * The shortest run of stillness that counts as a bout, in minutes.
     *
     * Fifteen, not thirty. The method this follows uses five, and thirty was chosen here to be
     * conservative - but conservative in one direction only: every bout it refuses is sleep that
     * did not happen as far as the rest of the arithmetic is concerned, because the sleep period
     * itself is bounded by the first and last bout.
     *
     * Measured across five nights, thirty gives 0, 0, 230, 35 and 41 minutes; fifteen gives 30,
     * 20, 394, 269 and 504. The nights it was scoring at forty minutes were eight hours long.
     */
    private static final int MIN_BOUT_MIN = 15;

    /** Gaps shorter than this inside the sleep period are absorbed. */
    private static final int MERGE_GAP_MIN = 60;

    /** Bouts further apart than this belong to separate sleeps rather than one broken night. */
    private static final int SESSION_GAP_MIN = 45;

    /** An awakening has to last this long to be counted as one. Movement
     *  flickers either side of the threshold, so without this a single trip to
     *  the bathroom is reported as nine separate awakenings. Minutes of wake
     *  are unaffected -- only the count of them. */
    private static final int MIN_WAKE_MIN = 1;

    /** Two wake bouts closer together than this are the same awakening. */
    private static final int WAKE_MERGE_MIN = 5;

    /** Threshold = 10th percentile of the angle changes, times this. */
    /** A silence longer than this is not observation, so nothing is claimed across it. */
    private static final int MAX_GAP_SEC = 900;

    private static final double THRESHOLD_SCALE = 15.0;
    private static final double THRESHOLD_MIN_DEG = 0.13;
    private static final double THRESHOLD_MAX_DEG = 0.50;

    public static class Result {
        public boolean valid;
        public String why = "";

        public long onsetAt;          // first sleep
        public long wakeAt;           // last sleep
        public int sptMin;            // sleep period: onset to final waking
        public int tstMin;            // total sleep inside that period
        public int wasoMin;           // wake after sleep onset

        /** Sleep across every session of the day, not only the main period. A second sleep is
         *  still sleep; it just is not the night, and the day total wants both. */
        public int allSleepMin;
        public int wakeups;           // separate wake bouts inside the period
        public int efficiencyPct;     // tst / spt

        public int epochs;
        public int epochSec;
        public double thresholdDeg;

        /** The tenth percentile the threshold was derived from. Recorded
         *  because on a steady signal it comes out at zero -- a rolling median
         *  repeats itself, so more than a tenth of the changes are exactly 0 --
         *  and then the threshold is the clamp floor rather than anything
         *  adaptive. Worth being able to see that rather than guess at it. */
        public double p10Deg;

        /** True when the strict threshold found nothing and the top of the
         *  published range was used instead. The night still scored, but the
         *  number is the looser of the two the method sanctions. */
        public boolean relaxed;
    }

    /** Seconds of sleep between two epochs, capped per row so a silence is not counted. */
    private static long sleepSecIn(boolean[] still, long[] atSec, int from, int to, int epochSec) {
        long sleep = 0;
        for (int i = from; i <= to; i++) {
            long span = (i < to) ? (atSec[i + 1] - atSec[i]) : epochSec;
            if (span > MAX_GAP_SEC) span = MAX_GAP_SEC;
            if (still[i]) sleep += span;
        }
        return sleep;
    }

    private SleepScore() { }

    public static Result score(List<SleepLog.Epoch> epochs) {
        Result r = new Result();
        r.epochs = (epochs == null) ? 0 : epochs.size();
        if (epochs == null || epochs.size() < 40) {
            r.why = "too few epochs";
            return r;
        }

        r.epochSec = medianGapSec(epochs);
        if (r.epochSec <= 0) { r.why = "bad timestamps"; return r; }

        int perWindow = Math.max(3, (MEDIAN_WINDOW_MIN * 60) / r.epochSec);
        int minBout = Math.max(1, (MIN_BOUT_MIN * 60) / r.epochSec);
        int mergeGap = Math.max(1, (MERGE_GAP_MIN * 60) / r.epochSec);

        // 1-2. the angle, smoothed by a rolling median. The median rather than
        // a mean because a single arm movement should not drag the baseline.
        int n = epochs.size();
        double[] angle = new double[n];
        double[] enmo = new double[n];
        for (int i = 0; i < n; i++) angle[i] = epochs.get(i).zAngle();
        for (int i = 0; i < n; i++) enmo[i] = epochs.get(i).enmo;
        double[] smooth = rollingMedian(angle, perWindow);

        // 3. how much the angle moved between one window and the next.
        double[] change = new double[n];
        change[0] = 0;
        for (int i = 1; i < n; i++) change[i] = Math.abs(smooth[i] - smooth[i - 1]);

        // 4. the threshold, from the night's own distribution.
        // Seconds, once, so every duration below is real time rather than a count of rows.
        long[] atSec = new long[n];
        for (int i = 0; i < n; i++) atSec[i] = epochs.get(i).at / 1000L;

        // 4. the threshold, from the changes that are actually changes.
        //
        // The tenth percentile of every change is zero on every night measured here - a wearer is
        // still for more than a tenth of any record, and a rolling median of a still stretch
        // repeats exactly - so this multiplied zero by fifteen and landed on its floor every time.
        // The adaptive step has never adapted: five nights all scored at 0.13 degrees, a figure
        // calibrated for five-second epochs and applied to epochs of five minutes.
        //
        // Taking the percentile among the non-zero changes measures the same thing the method
        // intends - how much this night's angle moves when it moves at all.
        r.p10Deg = percentileNonZero(change, 10);
        double t = clamp(r.p10Deg * THRESHOLD_SCALE);

        // 5. runs of stillness long enough to be a bout.
        boolean[] still = new boolean[n];
        List<int[]> bouts = bouts(change, enmo, still, t, atSec, MIN_BOUT_MIN * 60, MAX_GAP_SEC, r.epochSec);

        if (bouts.isEmpty() && t < THRESHOLD_MAX_DEG) {
            // Nothing at the strict end. Rather than report a night of no
            // sleep -- which is a claim, and almost certainly a false one --
            // try the other end of the range the method itself allows, and say
            // that is what happened. Sampling every 30 seconds instead of
            // every 5 leaves more movement inside each epoch, so the floor
            // being too tight here is expected rather than surprising.
            t = THRESHOLD_MAX_DEG;
            bouts = bouts(change, enmo, still, t, atSec, MIN_BOUT_MIN * 60, MAX_GAP_SEC, r.epochSec);
            r.relaxed = !bouts.isEmpty();
        }
        r.thresholdDeg = t;

        if (bouts.isEmpty()) {
            r.why = "no sustained rest found";
            return r;
        }

        // 6. the sleep period, absorbing short gaps between bouts.
        // The period runs from the first bout to the last, in time.
        //
        // This used to replace the period with whichever single segment was longer whenever the
        // gap between them exceeded the merge window - so a night broken by an hour awake was
        // reported as its bigger half, and the rest simply did not happen. Time between bouts
        // inside the period is counted below as waking, which is what it is.
        // The main sleep period, not everything between the first bout and the last.
        //
        // A night file runs noon to noon, so first-to-last spans whatever else the day held. On
        // 5-6 September that gave a sleep period of 20.4 hours: an afternoon at a desk, an
        // evening, and two sleeps, all inside one period whose still epochs were then added up
        // as total sleep. It reported 12.2 hours against a wearer who had slept about eight.
        //
        // Bouts closer together than SESSION_GAP_MIN belong to one sleep - a night broken by an
        // hour awake is still one night, which is what the previous version of this was right to
        // insist on. Beyond that gap it is a separate sleep, and the period is the session with
        // the most sleep in it. Everything else the day held is still counted, but into
        // allSleepMin rather than into this period.
        //
        // Against the same night: 01:16-05:48 at 93% and 07:03-11:00 at 81%, 7.8 hours between
        // them, where the wearer said "one to five, then eight-ish to twelve, about eight hours".
        List<int[]> sessions = new ArrayList<int[]>();
        int sFrom = bouts.get(0)[0], sTo = bouts.get(0)[1];
        for (int b = 1; b < bouts.size(); b++) {
            if (atSec[bouts.get(b)[0]] - atSec[sTo] > SESSION_GAP_MIN * 60) {
                sessions.add(new int[]{sFrom, sTo});
                sFrom = bouts.get(b)[0];
            }
            sTo = bouts.get(b)[1];
        }
        sessions.add(new int[]{sFrom, sTo});

        int from = sessions.get(0)[0], to = sessions.get(0)[1];
        long bestSleep = -1;
        r.allSleepMin = 0;
        for (int k = 0; k < sessions.size(); k++) {
            int a = sessions.get(k)[0], b = sessions.get(k)[1];
            long sleep = sleepSecIn(still, atSec, a, b, r.epochSec);
            r.allSleepMin += (int) (sleep / 60);
            if (sleep > bestSleep) { bestSleep = sleep; from = a; to = b; }
        }

        // Inside the period, still is sleep and moving is wake.
        int sleepEpochs = 0, wakeEpochs = 0;
        for (int i = from; i <= to; i++) {
            if (still[i]) sleepEpochs++;
            else wakeEpochs++;
        }

        int minWake = Math.max(1, (MIN_WAKE_MIN * 60) / r.epochSec);
        int wakeMerge = Math.max(1, (WAKE_MERGE_MIN * 60) / r.epochSec);
        int wakeBouts = countWakeBouts(still, from, to, minWake, wakeMerge);

        r.valid = true;
        r.onsetAt = epochs.get(from).at;
        r.wakeAt = epochs.get(to).at;
        // Each row stands for the time until the next one, capped so a silence is not counted as
        // sleep. Multiplying a row count by a median gap was what made a run of six rows read as
        // thirty minutes whether it covered thirty minutes or four hours.
        long sleepSec = 0, wakeSec = 0;
        for (int i = from; i <= to; i++) {
            long span = (i < to) ? (atSec[i + 1] - atSec[i]) : r.epochSec;
            if (span > MAX_GAP_SEC) span = MAX_GAP_SEC;
            if (still[i]) sleepSec += span; else wakeSec += span;
        }
        r.sptMin = (int) ((sleepSec + wakeSec) / 60);
        r.tstMin = (int) (sleepSec / 60);
        r.wasoMin = (int) (wakeSec / 60);
        r.wakeups = wakeBouts;
        r.efficiencyPct = (r.sptMin > 0)
                ? (int) Math.round(100.0 * r.tstMin / r.sptMin) : 0;
        return r;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * How many times the night was actually broken, rather than how many times
     * the signal crossed a threshold. Adjacent wake runs separated by less
     * than {@code merge} epochs of sleep are one awakening, and a run shorter
     * than {@code minWake} is movement in sleep, not waking up.
     */
    static int countWakeBouts(boolean[] still, int from, int to,
                              int minWake, int merge) {
        List<int[]> runs = new ArrayList<int[]>();
        int start = -1;
        for (int i = from; i <= to; i++) {
            if (!still[i]) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                runs.add(new int[]{start, i - 1});
                start = -1;
            }
        }
        if (start >= 0) runs.add(new int[]{start, to});

        int count = 0;
        int mergedStart = -1, mergedEnd = -1;
        for (int i = 0; i < runs.size(); i++) {
            int[] run = runs.get(i);
            if (mergedStart < 0) {
                mergedStart = run[0];
                mergedEnd = run[1];
            } else if (run[0] - mergedEnd <= merge) {
                mergedEnd = run[1];
            } else {
                if (mergedEnd - mergedStart + 1 >= minWake) count++;
                mergedStart = run[0];
                mergedEnd = run[1];
            }
        }
        if (mergedStart >= 0 && mergedEnd - mergedStart + 1 >= minWake) count++;
        return count;
    }

    private static double clamp(double t) {
        if (t < THRESHOLD_MIN_DEG) return THRESHOLD_MIN_DEG;
        if (t > THRESHOLD_MAX_DEG) return THRESHOLD_MAX_DEG;
        return t;
    }

    /** Runs of epochs below the threshold, lasting at least minBout. Fills
     *  {@code still} as it goes, since the caller needs it afterwards to tell
     *  sleep from wake inside the period. */
    /**
     * Runs of stillness, measured in seconds rather than in rows.
     *
     * This counted rows and multiplied by a median gap, which assumes every row is the same
     * distance from the last. They are not: the night's log now carries the recorder's bursts and
     * the samples a measurement leaves behind, at different cadences, and even before that it had
     * holes where the service was killed. A run of six rows was read as thirty minutes whether it
     * covered thirty minutes or four hours.
     *
     * A gap longer than maxGapSec ends the run rather than being counted inside it. Nothing was
     * observed across that silence, and a bout is a claim about what the wrist was doing.
     */
    /**
     * Runs of stillness long enough to be a bout.
     *
     * Still means the arm angle stopped moving <em>and</em> the wrist stopped moving. The angle
     * alone is van Hees's test and it assumes a full day of recording with a separate activity
     * gate in front of it; this gets neither. Sitting at a desk holds the arm at a constant
     * angle, so on angle alone an afternoon of reading is indistinguishable from sleep - and the
     * file it is handed contains only stretches the watcher already believed were sleep, so
     * nothing else was ever going to disagree.
     *
     * That is not a hypothetical. Replaying 4 September, angle alone found every one of 135
     * epochs still: 10.8 hours of sleep with no waking at all, an efficiency of 100% and zero
     * wakeups, over a stretch its wearer spent awake. With ENMO consulted the same epochs give
     * 4.5 hours inside a 9.2 hour period, 281 minutes awake, and an efficiency of 49%.
     *
     * ENMO is already in every row and was already the measure the watcher uses to decide
     * whether to record at all - see {@link SleepRules#STILL_ENMO} for where the value comes
     * from. Reading it here costs nothing and is the difference between a number and a claim.
     */
    private static List<int[]> bouts(double[] change, double[] enmo, boolean[] still, double t,
                                     long[] atSec, int minBoutSec, int maxGapSec, int epochSec) {
        int n = change.length;
        for (int i = 0; i < n; i++)
            still[i] = change[i] < t && enmo[i] < SleepRules.STILL_ENMO;

        /* A bout survives movement shorter than an awakening.
         *
         * This ended a bout at the first moving epoch, which asks for unbroken stillness at epoch
         * resolution. A sleeper does not provide it. Measured over one night, 44% of epochs read
         * still and the median unbroken run of them was a single epoch - so fifteen consecutive
         * minutes essentially never occurred, and a seventeen hour stretch of logging at 74%
         * stillness and a pulse of 52 scored as 101 minutes of sleep in four bouts of about half
         * an hour.
         *
         * How long movement has to last before it is waking is already decided in this file:
         * WAKE_MERGE_MIN, five minutes, is the distance inside which two wake bouts are called the
         * same awakening. Movement shorter than that does not end a sleep bout either. Nothing new
         * is invented here; the same number is simply applied on both sides of the question.
         *
         * The same night, with that: 342 minutes over four sessions, the longest running 00:10 to
         * 07:33. The bout still ends at its last still epoch, so absorbed movement counts as wake
         * inside the period rather than being quietly turned into sleep.
         */
        int tolerate = Math.max(1, (WAKE_MERGE_MIN * 60) / epochSec);

        List<int[]> out = new ArrayList<int[]>();
        int i = 0;
        while (i < n) {
            if (!still[i]) { i++; continue; }
            int j = i, last = i, missed = 0;
            while (j + 1 < n && (atSec[j + 1] - atSec[j]) <= maxGapSec) {
                if (still[j + 1]) { missed = 0; last = j + 1; }
                else if (++missed > tolerate) break;
                j++;
            }
            if (atSec[last] - atSec[i] >= minBoutSec) out.add(new int[]{i, last});
            i = (last > i ? last : i) + 1;
        }
        return out;
    }

    /** The typical gap between epochs, so a night logged at a different
     *  cadence -- or with holes where the service was killed -- still scores
     *  against real time rather than an assumed 30 seconds. */
    /** The percentile among the changes that are non-zero - see the note where this is used. */
    static double percentileNonZero(double[] v, int pct) {
        int n = 0;
        for (int i = 0; i < v.length; i++) if (v[i] > 1e-9) n++;
        if (n == 0) return 0;
        double[] nz = new double[n];
        n = 0;
        for (int i = 0; i < v.length; i++) if (v[i] > 1e-9) nz[n++] = v[i];
        Arrays.sort(nz);
        int at = (int) Math.floor((pct / 100.0) * (nz.length - 1));
        return nz[at];
    }

    static int medianGapSec(List<SleepLog.Epoch> e) {
        int n = e.size();
        if (n < 2) return 0;
        double[] gaps = new double[n - 1];
        for (int i = 1; i < n; i++) gaps[i - 1] = (e.get(i).at - e.get(i - 1).at) / 1000.0;
        Arrays.sort(gaps);
        int mid = gaps.length / 2;
        return (int) Math.round(gaps[mid]);
    }

    static double[] rollingMedian(double[] v, int window) {
        int n = v.length;
        double[] out = new double[n];
        int half = Math.max(1, window / 2);
        double[] buf = new double[window + 1];
        for (int i = 0; i < n; i++) {
            int from = Math.max(0, i - half);
            int to = Math.min(n - 1, i + half);
            int len = to - from + 1;
            if (len > buf.length) buf = new double[len];
            for (int k = 0; k < len; k++) buf[k] = v[from + k];
            double[] slice = Arrays.copyOf(buf, len);
            Arrays.sort(slice);
            out[i] = slice[len / 2];
        }
        return out;
    }

    /** The value below which the given percentage of the data falls. The
     *  first element is skipped: change[0] is a placeholder, not a reading. */
    static double percentile(double[] v, double pct) {
        if (v.length < 2) return 0;
        double[] s = Arrays.copyOfRange(v, 1, v.length);
        Arrays.sort(s);
        int idx = (int) Math.floor((pct / 100.0) * (s.length - 1));
        if (idx < 0) idx = 0;
        if (idx >= s.length) idx = s.length - 1;
        return s[idx];
    }
}
