package org.watchlauncher;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Speed, large, with the pulse under it.
 *
 * The two numbers come from opposite ends of the device. The heart rate is a
 * sensor the framework exposes and reads like any other. The speed is not:
 * this watch keeps its {@code gps} provider out of {@code Enabled Providers}
 * entirely and holds no last known location, because position is the tracker
 * firmware's business and it runs its own {@code gpsd} on a ten-minute cycle
 * rather than feeding Android's location stack.
 *
 * So the speed has two sources, in order of freshness:
 *
 *   1. a live fix, if the provider can be turned on and holds one -- what you
 *      actually want while moving;
 *   2. the last fix the tracker logged, which is what is there when the
 *      provider is off.
 *
 * Whichever it used is said on screen, with the age of the fix, because a
 * speed with no age on it is a number you cannot trust.
 */
public class SportsScreen extends Screen implements HeartRate.Listener {

    /** Past this, a logged fix is history rather than a reading. */
    private static final long STALE_MS = 15 * 60 * 1000L;

    private TextView vSpeed, vSpeedUnit, vBpm, vBpmUnit, vNote;

    private HeartRate hr;
    private LocationManager locations;
    private TrackerLog log;
    private ServerFix server;
    private String sleepState = "";

    private final Handler ui = new Handler();

    private float speedMs = -1;
    private long fixAt = 0;
    private String source = "";
    private boolean listening = false;
    private boolean loading = false;

    @Override
    public String title() { return "Sports"; }

