package org.watchlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * The small glyphs drawn beside a row: the five apps on the launcher, and the
 * kind of thing each Bluetooth device claims to be.
 *
 * Drawn rather than shipped as bitmaps. At ldpi a PNG set would be a 20x20
 * asset that cannot be recoloured, and every one of these has to invert when
 * its row is selected -- the row goes blue and the glyph has to go black with
 * it. A path costs nothing and recolours for free.
 *
 * All geometry is in a 24x24 design space and scaled to whatever the row gives
 * it, so the same glyph works at 20px in a list and larger elsewhere.
 */
public class AppIcons extends View {

    public static final int NONE = -1;
    public static final int MUSIC = 0;
    public static final int BLUETOOTH = 1;
    public static final int CAMERA = 2;
    public static final int CALL = 3;
    public static final int TERMINAL = 4;
    public static final int BACK = 5;
    public static final int GEAR = 6;
    public static final int CONTACT = 7;
    public static final int KEYBOARD = 8;
    public static final int HEADSET = 9;
    public static final int SPEAKER = 10;
    public static final int PHONE = 11;
    public static final int WATCH = 12;
    public static final int DEVICE = 13;
    public static final int COMPUTER = 14;
    public static final int HOME = 15;
    public static final int HEART = 16;
    public static final int MAP = 17;

    private static final float D = 24f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    private int glyph;
    private int colour = Ui.ACCENT;

    public AppIcons(Context c, int glyph) {
        super(c);
        this.glyph = glyph;
    }

    public void setGlyph(int g) {
        if (g == glyph) return;
        glyph = g;
        invalidate();
    }

    public void setColour(int c) {
        if (c == colour) return;
        colour = c;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || glyph == NONE) return;

