package org.watchlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Colours, metrics and the three view helpers every screen builds itself from.
 *
 * The palette is the music player's, unchanged: one blue accent, white for the
 * thing you are reading, grey for the thing you are not, red only for a
 * warning. On a 240x240 screen at ldpi there is no room for a second idea.
 *
 * Sizes are in raw pixels rather than dp. Density is 120, so dp would scale
 * everything by 0.75 and every hand-tuned number here would have to be
 * un-tuned by the same amount; the screen is a fixed 240x240 and will never be
 * anything else.
 */
public final class Ui {

    private Ui() { }

    // ---- palette ----------------------------------------------------------

    /** The one accent. Selection, the clock, headings, charging. */
    public static final int ACCENT = 0xFF7FB3FF;

    /** The driving route over the map.
     *
     *  The tiles are sixteen greys, so a saturated hue is something the map
     *  itself can never produce and the line cannot be mistaken for a road.
     *  Amber rather than the accent blue, which sits at about the luminance
     *  of a main road and disappeared into one. The casing is drawn under it
     *  so the route stays legible crossing both dark parkland and white
     *  motorway. */
    public static final int ROUTE = 0xFFFF9F1C;
    public static final int ROUTE_CASING = 0xFF241300;
    public static final int FG = Color.WHITE;
    public static final int DIM = 0xFFBBBBBB;
    public static final int MUTED = 0xFF999999;
    public static final int FAINT = 0xFF777777;
    public static final int BG = Color.BLACK;
    public static final int WARN = 0xFFFF6B6B;
    public static final int OK = 0xFF7FD48A;

    // ---- metrics ----------------------------------------------------------

    /** Status bar text, and the battery glyph drawn to match it. 40x22 keeps
     *  the launcher's own 36:20 aspect. */
    public static final int STATUS_PX = 22;
    public static final int BATT_W_PX = 40, BATT_H_PX = 22;

    public static final int TITLE_PX = 20;
    public static final int ITEM_PX = 14;
    public static final int BODY_PX = 13;
    public static final int HEAD_PX = 12;
    public static final int SMALL_PX = 11;
    public static final int HINT_PX = 10;

    /** At or below this the battery readout turns red. */
    public static final int LOW_BATTERY_PCT = 15;

    // ---- helpers ----------------------------------------------------------

    public static TextView text(Context c, int px, int colour, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(colour);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    public static TextView mono(Context c, int px, int colour) {
        TextView t = text(c, px, colour, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.LEFT);
        return t;
    }

    /**
     * Vertical gap. A view rather than a margin so it can sit between two
     * children whose own layout params are already carrying a weight.
     *
     * The height is enforced in onMeasure rather than left to the layout
     * params, because a bare View does not honour WRAP_CONTENT: View.onMeasure
     * defers to getDefaultSize(), which returns the whole AT_MOST size. A
     * spacer whose params get replaced at the call site -- addView(spacer(3),
     * lp(...)) instead of addView(spacer(3)) -- therefore expands to fill the
     * rest of the column and squeezes every sibling after it to nothing. That
     * cost an evening: the status bar rendered and the entire launcher below it
     * measured to zero height.
     */
    public static View spacer(Context c, final int px) {
        View v = new View(c) {
            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                setMeasuredDimension(getDefaultSize(0, wSpec), px);
            }
        };
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px));
        return v;
    }

    /** Layout params with an optional weight; height 0 means wrap. */
    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w,
                h == 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : h);
        if (weight > 0) { p.height = 0; p.weight = weight; }
        return p;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** mm:ss, for track and call timers. */
    public static String mmss(int ms) {
        int s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
