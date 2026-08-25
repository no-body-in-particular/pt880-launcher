package org.watchlauncher;

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
 * The one interaction this device really has: a highlighted row you move with
 * one button and commit with the other. The launcher, every menu, the device
 * list, the contact list and the call log are all this class.
 *
 * The movement rules are the music player's, so muscle memory carries across:
 *
 *   two buttons   A tap picks, A hold backs out, B tap moves down, B hold up
 *   one button    A tap moves, A hold picks, a very long A hold backs out
 *
 * Every list also carries a "Back" row, so backing out never *requires* the
 * hold -- which matters most in one-button mode, where the only way out is a
 * hold long enough that people give up on it.
 */
public abstract class ListScreen extends Screen {

    /** A row. {@code right} is the dim trailing column -- a device type, a
     *  phone number, a toggle's current state. */
    public static class Item {
        public final String text;
        public final String right;
        public final int glyph;
        public final int colour;

        public Item(String text) { this(text, null, AppIcons.NONE, Ui.FG); }
        public Item(String text, String right) { this(text, right, AppIcons.NONE, Ui.FG); }
        public Item(String text, String right, int glyph) { this(text, right, glyph, Ui.FG); }

        public Item(String text, String right, int glyph, int colour) {
            this.text = text;
            this.right = right;
            this.glyph = glyph;
            this.colour = colour;
        }
    }

    private ScrollView scroll;
    private LinearLayout list;
    private TextView heading;

    protected int sel = 0;

    /** Rebuilt on every render, so a screen can just answer with current state
     *  rather than maintaining an adapter. Lists here are at most a few dozen
     *  rows; there is nothing to be gained by recycling views. */
    protected abstract List<Item> items();

    /** The row was committed. */
    protected abstract void onPick(int index);

    /** Show the heading above the rows. Off for the launcher, which is the
     *  whole screen and needs no label. */
    protected boolean showHeading() { return true; }

    @Override
    protected View build() {
        LinearLayout col = Ui.column(shell);

        heading = Ui.text(shell, Ui.HEAD_PX, Ui.ACCENT, true);
        heading.setGravity(Gravity.LEFT);
        heading.setPadding(0, 0, 0, 4);
        col.addView(heading, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));

        list = Ui.column(shell);
        scroll = new ScrollView(shell);
        scroll.addView(list);
        col.addView(scroll, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    @Override
    public void onShow() { render(); }

    /** Rebuild the rows from {@link #items()} and re-centre on the selection. */
    public void render() {
        if (list == null) return;
        List<Item> its = items();
        heading.setVisibility(showHeading() ? View.VISIBLE : View.GONE);
        heading.setText(title());

        if (sel >= its.size()) sel = Math.max(0, its.size() - 1);
        if (sel < 0) sel = 0;

        list.removeAllViews();
        View selected = null;
        for (int i = 0; i < its.size(); i++) {
            View row = buildRow(its.get(i), i == sel);
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i == sel) selected = row;
        }

        final View target = selected;
        if (target != null) {
            scroll.post(new Runnable() {
                public void run() {
                    int y = target.getTop() - (scroll.getHeight() / 2)
                            + (target.getHeight() / 2);
                    scroll.smoothScrollTo(0, Math.max(0, y));
                }
            });
        }
        shell.renderHint();
    }

    private View buildRow(Item it, boolean on) {
        LinearLayout row = Ui.row(shell);
        row.setPadding(5, 4, 5, 4);
        row.setBackgroundColor(on ? Ui.ACCENT : Ui.BG);

        if (it.glyph != AppIcons.NONE) {
            AppIcons icon = new AppIcons(shell, it.glyph);
            icon.setColour(on ? Ui.BG : Ui.ACCENT);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(20, 20);
            ip.rightMargin = 6;
            row.addView(icon, ip);
        }

        TextView t = Ui.text(shell, Ui.ITEM_PX, on ? Ui.BG : it.colour, on);
        t.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setText(it.text);
        row.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (it.right != null && it.right.length() > 0) {
            TextView r = Ui.text(shell, Ui.HINT_PX, on ? Ui.BG : Ui.FAINT, false);
            r.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            r.setPadding(4, 0, 0, 0);
            r.setText(it.right);
            row.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    protected void move(int delta) {
        int n = items().size();
        if (n == 0) return;
        sel = ((sel + delta) % n + n) % n;
        render();
    }

    @Override
    public boolean onGesture(int button, int kind) {
        if (button == ShellActivity.BTN_B) {
            if (kind == ShellActivity.TAP) move(1);
            else move(-1);
            return true;
        }
        if (shell.twoButtons()) {
            // B does the moving, so A can commit outright.
            if (kind == ShellActivity.TAP) { onPick(sel); return true; }
            return false;                       // hold -> activity backs out
        }
        // One button: tap steps, hold commits, a very long hold backs out.
        if (kind == ShellActivity.TAP) { move(1); return true; }
        if (kind == ShellActivity.HOLD) { onPick(sel); return true; }
        return false;
    }

    @Override
    public boolean onKeyboard(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1); return true;
            case KeyEvent.KEYCODE_PAGE_UP:
                move(-5); return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                move(5); return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                onPick(sel); return true;
            case KeyEvent.KEYCODE_ESCAPE:
                shell.pop(); return true;
            default:
                return false;
        }
    }

    /** Convenience for the many lists that end in a Back row. */
    protected static void addBack(List<Item> l) {
        l.add(new Item("Back", null, AppIcons.BACK));
    }

    protected static List<Item> list() { return new ArrayList<Item>(); }

    @Override
    public String hint() {
        return shell.twoButtons() ? "A:pick  hold:back  B:down  B hold:up"
                                  : "tap:move  hold:pick  hold2s:back";
    }
}
