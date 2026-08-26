
import org.watchlauncher.Circadian;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * The rhythm measures, checked against days whose answer is known by
 * construction: a sleeper who is still from 23:00 to 07:00 and active
 * otherwise should give L5 in the small hours, a relative amplitude near one,
 * and - given identical days - an interdaily stability near one too.
 */
public class CircadianTest {

    static int fails = 0;

    static void check(String what, boolean ok, String detail) {
        System.out.printf("  %-46s %s%s%n", what, ok ? "ok" : "FAILED",
                detail.isEmpty() ? "" : ("   " + detail));
        if (!ok) fails++;
    }

    static Circadian.Day day(long startMs, boolean regular, double noise) {
        int n = (24 * 60) / 5;
        long[] at = new long[n];
        double[] enmo = new double[n];
        for (int i = 0; i < n; i++) {
            int min = i * 5;
            at[i] = startMs + min * 60000L;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(at[i]);
            int hour = c.get(Calendar.HOUR_OF_DAY);
            boolean asleep = regular ? (hour >= 23 || hour < 7) : ((min / 37) % 3 == 0);
            enmo[i] = (asleep ? 0.002 : 0.060) + ((min * 7919) % 13) / 1000.0 * noise;
        }
        return new Circadian.Day(at, enmo);
    }

    public static void main(String[] args) {
        long base = 1700000000000L;
        long dayMs = 24L * 3600 * 1000;

        List<Circadian.Day> regular = new ArrayList<Circadian.Day>();
        for (int d = 0; d < 7; d++) regular.add(day(base + d * dayMs, true, 0.2));
        Circadian.Result r = Circadian.of(regular);

        check("seven regular days score", r.valid, r.why);
        System.out.printf("     L5 %.4f at %s, M10 %.4f at %s%n",
                r.l5, Circadian.clock(r.l5StartMin), r.m10, Circadian.clock(r.m10StartMin));
        System.out.printf("     RA %.3f  IV %.3f  IS %.3f  over %d days%n",
                r.relativeAmplitude, r.intradailyVariability, r.interdailyStability, r.days);

        check("L5 lands in the night", r.l5StartMin >= 22 * 60 || r.l5StartMin <= 3 * 60,
                Circadian.clock(r.l5StartMin));
        check("L5 is quieter than M10", r.l5 < r.m10, String.format("%.4f < %.4f", r.l5, r.m10));
        check("relative amplitude is high for a settled sleeper",
                r.relativeAmplitude > 0.8, String.format("%.3f", r.relativeAmplitude));
        check("interdaily stability is high for identical days",
                r.interdailyStability > 0.7, String.format("%.3f", r.interdailyStability));

        List<Circadian.Day> chaotic = new ArrayList<Circadian.Day>();
        for (int d = 0; d < 7; d++) chaotic.add(day(base + d * dayMs, false, 0.2));
        Circadian.Result c = Circadian.of(chaotic);
        System.out.printf("%n     chaotic days: RA %.3f  IV %.3f  IS %.3f%n",
                c.relativeAmplitude, c.intradailyVariability, c.interdailyStability);
        check("a broken rhythm has a lower amplitude",
                c.relativeAmplitude < r.relativeAmplitude, "");
        check("a broken rhythm is more variable within the day",
                c.intradailyVariability > r.intradailyVariability, "");

        List<boolean[]> same = new ArrayList<boolean[]>();
        for (int d = 0; d < 3; d++) {
            boolean[] b = new boolean[24 * 60];
            for (int m = 0; m < b.length; m++) b[m] = (m < 7 * 60 || m >= 23 * 60);
            same.add(b);
        }
        check("identical nights give a regularity index of 100", Circadian.sri(same) == 100,
                String.valueOf(Circadian.sri(same)));

        List<boolean[]> flipped = new ArrayList<boolean[]>();
        boolean[] a = new boolean[24 * 60], b2 = new boolean[24 * 60];
        for (int m = 0; m < a.length; m++) { a[m] = m % 2 == 0; b2[m] = m % 2 == 1; }
        flipped.add(a); flipped.add(b2);
        check("opposite nights give zero", Circadian.sri(flipped) == 0,
                String.valueOf(Circadian.sri(flipped)));
        check("one night alone gives no answer", Circadian.sri(same.subList(0, 1)) == -1, "");

        System.out.println();
        System.out.println(fails == 0 ? "PASS" : (fails + " FAILED"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
