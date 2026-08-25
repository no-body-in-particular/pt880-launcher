package org.watchlauncher;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * What this is running on. Mostly useful when something does not work: the
 * root state and whether a keyboard is attached are the two answers most
 * questions about this watch come down to.
 */
public class AboutScreen extends Screen {

    private TextView body;

    @Override
    public String title() { return "About"; }

    @Override
    protected View build() {
        LinearLayout col = Ui.column(shell);
        TextView h = Ui.text(shell, Ui.HEAD_PX, Ui.ACCENT, true);
        h.setGravity(android.view.Gravity.LEFT);
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
    public void onShow() {
        StringBuilder b = new StringBuilder();
        b.append("Watch launcher\n\n");
        b.append("model   ").append(Build.MODEL).append("\n");
        b.append("device  ").append(Build.DEVICE).append("\n");
        b.append("android ").append(Build.VERSION.RELEASE)
         .append(" (api ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("build   ").append(Build.ID).append("\n\n");
        b.append("shell   ").append(shell.root().describe()).append("\n");
        b.append("keyboard ").append(shell.keyboardAttached() ? "attached" : "none").append("\n");
        b.append("buttons ").append(shell.twoButtons() ? "two" : "one").append("\n");
        b.append("oui db  ").append(OuiDb.get(shell).describe()).append("\n");
        body.setText(b.toString());
    }

    @Override
    public boolean onGesture(int button, int kind) {
        return false;                       // any hold on A backs out
    }

    @Override
    public String hint() { return "hold:back"; }
}
