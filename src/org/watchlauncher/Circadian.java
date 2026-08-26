package org.watchlauncher;

import java.util.ArrayList;
import java.util.List;

/**
 * Rest-activity rhythm, from the same epochs the sleep scoring uses.
 *
 * The sleep score answers "how did last night go". These answer the other
 * question actigraphy is used for: whether the days have a rhythm at all, and
 * whether it is the same rhythm from one day to the next. They are the
 * standard non-parametric measures - the ones GGIR reports and the literature
 * has used since Van Someren - and they need nothing the watch is not already
 * recording.
 *
 * <ul>
 *   <li><b>L5</b> the least active five hours, and when it starts. In a
 *       settled sleeper that is the middle of the night, and its start time
 *       drifts when the rhythm does.
 *   <li><b>M10</b> the most active ten hours, and when they start.
 *   <li><b>RA</b> relative amplitude, (M10-L5)/(M10+L5). One means the active
 *       and rest halves of the day are completely different; zero means they
 *       are the same, which is what a disrupted rhythm looks like.
 *   <li><b>IV</b> intradaily variability - how much the signal jumps from one
 *       hour to the next. High means a day broken into fragments.
 *   <li><b>IS</b> interdaily stability - how alike the days are. Needs more
 *       than one day and gets better with several.
 *   <li><b>SRI</b> the sleep regularity index of Phillips and colleagues: the
 *       chance of being in the same state - asleep or awake - at the same
 *       clock time on two consecutive days, scaled so 100 is perfectly
 *       regular and 0 is a coin toss.
 * </ul>
 *
 * Everything is computed on a fixed grid of hourly bins rather than on the
 * raw epochs, because the watch does not sample at a constant rate: it wakes
 * every five minutes while watching and every thirty seconds while logging,
 * so an unbinned mean would weight the nights it was busiest.
 */
public class Circadian {

    /** Bins per day. Hourly is what the literature uses for IV and IS. */
    private static final int BINS = 24;

    /** SRI compares states minute by minute; this is the grid it uses. */
    private static final int SRI_BINS = 24 * 60;

    public static class Result {
        public boolean valid;
        public String why = "";

        public double l5;             // mean ENMO over the quietest five hours
        public int l5StartMin;        // minutes past midnight
        public double m10;
        public int m10StartMin;
        public double relativeAmplitude;
        public double intradailyVariability;
        public double interdailyStability;
        public int days;

        public int sri = -1;          // -1 when fewer than two nights
    }

    /**
     * One day's samples: when each was taken, and how much movement it saw.
     *
     * Plain arrays rather than the log's own type, so this can be run and
     * checked on a desktop JVM. The maths is the part worth testing and it
     * needs nothing from Android.
     */
    public static class Day {
        public final long[] at;
        public final double[] enmo;
        public Day(long[] at, double[] enmo) { this.at = at; this.enmo = enmo; }
    }

    /**
     * @param days one day of samples each, oldest first
     */
    public static Result of(List<Day> days) {
        Result r = new Result();
        if (days == null || days.isEmpty()) {
            r.why = "no days";
            return r;
        }

        // Hourly means of ENMO, one row per day. A bin with no epochs is left
        // as NaN rather than zero: no data is not the same as no movement,
        // and treating it as stillness would invent sleep.
        List<double[]> rows = new ArrayList<double[]>();
        for (int d = 0; d < days.size(); d++) {
            double[] sum = new double[BINS];
            int[] n = new int[BINS];
            Day day = days.get(d);
            for (int i = 0; i < day.at.length; i++) {
                int bin = binOf(day.at[i], BINS);
                sum[bin] += day.enmo[i];
                n[bin]++;
            }
            double[] row = new double[BINS];
            for (int i = 0; i < BINS; i++) row[i] = n[i] > 0 ? sum[i] / n[i] : Double.NaN;
            rows.add(row);
        }
        r.days = rows.size();

        double[] mean = meanAcrossDays(rows);
        if (countReal(mean) < BINS / 2) {
            r.why = "less than half a day covered";
            return r;
        }

        int[] l5 = quietestRun(mean, 5);
        int[] m10 = busiestRun(mean, 10);
        if (l5 == null || m10 == null) {
            r.why = "not enough consecutive hours";
            return r;
        }
        r.l5 = runMean(mean, l5[0], 5);
        r.l5StartMin = l5[0] * 60;
        r.m10 = runMean(mean, m10[0], 10);
        r.m10StartMin = m10[0] * 60;

        double denom = r.m10 + r.l5;
        r.relativeAmplitude = denom > 0 ? (r.m10 - r.l5) / denom : 0;

        r.intradailyVariability = iv(rows);
        r.interdailyStability = rows.size() >= 2 ? is(rows) : Double.NaN;
        r.valid = true;
        return r;
    }