    @Override
    protected View build() {
        locations = (LocationManager) shell.getSystemService(Context.LOCATION_SERVICE);
        hr = new HeartRate(shell, this);
        log = new TrackerLog(shell, shell.root());
        server = new ServerFix(shell);

        LinearLayout col = Ui.column(shell);
        col.setGravity(Gravity.CENTER_VERTICAL);

        // The speed is the reason the screen exists, so it gets the room. 54px
        // is as large as three digits fit at 240 wide.
        vSpeed = Ui.text(shell, 54, Ui.FG, true);
        vSpeedUnit = Ui.text(shell, Ui.SMALL_PX, Ui.MUTED, false);
        vSpeedUnit.setText("km/h");

        LinearLayout pulse = Ui.row(shell);
        pulse.setGravity(Gravity.CENTER);
        AppIcons heart = new AppIcons(shell, AppIcons.HEART);
        heart.setColour(Ui.WARN);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(18, 18);
        hp.rightMargin = 6;
        pulse.addView(heart, hp);
        vBpm = Ui.text(shell, 28, Ui.FG, true);
        vBpmUnit = Ui.text(shell, Ui.SMALL_PX, Ui.MUTED, false);
        vBpmUnit.setText(" bpm");
        vBpmUnit.setPadding(0, 8, 0, 0);          // sits on the number's baseline
        pulse.addView(vBpm, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        pulse.addView(vBpmUnit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        vNote = Ui.text(shell, Ui.HINT_PX, Ui.FAINT, false);

        int mp = ViewGroup.LayoutParams.MATCH_PARENT;
        col.addView(vSpeed, Ui.lp(mp, 0, 0));
        col.addView(vSpeedUnit, Ui.lp(mp, 0, 0));
        col.addView(Ui.spacer(shell, 10));
        col.addView(pulse, Ui.lp(mp, 0, 0));
        col.addView(Ui.spacer(shell, 6));
        col.addView(vNote, Ui.lp(mp, 0, 0));
        return col;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onShow() {
        hr.start();
        startLocation();
        readLastFix();
        reloadLog();
        render();
    }

    /**
     * The tracker's own log, which is where the fixes actually are. Copying
     * 115 KB through a root shell and running two queries is far too slow for
     * the UI thread, so it happens on its own and the screen redraws when it
     * lands.
     */
    private void reloadLog() {
        if (loading) return;
        loading = true;
        new Thread(new Runnable() {
            public void run() {
                // The server first: it holds resolved positions and a speed it
                // has already computed, which is more than the watch can work
                // out from its own frames. The local log still runs, so the
                // screen has an answer with no network.
                server.refresh();
                log.refresh();
                ui.post(new Runnable() {
                    public void run() {
                        loading = false;
                        render();
                    }
                });
            }
        }).start();
    }

    @Override
    public void onHide() {
        hr.stop();
        stopLocation();
    }

    @Override
    public void tick() { render(); }

    public void onHeartRate(int bpm) { render(); }

    // ---------------------------------------------------------------- location

    /** Ask for updates from every provider that will talk to us. On this watch
     *  that is usually only {@code passive}, which reports whatever the
     *  tracker's own fixes happen to push through the framework. */
    private void startLocation() {
        if (locations == null || listening) return;
        listening = true;
        List<String> all = locations.getAllProviders();
        if (all == null) return;
        for (int i = 0; i < all.size(); i++) {
            String p = all.get(i);
            try {
                if (!locations.isProviderEnabled(p)) continue;
                locations.requestLocationUpdates(p, 1000L, 0f, updates);
            } catch (Exception e) {
                // SecurityException on a provider we may not use, or the
                // provider vanished between the list and the request.
            }
        }
    }

    private void stopLocation() {
        if (!listening) return;
        listening = false;
        try { locations.removeUpdates(updates); } catch (Exception e) { /* ignore */ }
    }

    /** Whatever the framework already holds, before any live fix arrives. */
    private void readLastFix() {
        if (locations == null) return;
        List<String> all = locations.getAllProviders();
        if (all == null) return;
        for (int i = 0; i < all.size(); i++) {
            try {
                take(locations.getLastKnownLocation(all.get(i)), "last fix");
            } catch (Exception e) { /* not permitted, or nothing there */ }
        }
    }

    private void take(Location l, String from) {
        if (l == null) return;
        long when = l.getTime();
        if (when <= 0) return;
        if (when < fixAt) return;                 // an older fix than we have
        fixAt = when;
        speedMs = l.hasSpeed() ? l.getSpeed() : -1;
        source = from + " (" + l.getProvider() + ")";
        render();
    }

    private final LocationListener updates = new LocationListener() {
        public void onLocationChanged(Location l) { take(l, "live"); }
        public void onProviderEnabled(String p) { }
        public void onProviderDisabled(String p) { }
        public void onStatusChanged(String p, int status, Bundle extras) { }
    };

    // ---------------------------------------------------------------- render

    private void render() {
        if (vSpeed == null) return;

        // Three sources, best first. The server has resolved positions and a
        // computed speed; the local log can only measure when the watch had a
        // real GPS lock; LocationManager has never had anything on this
        // device but costs nothing to check.
        float kmhLive = (speedMs >= 0) ? speedMs * 3.6f : -1;

        if (server.speed() >= 0) {
            renderSpeed(server.speed(), server.at());
        } else if (log.speed() >= 0) {
            renderSpeed(log.speed(), log.fixAt());
        } else if (kmhLive >= 0) {
            renderSpeed(kmhLive, fixAt);
        } else {
            vSpeed.setText("--");
            vSpeed.setTextColor(Ui.MUTED);
        }

        renderPulse();
        vNote.setText(note());
        shell.renderHint();
    }

    private void renderSpeed(float kmh, long at) {
        // One decimal below 10, none above: at a walk the tenths are the only
        // thing moving, and at speed they are noise.
        vSpeed.setText(kmh < 10f ? String.format("%.1f", kmh)
                                 : Integer.toString(Math.round(kmh)));
        boolean old = at == 0 || (System.currentTimeMillis() - at) > STALE_MS;
        vSpeed.setTextColor(old ? Ui.MUTED : Ui.FG);
    }

    /** The live sensor when it has spoken, else the last logged reading. */
    private void renderPulse() {
        int live = hr.bpm();
        int logged = log.bpm();
        int show = (live >= 0) ? live : logged;
        if (show < 0) {
            vBpm.setText("--");
            vBpm.setTextColor(Ui.MUTED);
        } else {
            vBpm.setText(Integer.toString(show));
            vBpm.setTextColor(live >= 0 ? Ui.FG : Ui.MUTED);
        }
    }

    /** One line saying where each number came from and how old it is, because
     *  a stale fix and a live one look identical otherwise. */
    private String note() {
        StringBuilder b = new StringBuilder();
        if (loading) return "reading log...";

        // Say which of the three answered and how old it is. A speed with no
        // provenance on it is a number you cannot act on.
        if (server.speed() >= 0) {
            b.append(server.typeName()).append(' ')
             .append(ago(System.currentTimeMillis() - server.at()));
        } else if (log.speed() >= 0) {
            // An average between two fixes ten minutes apart, not an
            // instantaneous reading, so say what it spans.
            b.append("gps over ").append(span(log.speedSpanMs())).append(' ')
             .append(ago(System.currentTimeMillis() - log.fixAt()));
        } else if (fixAt > 0) {
            b.append(source).append(' ').append(ago(System.currentTimeMillis() - fixAt));
        } else if (server.at() > 0) {
            // A position but no speed: a cell fix, which cannot produce one.
            b.append(server.typeName()).append(" fix, no speed");
        } else if (server.problem() != null) {
            b.append(server.problem());
        } else if (log.problem() != null) {
            b.append(log.problem());
        } else {
            b.append("no fix");
        }

        if (hr.measuring()) {
            b.append("   measuring");
        } else if (hr.bpm() >= 0) {
            b.append("   pulse ").append(ago(hr.age()));
        } else if (log.bpm() >= 0) {
            b.append("   pulse ").append(ago(System.currentTimeMillis() - log.bpmAt()));
        } else if (!hr.available()) {
            b.append("   no pulse sensor");
        } else {
            b.append("   no pulse yet");
        }
        return b.toString();
    }

    private boolean gpsOff() {
        try {
            return locations != null
                    && !locations.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return true;
        }
    }

    /** A duration without the "ago", for "gps over 10m". */
    private static String span(long ms) {
        String s = ago(ms);
        int cut = s.indexOf(" ago");
        return cut < 0 ? s : s.substring(0, cut);
    }

    private static String ago(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s ago";
        long m = s / 60;
        if (m < 60) return m + "m ago";
        return (m / 60) + "h ago";
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean onGesture(int button, int kind) {
        if (button == ShellActivity.BTN_A) {
            if (kind == ShellActivity.TAP) {
                readLastFix();
                reloadLog();
                hr.start();                      // one fresh pulse reading
                render();
                return true;
            }
            shell.push(new SportsMenuScreen(this));
            return true;
        }
        return true;
    }

    @Override
    public String hint() {
        return shell.twoButtons() ? "A:refresh  hold:menu"
                                  : "tap:refresh  hold:menu";
    }

    // ---------------------------------------------------------------- menu

    HeartRate heartRate() { return hr; }

    boolean gpsProviderOff() { return gpsOff(); }

    String sleepState() { return sleepState.length() == 0 ? "ready" : sleepState; }

    /** What the sleep detector is doing, as one phrase. There is nothing to
     *  turn on: it watches all day and starts logging on its own. */
    String sleepTracking() {
        if (!SleepLog.enabled(shell)) return "off";
        if (SleepLog.state(shell) == SleepLog.LOGGING) {
            return "asleep, " + SleepLog.countTonight();
        }
        int today = SleepLog.dayMinutes(shell);
        return today > 0 ? ("awake, " + today + "min today") : "watching";
    }

    /**
     * Score the most recent night's log and send the result. Reading a night
     * of epochs off the card and running a rolling median over them is not UI
     * thread work.
     */
    void scoreLastNight() {
        if (sleepState.equals("scoring")) return;
        sleepState = "scoring";
        shell.toast("scoring");
        new Thread(new Runnable() {
            public void run() {
                final String result = doScore();
                ui.post(new Runnable() {
                    public void run() {
                        sleepState = result;
                        shell.toast(result);
                    }
                });
            }
        }).start();
    }

    private String doScore() {
        try {
            String night = SleepLog.latestNight();
            if (night == null) return "no log yet";

            SleepScore.Result r = SleepScore.score(SleepLog.read(night));
            if (!r.valid) return r.why;

            TrackerConfig cfg = new TrackerConfig(shell, shell.root());
            cfg.load();
            SleepUpload up = new SleepUpload();
            int n = up.sendScore(cfg, r);
            if (up.problem() != null) return up.problem();
            if (n == 0) return "no reply";
            SleepLog.markScored(shell, night);
            // The efficiency is the one number worth seeing on the watch; the
            // rest is on the website graph, which is a better place for it.
            return r.tstMin + "min, " + r.efficiencyPct + "%";
        } catch (Exception e) {
            return "score failed";
        }
    }

    /**
     * Push the nights the firmware recorded up to the tracker server, over the
     * tracker's own protocol. Off the UI thread: it copies a database through
     * a root shell and then opens a socket.
     */
    void sendSleep() {
        if (sleepState.equals("sending")) return;
        sleepState = "sending";
        shell.toast("sending sleep data");
        new Thread(new Runnable() {
            public void run() {
                final String result = doSendSleep();
                ui.post(new Runnable() {
                    public void run() {
                        sleepState = result;
                        shell.toast(result);
                    }
                });
            }
        }).start();
    }

    private String doSendSleep() {
        try {
            String last = SleepUpload.lastSent(shell.prefs());
            java.util.List<SleepUpload.Night> nights = log.sleepNights(last);
            if (nights.isEmpty()) return "nothing new";

            TrackerConfig cfg = new TrackerConfig(shell, shell.root());
            cfg.load();
            if (!cfg.usable()) return "no imei";

            SleepUpload up = new SleepUpload();
            int n = up.send(cfg, nights);
            if (up.problem() != null) return up.problem();
            if (n == 0) return "no reply";
            // Only once the server has acknowledged them; a failed run has to
            // be repeatable or a night would be lost silently.
            SleepUpload.markSent(shell.prefs(), nights);
            return nights.size() + " night" + (nights.size() == 1 ? "" : "s") + " sent";
        } catch (Exception e) {
            return "failed";
        }
    }

    /** For the menu: whether the server source is set up and answering. */
    String serverState() {
        if (!server.configured()) return "no tracker.txt";
        if (server.problem() != null) return server.problem();
        return server.at() > 0 ? server.typeName() : "no reply yet";
    }

    /** Turn the gps provider on. It is off in secure settings, which needs
     *  either a signature permission or root; the terminal's shell is already
     *  here, so use that. */
    void enableGps() {
        RootShell sh = shell.root();
        if (!sh.isRoot()) { shell.toast("needs the root helper"); return; }
        sh.exec("settings put secure location_providers_allowed +gps");
        stopLocation();
        startLocation();
        shell.toast(gpsOff() ? "could not enable gps" : "gps enabled");
    }

    static class SportsMenuScreen extends ListScreen {
        private final SportsScreen sports;

        SportsMenuScreen(SportsScreen s) { sports = s; }

        @Override
        public String title() { return "Sports"; }

        @Override
        protected List<Item> items() {
            List<Item> l = list();
            l.add(new Item("Refresh", null, AppIcons.GEAR));
            l.add(new Item("GPS provider",
                    sports.gpsProviderOff() ? "off" : "on", AppIcons.DEVICE));
            l.add(new Item("Pulse sensor",
                    sports.heartRate().available() ? "found" : "none",
                    AppIcons.HEART));
            l.add(new Item("Server", sports.serverState(), AppIcons.DEVICE));
            l.add(new Item("Send sleep", sports.sleepState(), AppIcons.HEART));
            l.add(new Item("Sleep", sports.sleepTracking(), AppIcons.GEAR));
            l.add(new Item("Score last night", null, AppIcons.HEART));
            addBack(l);
            l.add(new Item("Exit sports", null, AppIcons.HOME));
            return l;
        }

        @Override
        protected void onPick(int index) {
            switch (index) {
                case 0: sports.readLastFix(); sports.reloadLog(); shell.pop(); break;
                case 1: sports.enableGps(); render(); break;
                case 2: break;                       // a readout, not an action
                case 3: break;                       // likewise
                case 4: sports.sendSleep(); render(); break;
                case 5: break;                       // a readout, not an action
                case 6: sports.scoreLastNight(); render(); break;
                case 7: shell.pop(); break;
                default: shell.popToRoot(); break;
            }
        }
    }
}