        canvas.save();
        canvas.scale(w / D, h / D);
        p.setColor(colour);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        switch (glyph) {
            case MUSIC:     music(canvas); break;
            case BLUETOOTH: bluetooth(canvas); break;
            case CAMERA:    camera(canvas); break;
            case CALL:      call(canvas); break;
            case TERMINAL:  terminal(canvas); break;
            case BACK:      back(canvas); break;
            case GEAR:      gear(canvas); break;
            case CONTACT:   contact(canvas); break;
            case KEYBOARD:  keyboard(canvas); break;
            case HEADSET:   headset(canvas); break;
            case SPEAKER:   speaker(canvas); break;
            case PHONE:     phone(canvas); break;
            case WATCH:     watch(canvas); break;
            case COMPUTER:  computer(canvas); break;
            case HOME:      home(canvas); break;
            case HEART:     heart(canvas); break;
            case MAP:       map(canvas); break;
            default:        device(canvas); break;
        }
        canvas.restore();
    }

    private void stroke(float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
    }

    private void fill() { p.setStyle(Paint.Style.FILL); }

    /** A beamed pair of quavers. */
    private void music(Canvas c) {
        stroke(2f);
        c.drawLine(9f, 18f, 9f, 5f, p);
        c.drawLine(19f, 16f, 19f, 3f, p);
        c.drawLine(9f, 5f, 19f, 3f, p);
        fill();
        c.drawCircle(6.5f, 18f, 3f, p);
        c.drawCircle(16.5f, 16f, 3f, p);
    }

    /** The Bluetooth rune: a vertical stem crossed by two triangles. */
    private void bluetooth(Canvas c) {
        stroke(2f);
        path.reset();
        path.moveTo(7f, 7.5f);
        path.lineTo(17f, 16.5f);
        path.lineTo(12f, 21f);
        path.lineTo(12f, 3f);
        path.lineTo(17f, 7.5f);
        path.lineTo(7f, 16.5f);
        c.drawPath(path, p);
    }

    private void camera(Canvas c) {
        stroke(2f);
        r.set(2.5f, 6.5f, 21.5f, 19.5f);
        c.drawRoundRect(r, 3f, 3f, p);
        // The lens hood, offset left the way the watch's own camera sits.
        path.reset();
        path.moveTo(8f, 6.5f);
        path.lineTo(9.5f, 3.5f);
        path.lineTo(14.5f, 3.5f);
        path.lineTo(16f, 6.5f);
        c.drawPath(path, p);
        c.drawCircle(12f, 13f, 4f, p);
    }

    /** A handset, tilted the way every dialler has drawn one since 1970. */
    private void call(Canvas c) {
        fill();
        c.save();
        c.rotate(-30f, 12f, 12f);
        r.set(6f, 2.5f, 12f, 8f);
        c.drawRoundRect(r, 2.5f, 2.5f, p);
        r.set(12f, 16f, 18f, 21.5f);
        c.drawRoundRect(r, 2.5f, 2.5f, p);
        stroke(3f);
        c.drawLine(9.5f, 7f, 14.5f, 17f, p);
        c.restore();
    }

    private void terminal(Canvas c) {
        stroke(2f);
        r.set(2.5f, 4.5f, 21.5f, 19.5f);
        c.drawRoundRect(r, 2.5f, 2.5f, p);
        // A prompt chevron and the cursor sitting after it.
        path.reset();
        path.moveTo(6.5f, 9f);
        path.lineTo(10f, 12f);
        path.lineTo(6.5f, 15f);
        c.drawPath(path, p);
        c.drawLine(12f, 15.5f, 17.5f, 15.5f, p);
    }

    private void back(Canvas c) {
        stroke(2f);
        path.reset();
        path.moveTo(13f, 5f);
        path.lineTo(6f, 12f);
        path.lineTo(13f, 19f);
        c.drawPath(path, p);
        c.drawLine(6f, 12f, 19f, 12f, p);
    }

    private void gear(Canvas c) {
        stroke(2f);
        c.drawCircle(12f, 12f, 4.5f, p);
        // Eight teeth, as spokes rather than shaped cogs -- at 20px on screen
        // a real cog outline turns to mud.
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45f, 12f, 12f);
            c.drawLine(12f, 2.5f, 12f, 6f, p);
            c.restore();
        }
    }

    private void contact(Canvas c) {
        fill();
        c.drawCircle(12f, 8f, 4f, p);
        path.reset();
        path.addArc(new RectF(4f, 13f, 20f, 27f), 180f, 180f);
        path.close();
        c.drawPath(path, p);
    }

    private void keyboard(Canvas c) {
        stroke(1.8f);
        r.set(2f, 6.5f, 22f, 18f);
        c.drawRoundRect(r, 2f, 2f, p);
        fill();
        for (int row = 0; row < 2; row++) {
            for (int i = 0; i < 5; i++) {
                float x = 5f + i * 3.5f + row * 1.5f;
                c.drawRect(x, 9f + row * 3f, x + 2f, 10.6f + row * 3f, p);
            }
        }
        c.drawRect(7f, 15f, 17f, 16.4f, p);
    }

    private void headset(Canvas c) {
        stroke(2f);
        // Headband.
        r.set(4f, 4f, 20f, 20f);
        c.drawArc(r, 200f, 140f, false, p);
        fill();
        r.set(3f, 11.5f, 7.5f, 19f);
        c.drawRoundRect(r, 2f, 2f, p);
        r.set(16.5f, 11.5f, 21f, 19f);
        c.drawRoundRect(r, 2f, 2f, p);
    }

    private void speaker(Canvas c) {
        stroke(2f);
        r.set(5.5f, 2.5f, 18.5f, 21.5f);
        c.drawRoundRect(r, 2.5f, 2.5f, p);
        c.drawCircle(12f, 14.5f, 4f, p);
        fill();
        c.drawCircle(12f, 6.5f, 1.3f, p);
    }

    private void phone(Canvas c) {
        stroke(2f);
        r.set(6.5f, 2.5f, 17.5f, 21.5f);
        c.drawRoundRect(r, 2.5f, 2.5f, p);
        fill();
        c.drawCircle(12f, 18.5f, 1.2f, p);
        c.drawRect(9.5f, 5f, 14.5f, 6f, p);
    }

    private void watch(Canvas c) {
        stroke(2f);
        r.set(6f, 6.5f, 18f, 17.5f);
        c.drawRoundRect(r, 2.5f, 2.5f, p);
        c.drawLine(9f, 6.5f, 9.5f, 2.5f, p);
        c.drawLine(15f, 6.5f, 14.5f, 2.5f, p);
        c.drawLine(9f, 17.5f, 9.5f, 21.5f, p);
        c.drawLine(15f, 17.5f, 14.5f, 21.5f, p);
    }

    private void computer(Canvas c) {
        stroke(2f);
        r.set(3f, 4.5f, 21f, 16f);
        c.drawRoundRect(r, 1.5f, 1.5f, p);
        c.drawLine(2f, 19.5f, 22f, 19.5f, p);
    }

    /** A house. Distinct from BACK, which steps one level; this one means the
     *  launcher, however many levels away that is. */
    private void home(Canvas c) {
        stroke(2f);
        path.reset();
        path.moveTo(3f, 11f);
        path.lineTo(12f, 3f);
        path.lineTo(21f, 11f);
        c.drawPath(path, p);
        path.reset();
        path.moveTo(6f, 10.5f);
        path.lineTo(6f, 20f);
        path.lineTo(18f, 20f);
        path.lineTo(18f, 10.5f);
        c.drawPath(path, p);
    }

    /** Sports: the heart is the readable half of "speed and heart rate" at
     *  20px. A running figure is the conventional glyph and turns to mud. */
    private void heart(Canvas c) {
        fill();
        path.reset();
        path.moveTo(12f, 20.5f);
        path.cubicTo(3f, 14f, 2.5f, 8.5f, 6.5f, 6f);
        path.cubicTo(9f, 4.5f, 11.5f, 6f, 12f, 7.5f);
        path.cubicTo(12.5f, 6f, 15f, 4.5f, 17.5f, 6f);
        path.cubicTo(21.5f, 8.5f, 21f, 14f, 12f, 20.5f);
        path.close();
        c.drawPath(path, p);
    }

    /** A folded map: three panels with a fold between each. */
    private void map(Canvas c) {
        stroke(2f);
        path.reset();
        path.moveTo(2.5f, 6f);
        path.lineTo(9f, 3.5f);
        path.lineTo(15f, 6.5f);
        path.lineTo(21.5f, 4f);
        path.lineTo(21.5f, 18f);
        path.lineTo(15f, 20.5f);
        path.lineTo(9f, 17.5f);
        path.lineTo(2.5f, 20f);
        path.close();
        c.drawPath(path, p);
        c.drawLine(9f, 3.5f, 9f, 17.5f, p);
        c.drawLine(15f, 6.5f, 15f, 20.5f, p);
    }

    private void device(Canvas c) {
        stroke(2f);
        r.set(4.5f, 4.5f, 19.5f, 19.5f);
        c.drawRoundRect(r, 3f, 3f, p);
        fill();
        c.drawCircle(12f, 12f, 2.2f, p);
    }
}
