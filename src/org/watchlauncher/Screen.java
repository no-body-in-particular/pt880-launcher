package org.watchlauncher;

import android.view.KeyEvent;
import android.view.View;

/**
 * One thing the watch can be showing. The activity owns the window, the status
 * bar and the key decoding; a screen owns only the middle of the display and
 * what the two buttons mean while it is up.
 *
 * Screens live on a stack. Pushing one is entering an app, popping is leaving
 * it, and the launcher is the bottom of the stack -- so backing out can never
 * strand you on the stock clock face the way closing the standalone player
 * does.
 */
public abstract class Screen {

    protected ShellActivity shell;
    private View view;

    void attach(ShellActivity a) { shell = a; }

    /** Shown as the heading of a list, and in the window title nowhere else --
     *  there is no title bar. */
    public abstract String title();

    /** Built once, the first time the screen is shown, then reused. */
    protected abstract View build();

    public final View view() {
        if (view == null) view = build();
        return view;
    }

    /** Entering the screen, including on the way back from a child. */
    public void onShow() { }

    /** Leaving it. Stop scans, previews, anything that costs battery. */
    public void onHide() { }

    /** Once a second while visible, alongside the status bar's own retick. */
    public void tick() { }

    /**
     * A watch button. Return false to let the activity apply the default,
     * which is: hold on button A leaves the screen.
     *
     * @param button {@link ShellActivity#BTN_A} or {@link ShellActivity#BTN_B}
     * @param kind   {@link ShellActivity#TAP}, {@code HOLD} or {@code XHOLD}
     */
    public abstract boolean onGesture(int button, int kind);

    /** A key from a paired Bluetooth keyboard, if one is connected. Screens
     *  that do not care leave this alone and get the arrow/enter/escape
     *  defaults the activity maps onto gestures. */
    public boolean onKeyboard(KeyEvent e) { return false; }

    /** The bottom line. Says what the buttons do right here. */
    public abstract String hint();
}
