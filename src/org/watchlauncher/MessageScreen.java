package org.watchlauncher;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One message, in full.
 *
 * The list can only show the first line of a text on a screen this size, and a verification code
 * or an address is exactly the part that gets cut off. This scrolls.
 */
public class MessageScreen extends Screen {

    private final String from;
    private final String when;
    private final String text;
    private TextView body;

    public MessageScreen(String from, String when, String text) {
        this.from = from;
        this.when = when;
        this.text = text;
    }

    @Override
    public String title() { return from; }

    @Override
    public boolean onGesture(int button, int kind) {
        return false;                       // nothing to do here; any hold backs out
    }

    @Override
    public String hint() { return "hold:back"; }

    @Override
    protected View build() {
        LinearLayout col = Ui.column(shell);

        TextView h = Ui.text(shell, Ui.HEAD_PX, Ui.ACCENT, true);
        h.setGravity(Gravity.LEFT);
        h.setText(from);
        h.setPadding(0, 0, 0, 2);
        col.addView(h, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));

        // Only when the provider's date was believable - see MessagesScreen, where most of them
        // are not - because a wrong date printed confidently is worse than no date.
        if (when != null) {
            TextView d = Ui.text(shell, Ui.SMALL_PX, Ui.DIM, false);
            d.setGravity(Gravity.LEFT);
            d.setText(when);
            d.setPadding(0, 0, 0, 4);
            col.addView(d, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0));
        }

        body = Ui.text(shell, Ui.SMALL_PX, Ui.FG, false);
        body.setGravity(Gravity.LEFT);
        body.setText(text);
        ScrollView s = new ScrollView(shell);
        s.addView(body);
        col.addView(s, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }
}