    /** Which hourly bin a timestamp falls in, in local time. */
    private static int binOf(long at, int bins) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(at);
        int minutes = c.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + c.get(java.util.Calendar.MINUTE);
        int b = minutes * bins / (24 * 60);
        return b < 0 ? 0 : (b >= bins ? bins - 1 : b);
    }

    private static double[] meanAcrossDays(List<double[]> rows) {
        double[] out = new double[BINS];
        for (int i = 0; i < BINS; i++) {
            double s = 0;
            int n = 0;
            for (double[] row : rows) {
                if (!Double.isNaN(row[i])) { s += row[i]; n++; }
            }
            out[i] = n > 0 ? s / n : Double.NaN;
        }
        return out;
    }

    private static int countReal(double[] v) {
        int n = 0;
        for (double d : v) if (!Double.isNaN(d)) n++;
        return n;
    }

    /** The run of the given length with the smallest mean; wraps midnight,
     *  because the quietest five hours nearly always do. */
    private static int[] quietestRun(double[] v, int len) {
        return extremeRun(v, len, true);
    }

    private static int[] busiestRun(double[] v, int len) {
        return extremeRun(v, len, false);
    }

    private static int[] extremeRun(double[] v, int len, boolean smallest) {
        int best = -1;
        double bestVal = smallest ? Double.MAX_VALUE : -Double.MAX_VALUE;
        for (int start = 0; start < BINS; start++) {
            double m = runMean(v, start, len);
            if (Double.isNaN(m)) continue;
            if (smallest ? m < bestVal : m > bestVal) { bestVal = m; best = start; }
        }
        return best < 0 ? null : new int[] { best };
    }

    private static double runMean(double[] v, int start, int len) {
        double s = 0;
        int n = 0;
        for (int i = 0; i < len; i++) {
            double d = v[(start + i) % BINS];
            if (!Double.isNaN(d)) { s += d; n++; }
        }
        // Most of the window has to be real, or a run that happens to sit in
        // a gap wins by having almost no data in it.
        return n >= len - 1 ? s / n : Double.NaN;
    }

    /** Intradaily variability: mean squared hour-to-hour change over the
     *  variance of the whole series. Van Someren's definition. */
    private static double iv(List<double[]> rows) {
        List<Double> all = new ArrayList<Double>();
        for (double[] row : rows) for (double d : row) if (!Double.isNaN(d)) all.add(d);
        if (all.size() < 3) return Double.NaN;

        double mean = 0;
        for (double d : all) mean += d;
        mean /= all.size();

        double var = 0;
        for (double d : all) var += (d - mean) * (d - mean);
        var /= all.size();
        if (var <= 0) return 0;

        double diffs = 0;
        int n = 0;
        for (double[] row : rows) {
            for (int i = 1; i < row.length; i++) {
                if (Double.isNaN(row[i]) || Double.isNaN(row[i - 1])) continue;
                double d = row[i] - row[i - 1];
                diffs += d * d;
                n++;
            }
        }
        return n > 0 ? (diffs / n) / var : Double.NaN;
    }

    /** Interdaily stability: how much of the total variance is explained by
     *  the average day. One is a perfectly repeating rhythm. */
    private static double is(List<double[]> rows) {
        double[] avg = meanAcrossDays(rows);
        List<Double> all = new ArrayList<Double>();
        for (double[] row : rows) for (double d : row) if (!Double.isNaN(d)) all.add(d);
        if (all.size() < BINS) return Double.NaN;

        double mean = 0;
        for (double d : all) mean += d;
        mean /= all.size();

        double var = 0;
        for (double d : all) var += (d - mean) * (d - mean);
        var /= all.size();
        if (var <= 0) return 0;

        double between = 0;
        int n = 0;
        for (int i = 0; i < BINS; i++) {
            if (Double.isNaN(avg[i])) continue;
            between += (avg[i] - mean) * (avg[i] - mean);
            n++;
        }
        if (n == 0) return Double.NaN;
        return (between / n) / var;
    }

    /**
     * Sleep regularity index, over a run of scored nights.
     *
     * The chance of being in the same state at the same clock minute on two
     * consecutive days, rescaled so that 100 is identical days and 0 is no
     * better than chance. Unlike the others this needs the sleep scoring
     * rather than raw movement, because it is about state, not activity.
     *
     * @param asleep one bitmap per day, SRI_BINS long, true where asleep
     */
    public static int sri(List<boolean[]> asleep) {
        if (asleep == null || asleep.size() < 2) return -1;
        long same = 0, total = 0;
        for (int d = 1; d < asleep.size(); d++) {
            boolean[] a = asleep.get(d - 1), b = asleep.get(d);
            if (a == null || b == null || a.length != b.length) continue;
            for (int i = 0; i < a.length; i++) {
                if (a[i] == b[i]) same++;
                total++;
            }
        }
        if (total == 0) return -1;
        double p = (double) same / total;
        int v = (int) Math.round(200.0 * p - 100.0);
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }

    /** Minutes past midnight as a clock time, for a screen. */
    public static String clock(int minutes) {
        int m = ((minutes % (24 * 60)) + 24 * 60) % (24 * 60);
        return String.format("%02d:%02d", m / 60, m % 60);
    }
}
