package org.watchlauncher;

import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A root shell on the watch, typed on a Bluetooth keyboard.
 *
 * Pair one from the Bluetooth app and it becomes a real terminal: the keys
 * arrive as ordinary Android key events, so there is nothing to emulate. The
 * two hardware buttons are not a fallback for typing -- a character wheel
 * driven by two buttons is a novelty, not an input method -- they scroll the
 * output and open a short list of built-in commands, which is enough to check
 * an IP address or reboot with nothing else attached.
 *
 *   A tap    the built-in command list
 *   A hold   back to the launcher
 *   B tap    scroll down      B hold  scroll up
 *
 * With a keyboard attached:
 *
 *   Enter        run           Backspace   delete
 *   Up / Down    history       Esc         clear the line
 *   Ctrl-L       clear screen  Ctrl-C      abandon the line
 *   Ctrl-D       back to the launcher
 *
 * Commands run on a background thread. A shell command that takes twenty
 * seconds is normal, and running it on the UI thread would stop the clock.
 */
public class TermScreen extends Screen {

    private static final int MAX_LINES = 400;

    private TextView vOut, vIn;
    private ScrollView vScroll;

    private final StringBuilder buffer = new StringBuilder();
    private final List<String> lines = new ArrayList<String>();
    private final List<String> history = new ArrayList<String>();
    private final Handler ui = new Handler();

    private int historyAt = -1;
    private boolean busy = false;
    private boolean opening = false;

    @Override
    public String title() { return "Terminal"; }

    @Override
    protected View build() {
        LinearLayout col = Ui.column(shell);

        vOut = Ui.mono(shell, Ui.HINT_PX, Ui.DIM);
        vOut.setPadding(0, 0, 0, 2);
        vScroll = new ScrollView(shell);
        vScroll.addView(vOut);
        col.addView(vScroll, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        vIn = Ui.mono(shell, Ui.SMALL_PX, Ui.ACCENT);
        vIn.setGravity(Gravity.LEFT);
        vIn.setMaxLines(2);
        col.addView(vIn, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));

        return col;
    }

    @Override
    public void onShow() {
        if (lines.isEmpty() && !opening) banner();
        renderOut();
        renderIn();
    }

