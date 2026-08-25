package org.watchlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The stock launcher's battery glyph, redrawn.
 *
 * Geometry is measured from L004Launcher's `ic_battery_bg` set at ldpi (a 36x36
 * canvas): a rounded body with the terminal nub on the *left*, and five
 * discrete fill bars that accumulate from the right-hand end. Both quirks are
 * the vendor's, and are kept so this reads as the same icon the watch shows
 * everywhere else.
 *
 * Redrawn rather than copied: the originals are vendor assets, and they are
 * near-black -- drawn for the launcher's light background, so they would be
 * invisible on this app's black one.
 */
public class BatteryIcon extends View {

    /** Design space: the vendor's 36x36 canvas, cropped to the 20 rows the
     *  glyph actually occupies. Working in its own coordinates keeps the 2px
     *  stroke off the view edge, where half of it would be clipped away. */
    private static final float DW = 36f, DH = 20f;

    /** Bar extents in design space, right-hand bar first: fill grows leftward. */
    private static final float[][] BARS = {
        {31f, 34f}, {24f, 28f}, {18f, 21f}, {12f, 15f}, {5f, 9f},
    };
    private static final float BAR_TOP = 3f, BAR_BOTTOM = 17f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private int lit = 0;
    private int colour = 0xFF999999;

    public BatteryIcon(Context c) {
        super(c);
    }

    /** @param pct 0-100, or negative when the level is not known yet. */
    public void set(int pct, int colour) {
        int n = (pct < 0) ? 0 : Math.min(BARS.length, (pct + 19) / 20);
        if (n == lit && colour == this.colour) return;
        lit = n;
        this.colour = colour;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.save();
        canvas.scale(w / DW, h / DH);

        paint.setColor(colour);

        // Terminal nub, on the left.
        paint.setStyle(Paint.Style.FILL);
        r.set(1f, 7f, 3.5f, 13f);
        canvas.drawRoundRect(r, 1f, 1f, paint);

        // Body outline. The 2px stroke straddles its centreline, so the rect
        // runs down the middle of the vendor's border: x 3..4 and 33..34.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        r.set(3.5f, 1.5f, 33.5f, 17.5f);
        canvas.drawRoundRect(r, 2.5f, 2.5f, paint);

        // Fill bars.
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < lit; i++) {
            r.set(BARS[i][0], BAR_TOP, BARS[i][1], BAR_BOTTOM);
            canvas.drawRect(r, paint);
        }

        canvas.restore();
    }
}
