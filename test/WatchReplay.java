import org.watchlauncher.SleepLog;
import org.watchlauncher.SleepRules;
import org.watchlauncher.SleepVote;
import org.watchlauncher.SleepWatcher;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * Run the watcher over a night off the watch, and print when it would start and stop logging.
 *
 * The scorer could already be replayed; the watcher could not, and the watcher is the half that
 * has been wrong. Its thresholds have been set three times from arithmetic done by hand on a
 * night's summary statistics, and twice they were set wrong in a way one run of the actual rule
 * would have shown immediately.
 *
 *     java -cp out WatchReplay 2026-09-23.csv watch-2026-09-23.csv --resting 51 +2
 *
 * Both files, because the watcher sees every burst it takes and they are written to whichever
 * file its state at the time selects - the night log while logging, its own while watching. The
 * vitals rows in the night log are not its bursts and are dropped by sample count.
 *
 * What it cannot replay is the bursts that were never written: one refused for not being
 * acceleration leaves no row anywhere. Those are a few per thousand and they widen a gap rather
 * than change a verdict, which the cap on credited time already handles.
 */
public class WatchReplay {

    static SimpleDateFormat when;

    static String t(long ms) { return when.format(new Date(ms)); }

    static String hm(int sec) {
        int m = Math.abs(sec) / 60;
        return (sec < 0 ? "-" : "") + (m / 60) + "h" + String.format("%02d", m % 60);
    }

    /** The watcher's own cadence: fine while something might be happening, coarse when not. */
    static final int FINE_SEC = 30;
    static final int COARSE_SEC = 300;

    public static void main(String[] args) throws Exception {
        int resting = 51, off = 0;
        List<String> files = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if ("--resting".equals(args[i])) resting = Integer.parseInt(args[++i]);
            else if (args[i].startsWith("+") || args[i].startsWith("-"))
                off = Integer.parseInt(args[i].replace("+", ""));
            else files.add(args[i]);
        }
        when = new SimpleDateFormat("dd MMM HH:mm");
        when.setTimeZone(TimeZone.getTimeZone("GMT" + (off < 0 ? "-" : "+") + Math.abs(off)));

        List<SleepLog.Epoch> all = new ArrayList<SleepLog.Epoch>();
        for (int i = 0; i < files.size(); i++) all.addAll(SleepLog.readFile(files.get(i)));
        // Only the watcher's own bursts. A vitals row averages its axes over thirty to eighty
        // seconds and is not a five second burst, whatever else it is good for.
        List<SleepLog.Epoch> e = new ArrayList<SleepLog.Epoch>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).samples > 0 && all.get(i).samples <= 200) e.add(all.get(i));
        }
        Collections.sort(e, new Comparator<SleepLog.Epoch>() {
            public int compare(SleepLog.Epoch a, SleepLog.Epoch b) {
                return a.at < b.at ? -1 : (a.at > b.at ? 1 : 0);
            }
        });
        if (e.size() < 2) { System.out.println("not enough bursts"); return; }

        System.out.println(e.size() + " bursts, " + t(e.get(0).at) + " -> "
                + t(e.get(e.size() - 1).at) + "   resting " + resting
                + "   (times UTC" + (off == 0 ? "" : (off > 0 ? "+" + off : "" + off)) + ")");
        System.out.println();

        int lean = 0, state = 0;
        long stateSince = e.get(0).at;
        int loggedSec = 0;
        double prevAngle = Double.NaN;
        long prevAt = 0;
        List<String> spans = new ArrayList<String>();

        for (int i = 0; i < e.size(); i++) {
            SleepLog.Epoch b = e.get(i);
            long gapMs = prevAt > 0 ? b.at - prevAt : 0;
            int stepSec = SleepRules.credit(gapMs);

            double change = Double.NaN;
            boolean usable = false;
            if (!Double.isNaN(prevAngle) && prevAt > 0) {
                change = Math.abs(b.zAngle() - prevAngle);
                usable = SleepRules.anglePairUsable(gapMs / 1000L, 80, b.samples);
            }

            int net = SleepVote.net(b.enmo, change, usable, b.bpm, resting, -1);
            lean = SleepWatcher.lean(lean, stepSec, net);

            if (state == 0 && SleepWatcher.onset(lean)) {
                spans.add(String.format("   %s  -> logging", t(b.at)));
                state = 1; lean = SleepWatcher.afterChange(); stateSince = b.at;
            } else if (state == 1 && SleepWatcher.woke(lean)) {
                int sec = (int) ((b.at - stateSince) / 1000L);
                loggedSec += sec;
                spans.add(String.format("   %s  -> watching        logged %s", t(b.at), hm(sec)));
                state = 0; lean = SleepWatcher.afterChange(); stateSince = b.at;
            }
            prevAngle = b.zAngle();
            prevAt = b.at;
        }
        if (state == 1) {
            int sec = (int) ((e.get(e.size() - 1).at - stateSince) / 1000L);
            loggedSec += sec;
            spans.add(String.format("   %s  (still logging)   logged %s",
                    t(e.get(e.size() - 1).at), hm(sec)));
        }

        System.out.println("what the vote would have done:");
        if (spans.isEmpty()) System.out.println("   never started logging");
        for (int i = 0; i < spans.size(); i++) System.out.println(spans.get(i));
        System.out.println();
        System.out.println("   total logged: " + hm(loggedSec));
    }
}