    /**
     * Opening the shell forks a process and runs `id` in it, which is not
     * instant on this SoC. Doing that on the UI thread would stop the clock
     * for the first half second of every visit.
     */
    private void banner() {
        opening = true;
        print("opening shell...");
        new Thread(new Runnable() {
            public void run() {
                final RootShell sh = shell.root();
                final boolean ok = sh.open();
                ui.post(new Runnable() {
                    public void run() {
                        opening = false;
                        lines.clear();
                        if (!ok) {
                            print("no shell available");
                        } else {
                            print(sh.identity() == null ? sh.describe() : sh.identity());
                            if (!sh.isRoot()) {
                                print("");
                                print("not root. install the helper:");
                                print("  apps/watchlauncher/install-root-helper.sh");
                            }
                        }
                        print("");
                        if (!shell.keyboardAttached()) {
                            print("no keyboard: pair one in Bluetooth,");
                            print("or tap A for built-in commands.");
                            print("");
                        }
                        renderOut();
                        renderIn();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- output

    private void print(String s) {
        if (s == null) return;
        String[] parts = s.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            // A long line wraps into the 240px width as it is; splitting it
            // here would break copy-paste-free reading of paths.
            lines.add(parts[i]);
        }
        while (lines.size() > MAX_LINES) lines.remove(0);
    }

    private void renderOut() {
        if (vOut == null) return;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            b.append(lines.get(i));
            if (i < lines.size() - 1) b.append('\n');
        }
        vOut.setText(b.toString());
        toBottom();
    }

    private void toBottom() {
        vScroll.post(new Runnable() {
            public void run() { vScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void renderIn() {
        if (vIn == null) return;
        if (opening) {
            vIn.setText("...");
            vIn.setTextColor(Ui.FAINT);
            return;
        }
        if (busy) {
            vIn.setText("running...");
            vIn.setTextColor(Ui.FAINT);
            return;
        }
        vIn.setTextColor(Ui.ACCENT);
        vIn.setText((shell.root().isRoot() ? "# " : "$ ") + buffer + "_");
    }

    // ---------------------------------------------------------------- running

    public void run(final String command) {
        if (busy || opening || command == null || command.trim().length() == 0) return;
        busy = true;
        print((shell.root().isRoot() ? "# " : "$ ") + command);
        renderOut();
        renderIn();

        if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) {
            history.add(command);
        }
        historyAt = -1;

        new Thread(new Runnable() {
            public void run() {
                final String result = shell.root().exec(command);
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (result == null) print("[shell died]");
                        else if (result.length() > 0) {
                            // The sentinel echo leaves one trailing newline
                            // that would otherwise show as a blank line after
                            // every single command.
                            print(result.endsWith("\n")
                                    ? result.substring(0, result.length() - 1) : result);
                        }
                        renderOut();
                        renderIn();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean onKeyboard(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int code = e.getKeyCode();

        if (e.isCtrlPressed()) {
            switch (code) {
                case KeyEvent.KEYCODE_L:
                    lines.clear(); renderOut(); return true;
                case KeyEvent.KEYCODE_C:
                    buffer.setLength(0); renderIn(); return true;
                case KeyEvent.KEYCODE_D:
                    shell.pop(); return true;
                default:
                    return true;
            }
        }

        switch (code) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                String cmd = buffer.toString();
                buffer.setLength(0);
                renderIn();
                run(cmd);
                return true;
            }
            case KeyEvent.KEYCODE_DEL:
                if (buffer.length() > 0) buffer.setLength(buffer.length() - 1);
                renderIn();
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                buffer.setLength(0);
                renderIn();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                recall(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                recall(-1);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                vScroll.smoothScrollBy(0, -80);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                vScroll.smoothScrollBy(0, 80);
                return true;
            default:
                break;
        }

        int ch = e.getUnicodeChar(e.getMetaState());
        if (ch >= 32 && ch < 127) {
            buffer.append((char) ch);
            renderIn();
        }
        return true;
    }

    /** Walk back through what has been run. Index counts from the end so a
     *  new command being appended does not shift where you are. */
    private void recall(int direction) {
        if (history.isEmpty()) return;
        historyAt += direction;
        if (historyAt < -1) historyAt = -1;
        if (historyAt >= history.size()) historyAt = history.size() - 1;

        buffer.setLength(0);
        if (historyAt >= 0) buffer.append(history.get(history.size() - 1 - historyAt));
        renderIn();
    }

    @Override
    public boolean onGesture(int button, int kind) {
        if (button == ShellActivity.BTN_A) {
            if (kind == ShellActivity.TAP) { shell.push(new CommandsScreen(this)); return true; }
            return false;                                   // hold leaves
        }
        vScroll.smoothScrollBy(0, kind == ShellActivity.TAP ? 60 : -60);
        return true;
    }

    @Override
    public String hint() {
        if (busy) return "running...";
        if (shell.keyboardAttached()) return "type  |  A:commands  hold:back";
        return shell.twoButtons() ? "A:commands  hold:back  B:scroll"
                                  : "tap:commands  hold:back";
    }

    /** The two-button path: a short list of things worth running with no
     *  keyboard attached. */
    static class CommandsScreen extends ListScreen {
        private final TermScreen term;
        private final List<String> cmds = RootShell.builtins();

        CommandsScreen(TermScreen t) { term = t; }

        @Override
        public String title() { return "Run"; }

        @Override
        protected List<Item> items() {
            List<Item> l = list();
            for (int i = 0; i < cmds.size(); i++) {
                l.add(new Item(cmds.get(i), null, AppIcons.NONE,
                        cmds.get(i).equals("reboot") ? Ui.WARN : Ui.FG));
            }
            addBack(l);
            return l;
        }

        @Override
        protected void onPick(int index) {
            if (index >= cmds.size()) { shell.pop(); return; }
            String cmd = cmds.get(index);
            shell.pop();
            term.run(cmd);
        }
    }
}
