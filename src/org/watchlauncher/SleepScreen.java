package org.watchlauncher;

import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Last night, scored.
 *
 * The numbers existed before this screen did - they were computed, uploaded to the tracker and
 * then only visible on a website. The watch that measured the night could not show it.
 *
 * Everything here is read from the night's own log rather than from what was last sent, so a night
 * that failed to upload still reads correctly, and a night still in progress reads as far as it has
 * got. Scoring runs off the UI thread: it reads a night of epochs off the card and runs a rolling
 * median over them.
 */
public class SleepScreen extends Screen {

    private static final SimpleDateFormat WHEN =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final Handler ui = new Handler();
    private TextView body;
    private volatile String scored;
    private volatile boolean working;

    @Override
    public String title() { return "Sleep"; }

    @Override
    public String hint() { return "tap:rescore  hold:back"; }

    @Override
    protected View build() {
        LinearLayout col = Ui.column(shell);

        TextView h = Ui.text(shell, Ui.HEAD_PX, Ui.ACCENT, true);
        h.setGravity(Gravity.LEFT);
        h.setText(title());
        h.setPadding(0, 0, 0, 4);
        col.addView(h, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));

        body = Ui.mono(shell, Ui.SMALL_PX, Ui.DIM);
        ScrollView s = new ScrollView(shell);
        s.addView(body);
        col.addView(s, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    @Override
    public void onShow() { refresh(); }

    /** A tap rescores, because a night that ended while the watch was out of signal is common. */
    @Override
    public boolean onGesture(int button, int kind) {
        if (working) return true;
        working = true;
        scored = "reading the night...";
        refresh();
        new Thread(new Runnable() {
            public void run() {
                final String out = describe();
                working = false;
                scored = out;
                ui.post(new Runnable() { public void run() { refresh(); } });
            }
        }, "sleep-screen").start();
        return true;
    }

    private void refresh() {
        if (body == null) return;
        StringBuilder b = new StringBuilder();

        // What the detector is doing now, which is the question a glance is usually asking.
        if (!SleepLog.enabled(shell)) {
            b.append("tracking  off\n");
        } else if (SleepLog.state(shell) == SleepLog.LOGGING) {
            b.append("now       asleep, ").append(SleepLog.countTonight()).append("\n");
        } else {
            b.append("now       awake\n");
        }
        int today = SleepLog.dayMinutes(shell);
        b.append("today     ").append(today > 0 ? (hm(today)) : "-").append("\n");
        int rest = SleepLog.restingBpm(shell);
        b.append("resting   ").append(rest > 0 ? (rest + " bpm") : "not learned yet").append("\n");
        b.append("\n");
        b.append(scored == null ? "tap to score last night" : scored);
        body.setText(b.toString());
    }

    /** The last night's log, read and scored here rather than taken from what was uploaded. */
    private String describe() {
        try {
            String night = SleepLog.latestNight();
            if (night == null) return "no log yet";
            SleepScore.Result r = SleepScore.score(SleepLog.read(night));
            if (!r.valid) return night + "\n" + (r.why == null ? "not scorable" : r.why);

            StringBuilder b = new StringBuilder();
            b.append(night).append("\n");
            b.append("slept     ").append(hm(r.tstMin)).append("\n");
            b.append("in bed    ").append(hm(r.sptMin)).append("\n");
            b.append("awake     ").append(hm(r.wasoMin)).append("\n");
            b.append("efficiency ").append(r.efficiencyPct).append("%\n");
            b.append("wakeups   ").append(r.wakeups).append("\n");
            if (r.onsetAt > 0 && r.wakeAt > 0) {
                b.append("from      ").append(WHEN.format(new Date(r.onsetAt)))
                 .append(" to ").append(WHEN.format(new Date(r.wakeAt))).append("\n");
            }
            // Sleep outside the main period is still sleep, and on this wearer it is often most
            // of the day's total - see SleepScore, where the period is one session and this is
            // every one of them.
            if (r.allSleepMin > r.tstMin) {
                b.append("all naps  ").append(hm(r.allSleepMin)).append("\n");
            }
            b.append("epochs    ").append(r.epochs).append(" at ").append(r.epochSec).append("s\n");
            if (r.relaxed) b.append("(threshold relaxed)\n");
            return b.toString();
        } catch (Throwable t) {
            return "could not read the night";
        }
    }

    private static String hm(int minutes) {
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
