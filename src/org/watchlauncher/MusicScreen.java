package org.watchlauncher;

import android.bluetooth.BluetoothDevice;
import android.media.AudioManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Now playing.
 *
 * The player from {@code apps/watchplayer}, moved in whole: the same
 * {@link MusicService}, the same {@link Library} filesystem walk, the same
 * transport gestures. What changed is that it is a screen rather than an
 * activity, so it shares the launcher's status bar instead of drawing its own,
 * and leaving it lands on the launcher rather than on the stock clock face.
 *
 * The service is deliberately not bound to the screen's lifetime -- it is
 * started, and it keeps playing while you take a photo or make a call. Only
 * the menu's Stop really stops it.
 *
 * Gestures here match the standalone player exactly:
 *
 *   A tap    play / pause
 *   A hold   menu
 *   B tap    next track
 *   B hold   previous track
 */
public class MusicScreen extends Screen implements MusicService.Listener {

    private AudioManager audio;

    private TextView vStatus, vTitle, vSub, vVol;
    private LinearLayout volBarRow;
    private View volFill, volRest;

    private int lastVol = -1;
    private long volFlashUntil = 0;

    @Override
    public String title() { return "Music"; }

    @Override
    protected View build() {
        audio = shell.audio();

        LinearLayout now = Ui.column(shell);
        now.setGravity(Gravity.CENTER_VERTICAL);

        vStatus = Ui.text(shell, Ui.SMALL_PX, Ui.ACCENT, false);
        vTitle = Ui.text(shell, Ui.TITLE_PX, Ui.FG, true);
        vTitle.setMaxLines(3);
        vTitle.setEllipsize(TextUtils.TruncateAt.END);
        vSub = Ui.text(shell, Ui.BODY_PX, Ui.DIM, false);
        vVol = Ui.text(shell, Ui.SMALL_PX, 0xFF666666, false);

        // Drawn as views, not block characters: this build's font has no
        // U+25AE/U+25AF, so a text bar renders as blank.
        volFill = new View(shell);
        volRest = new View(shell);
        volBarRow = Ui.row(shell);
        volBarRow.addView(volFill, new LinearLayout.LayoutParams(0, 4, 1));
        volBarRow.addView(volRest, new LinearLayout.LayoutParams(0, 4, 1));
        LinearLayout volWrap = Ui.column(shell);
        volWrap.setPadding(34, 0, 34, 0);
        volWrap.addView(volBarRow,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4));

