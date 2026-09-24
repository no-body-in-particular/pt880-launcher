import org.watchlauncher.SleepLog;
import org.watchlauncher.SleepScore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * Run the shipped scorer over a night pulled off the watch, and print what it decided.
 *
 * Not a test - a tool. Every wrong answer this scorer has given was found by reading the night's
 * numbers by hand and reasoning about what the code would do with them, which is how a threshold
 * that refused three real nights survived two rounds of review. This runs the code instead.
 *
 *     java -cp out SleepReplay 2026-09-23.csv [+2]
 *
 * The optional second argument is the wearer's offset from UTC, for readable times; the watch
 * itself runs on GMT.
 */
public class SleepReplay {

    static SimpleDateFormat when;

    static String t(long ms) {
        return ms <= 0 ? "-" : when.format(new Date(ms));
    }

    static String hm(int min) {
        return (min / 60) + "h" + String.format("%02d", min % 60);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: SleepReplay <night.csv> [utcOffsetHours]");
            System.exit(2);
        }
        int off = args.length > 1 ? Integer.parseInt(args[1].replace("+", "")) : 0;
        when = new SimpleDateFormat("dd MMM HH:mm");
        when.setTimeZone(TimeZone.getTimeZone("GMT" + (off < 0 ? "-" : "+") + Math.abs(off)));

        List<SleepLog.Epoch> epochs = SleepLog.readFile(args[0]);
        int shortBursts = 0;
        for (int i = 0; i < epochs.size(); i++) {
            if (epochs.get(i).samples > 0 && epochs.get(i).samples <= 200) shortBursts++;
        }
        System.out.println(args[0] + ": " + epochs.size() + " epochs, "
                + shortBursts + " of them five second bursts, "
                + t(epochs.get(0).at) + " -> " + t(epochs.get(epochs.size() - 1).at)
                + "   (times UTC" + (off == 0 ? "" : (off > 0 ? "+" + off : "" + off)) + ")");
        System.out.println();

        SleepScore.Result r = SleepScore.score(epochs);

        System.out.println("sessions the scorer found:");
        System.out.printf("  %-13s %-6s %6s %9s %9s  %s%n",
                "from", "to", "min", "medRange", "medAngle", "verdict");
        if (r.sessionLog != null) {
            for (int i = 0; i < r.sessionLog.size(); i++) {
                String[] p = r.sessionLog.get(i).split(",");
                System.out.printf("  %-13s %-6s %6s %9s %9s  %s%n",
                        t(Long.parseLong(p[0])),
                        t(Long.parseLong(p[1])).substring(7),
                        p[2], p[3], p[4], p[5]);
            }
        }
        System.out.println();

        if (!r.valid) {
            System.out.println("not scorable: " + r.why);
            System.out.println("epochs=" + r.epochs + " epochSec=" + r.epochSec);
            return;
        }
        System.out.println("slept      " + hm(r.tstMin));
        System.out.println("in bed     " + hm(r.sptMin));
        System.out.println("awake      " + hm(r.wasoMin));
        System.out.println("efficiency " + r.efficiencyPct + "%");
        System.out.println("wakeups    " + r.wakeups);
        System.out.println("from       " + t(r.onsetAt) + " to " + t(r.wakeAt));
        System.out.println("all sleep  " + hm(r.allSleepMin) + "   (every session, not just the best)");
        System.out.println("epochSec   " + r.epochSec + "   (the angle test needs <=15)");
        System.out.println("p10Deg     " + String.format("%.4f", r.p10Deg));
    }
}
