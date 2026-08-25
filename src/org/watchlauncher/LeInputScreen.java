package org.watchlauncher;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether an LE keyboard or mouse can be made to work here.
 *
 * This build has a Classic HID host and no HOGP, so the ordinary path refuses
 * these devices outright. API 19 does have a GATT client though, and HID over
 * GATT is only a GATT service -- so the app can read the reports itself.
 *
 * Whether it can is a question about encryption, not about code: HOGP devices
 * usually refuse to notify over an unencrypted link, and LE bonding is the
 * weakest part of this Android version. So this screen does not assume. It
 * runs each step and prints what came back, and the descriptor write status is
 * the answer:
 *
 *   ok         reports will arrive, and this is worth building on
 *   status 5   insufficient authentication - the device wants a bond
 *   status 15  insufficient encryption - same answer, different wording
 *
 * Raw reports are shown as bytes rather than decoded. Decoding boot protocol
 * is easy and pointless until it is known that anything arrives.
 */
public class LeInputScreen extends Screen implements LeHid.Listener {

    private static final int MAX_LINES = 200;

    private TextView out;
    private ScrollView scroll;
    private LeHid le;

    private final List<String> lines = new ArrayList<String>();
    private int reports = 0;

    @Override
    public String title() { return "LE input"; }

    @Override
    protected View build() {
        LinearLayout col = Ui.column(shell);
        TextView h = Ui.text(shell, Ui.HEAD_PX, Ui.ACCENT, true);
        h.setGravity(Gravity.LEFT);
        h.setText(title());
        col.addView(h, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));

        out = Ui.mono(shell, Ui.HINT_PX, Ui.DIM);
        scroll = new ScrollView(shell);
        scroll.addView(out);
        col.addView(scroll, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    @Override
    public void onShow() {
        if (le == null) le = new LeHid(shell, this);
        if (lines.isEmpty()) {
            add("A tap scans. Reports appear as raw bytes.");
            add("");
        }
        render();
    }

    @Override
    public void onHide() {
        if (le != null) { le.stopScan(); le.close(); }
    }

    private void add(String s) {
        lines.add(s);
        while (lines.size() > MAX_LINES) lines.remove(0);
    }

    private void render() {
        if (out == null) return;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            b.append(lines.get(i));
            if (i < lines.size() - 1) b.append('\n');
        }
        out.setText(b.toString());
        scroll.post(new Runnable() {
            public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
        shell.renderHint();
    }

    public void onLeHidStatus(String line) {
        add(line);
        render();
    }

    public void onLeHidReport(boolean keyboard, byte[] report) {
        reports++;
        StringBuilder b = new StringBuilder(keyboard ? "kbd " : "mse ");
        for (int i = 0; i < report.length && i < 12; i++) {
            b.append(String.format("%02x ", report[i]));
        }
        add(b.toString());
        render();
    }

    @Override
    public boolean onGesture(int button, int kind) {
        if (button == ShellActivity.BTN_A) {
            if (kind == ShellActivity.TAP) {
                add("--- scanning ---");
                le.scan();
                render();
                return true;
            }
            return false;                      // hold leaves
        }
        scroll.smoothScrollBy(0, kind == ShellActivity.TAP ? 60 : -60);
        return true;
    }

    @Override
    public String hint() {
        return reports > 0 ? (reports + " reports  hold:back")
                           : "A:scan  hold:back  B:scroll";
    }
}