        int mp = ViewGroup.LayoutParams.MATCH_PARENT;
        now.addView(vStatus, Ui.lp(mp, 0, 0));
        now.addView(Ui.spacer(shell, 8));
        now.addView(vTitle, Ui.lp(mp, 0, 0));
        now.addView(Ui.spacer(shell, 6));
        now.addView(vSub, Ui.lp(mp, 0, 0));
        now.addView(Ui.spacer(shell, 9));
        now.addView(volWrap, Ui.lp(mp, 0, 0));
        now.addView(Ui.spacer(shell, 3));
        now.addView(vVol, Ui.lp(mp, 0, 0));
        return now;
    }

    @Override
    public void onShow() {
        MusicService s = shell.music();
        if (s != null) s.setListener(this);
        render();
    }

    @Override
    public void onHide() {
        // The binding stays; only the callback goes. The whole point of one
        // application is that the music does not stop because you opened the
        // camera.
        MusicService s = shell.music();
        if (s != null) s.setListener(null);
    }

    /** The activity's binding landed after this screen was already up. */
    void onServiceReady() {
        MusicService s = shell.music();
        if (s != null) s.setListener(this);
        render();
    }

    public void onPlayerChanged() { render(); }

    @Override
    public void tick() {
        // Catches volume moved by anyone else (AVRCP absolute volume, the
        // system, another app) so the bar still reflects reality.
        int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (lastVol >= 0 && v != lastVol) volFlashUntil = System.currentTimeMillis() + 2500;
        lastVol = v;
        render();
    }

    /** The activity calls this when a volume key was pressed while this screen
     *  is up, so the bar lights without waiting for the next tick. */
    public void flashVolume() {
        lastVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        volFlashUntil = System.currentTimeMillis() + 2500;
        render();
    }

    private void render() {
        if (vTitle == null) return;
        MusicService svc = shell.music();

        String head = "";
        BluetoothDevice a = shell.bt().connectedAudio();
        if (a != null) {
            String n = null;
            try { n = a.getName(); } catch (Exception e) { /* ignore */ }
            head = (n == null ? "Headphones" : n) + " ✓";
        } else {
            String s = shell.bt().status();
            if (s != null) head = s;
        }
        vStatus.setText(head);

        if (svc == null) {
            vTitle.setText("...");
            vSub.setText("");
            return;
        }

        vTitle.setText(svc.title());

        int n = svc.tracks().size();
        StringBuilder sb = new StringBuilder();
        if (n > 0) {
            sb.append(svc.isPlaying() ? "▶ " : "‖ ");
            sb.append(svc.index() + 1).append(" / ").append(n);
            int d = svc.duration();
            if (d > 0) {
                sb.append("   ").append(Ui.mmss(svc.position()))
                  .append(" / ").append(Ui.mmss(d));
            }
        } else {
            sb.append("Put files in /sdcard/Music");
        }
        if (svc.note().length() > 0) sb.append("\n").append(svc.note());
        vSub.setText(sb.toString());

        renderVolume();
    }

    /** Compact volume readout; the stock volume panel is unusable at 240px. */
    private void renderVolume() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) return;

        boolean flash = System.currentTimeMillis() < volFlashUntil;
        ((LinearLayout.LayoutParams) volFill.getLayoutParams()).weight = cur;
        ((LinearLayout.LayoutParams) volRest.getLayoutParams()).weight = Math.max(0, max - cur);
        volFill.setBackgroundColor(flash ? Ui.ACCENT : 0xFF5A5A5A);
        volRest.setBackgroundColor(0xFF262626);
        volBarRow.requestLayout();

        vVol.setText("vol " + cur + "/" + max);
        vVol.setTextColor(flash ? Ui.ACCENT : 0xFF666666);
    }

    @Override
    public boolean onGesture(int button, int kind) {
        MusicService svc = shell.music();
        if (button == ShellActivity.BTN_A) {
            if (kind == ShellActivity.TAP) {
                if (svc != null) svc.toggle();
                render();
                return true;
            }
            // Any hold opens the menu, however long. People hold a watch
            // button for several seconds, and making a longer hold mean
            // something else just loses them the menu.
            shell.push(new MusicMenuScreen(this));
            return true;
        }
        if (svc == null) return true;
        if (kind == ShellActivity.TAP) svc.next(); else svc.prev();
        render();
        return true;
    }

    MusicService service() { return shell.music(); }

    @Override
    public String hint() {
        return shell.twoButtons() ? "A:play  hold:menu  B:next  B hold:prev"
                                  : "tap:play   hold:menu";
    }

    /** The player's own menu. Bluetooth is not on it: it is its own app on the
     *  launcher, and reaching it through the player was a leftover from when
     *  the player was the only thing installed. */
    static class MusicMenuScreen extends ListScreen {
        private final MusicScreen music;

        MusicMenuScreen(MusicScreen m) { music = m; }

        @Override
        public String title() { return "Music"; }

        @Override
        protected List<Item> items() {
            MusicService s = music.service();
            List<Item> l = list();
            l.add(new Item((s != null && s.isPlaying()) ? "Pause" : "Play",
                    null, AppIcons.MUSIC));
            l.add(new Item("Next track"));
            l.add(new Item("Previous track"));
            l.add(new Item("Volume up"));
            l.add(new Item("Volume down"));
            l.add(new Item("Rescan music"));
            addBack(l);
            l.add(new Item("Exit player", null, AppIcons.HOME));
            return l;
        }

        @Override
        protected void onPick(int index) {
            MusicService s = music.service();
            AudioManager a = shell.audio();
            switch (index) {
                case 0: if (s != null) s.toggle(); break;
                case 1: if (s != null) s.next(); break;
                case 2: if (s != null) s.prev(); break;
                case 3: a.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE, 0); break;
                case 4: a.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER, 0); break;
                case 5:
                    if (s != null) s.rescan();
                    shell.toast("Rescanning");
                    break;
                case 6: shell.pop(); return;             // Back, to now playing
                default:
                    // All the way out to the launcher, past the now-playing
                    // screen. Playback carries on -- that is the point of one
                    // application -- and Pause above is how you stop it.
                    shell.popToRoot();
                    return;
            }
            render();
        }
    }
}
