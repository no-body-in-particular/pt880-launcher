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
 * One LE device, connected to and interrogated, with every step printed.
 *
 * The question this answers is whether a HOGP device will talk to a stack that
 * has no HOGP host. The app can be its own host -- HID over GATT is only a
 * GATT service and API 19 has a full GATT client -- but most such devices
 * refuse to notify over an unencrypted link, and LE bonding is the weakest
 * part of this Android version.
 *
 * So nothing here is assumed. Each step prints its result, and the descriptor
 * write is the one that matters:
 *
 *   ok         reports will arrive; this is worth building on
 *   status 5   insufficient authentication - it wants a bonded link
 *   status 15  insufficient encryption - the same answer, worded differently
 *
 * Reports print as raw bytes. Decoding boot protocol is a short job and a
 * pointless one until it is known that anything arrives at all.
 */
public class LeReportScreen extends Screen implements LeHid.Listener {

    private static final int MAX_LINES = 200;

    private final LeHid le;
    private final LeHid.Found target;
    private final List<String> lines = new ArrayList<String>();

    private TextView out;
    private ScrollView scroll;
    private int reports = 0;
    private boolean started = false;

    LeReportScreen(LeHid le, LeHid.Found target) {
        this.le = le;
        this.target = target;
    }

    @Override
    public String title() { return target.label(); }

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
        le.setListener(this);
        if (!started) {
            started = true;
            // Deliberately does not connect on its own. For LE HID the order
            // matters -- bond first, then connect over the encrypted link --
            // and a connect already in flight blocks a bond request, so
            // auto-connecting here made the correct sequence unreachable.
            add(target.label());
            add(bondState());
            add("");
            add("B hold : bond first");
            add("A      : connect");
            add("");
        }
        render();
    }

    private String bondState() {
        switch (target.device.getBondState()) {
            case android.bluetooth.BluetoothDevice.BOND_BONDED: return "bonded";
            case android.bluetooth.BluetoothDevice.BOND_BONDING: return "bonding";
            default: return "not bonded";
        }
    }

    @Override
    public void onHide() {
        le.stopWatchingBonding();
        le.close();
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

    public void onLeHidStatus(String line) { add(line); render(); }

    public void onLeHidFound() { }

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
                add("--- reconnecting ---");
                le.connect(target.device);
                render();
                return true;
            }
            return false;                        // hold leaves
        }
        if (kind == ShellActivity.TAP) {
            scroll.smoothScrollBy(0, 60);
        } else {
            // A hold on B asks the stack to bond. LE bonding is unreliable on
            // this version, which is why it is a deliberate action rather than
            // something attempted automatically -- but if the device is
            // refusing to talk without encryption, it is the only lever left.
            add("--- requesting bond ---");
            le.bond(target.device);
            render();
        }
        return true;
    }

    /** Keeps the elapsed time moving while a connection is pending, so a slow
     *  connect does not look like a frozen screen. */
    @Override
    public void tick() {
        String p = le.connectProgress();
        if (p.length() > 0) shell.renderHint();
    }

    @Override
    public String hint() {
        if (reports > 0) return reports + " reports  hold:back";
        String p = le.connectProgress();
        if (p.length() > 0) return "connecting " + p + "  hold:back";
        return "A:connect  hold:back  B:scroll  B hold:bond";
    }
}
