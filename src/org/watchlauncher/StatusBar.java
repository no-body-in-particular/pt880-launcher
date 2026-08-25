package org.watchlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;

/**
 * The one-line bar across the top of every screen: clock left, battery right.
 *
 *     10:42                          84% [|||]
 *
 * It belongs to the activity, not to any screen, so switching from the
 * launcher into the camera or a call never takes it away. That was the whole
 * point of the request -- the time and the charge are what you actually look
 * at a watch for, and an app that hides them is an app you have to leave.
 */
public class StatusBar {

    private final Context ctx;
    private final LinearLayout view;
    private final TextView vClock, vBatt;
    private final BatteryIcon vIcon;

    /** Honours the system 12/24h setting; needs a Context, hence not static. */
    private final DateFormat clockFmt;

    private int pct = -1;
    private boolean charging = false;
    private boolean registered = false;

    public StatusBar(Context c) {
        ctx = c;
        clockFmt = android.text.format.DateFormat.getTimeFormat(c);

        // Blue, the same accent selection and headings use: the clock reads as
        // a label, not as a reading you are meant to act on.
        vClock = Ui.text(c, Ui.STATUS_PX, Ui.ACCENT, false);
        vClock.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        vBatt = Ui.text(c, Ui.STATUS_PX, Ui.MUTED, false);
        vBatt.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        vIcon = new BatteryIcon(c);

        view = Ui.row(c);
        // The clock takes the slack, so it sits hard left and the battery
        // stays hard right whatever the time string measures.
        view.addView(vClock, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // Percentage then glyph, the order the stock head bar uses.
        view.addView(vBatt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams ip =
                new LinearLayout.LayoutParams(Ui.BATT_W_PX, Ui.BATT_H_PX);
        ip.leftMargin = 4;
        view.addView(vIcon, ip);
    }

    public LinearLayout view() { return view; }

    public int percent() { return pct; }
    public boolean charging() { return charging; }

    /** Sticky ACTION_BATTERY_CHANGED rather than BatteryManager's
     *  getIntProperty(), which only landed in API 21; this watch is 19.
     *  Registering means the level is pushed on change instead of polled, and
     *  it needs no permission. */
    public void start() {
        if (registered) return;
        read(ctx.registerReceiver(rx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        registered = true;
    }

    public void stop() {
        if (!registered) return;
        try { ctx.unregisterReceiver(rx); } catch (Exception e) { /* not registered */ }
        registered = false;
    }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { read(i); }
    };

    private void read(Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        pct = (level >= 0 && scale > 0) ? (level * 100) / scale : -1;
        charging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        render();
    }

    /** Called from the activity's one-second tick and from battery broadcasts. */
    public void render() {
        vClock.setText(clockFmt.format(new Date()));

        if (pct < 0) {
            vBatt.setText("");
            vIcon.set(-1, Ui.MUTED);
            return;
        }
        // Charging is carried by colour rather than a bolt: the vendor icon
        // set has no bolt, and this build's font is missing one too.
        int c = charging ? Ui.ACCENT : (pct <= Ui.LOW_BATTERY_PCT ? Ui.WARN : Ui.MUTED);
        vBatt.setText(pct + "%");
        vBatt.setTextColor(c);
        vIcon.set(pct, c);
    }
}
