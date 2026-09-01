package org.watchlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Paint;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.io.File;

/**
 * A day of rain, played on the watch.
 *
 * The server draws the country and Buienradar's next twenty-four hours over
 * it and sends one animated GIF; this plays it. Nothing here knows anything
 * about weather -- it is a picture, and the whole of the work is deciding when
 * to advance it and how to fit a 200x231 image onto a 240 wide screen with a
 * status bar on top and a hint underneath.
 *
 * <pre>
 *  10:42                      84% [|||]
 *  ---------------------------------
 *
 *        [ the animation, scaled
 *          to fit, centred      ]
 *
 *   A:play   B:+1h   hold B:-1h
 * </pre>
 *
 * Frames are an hour each and the GIF holds them at a second apiece, so a
 * whole day runs in twenty-four seconds. Stepping moves by exactly one frame
 * because it moves the clock by exactly one frame's worth.
 *
 * <p>Drawn with {@link Movie}, which is the only GIF decoder API 19 has and
 * needs a software canvas -- which this app is, because
 * {@code android:hardwareAccelerated="false"} is set in the manifest for
 * reasons that predate this screen.
 */
public class RainScreen extends Screen {

    /** How long each frame is held in the file the server builds. Stepping and
     *  playing both move the clock by this, so they agree with each other and
     *  with the picture. */
    private static final int FRAME_MS = 1000;

    private final Handler ui = new Handler();
    private RainView view;

    private Movie movie;
    private int posMs = 0;
    private boolean playing = true;
    private boolean loading = false;
    private String problem = "";

    @Override
    public String title() { return "Rain"; }

    @Override
    protected View build() {
        view = new RainView(shell);
        LinearLayout col = Ui.column(shell);
        col.addView(view, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    @Override
    public void onShow() {
        load(false);
        if (movie != null && playing) ui.postDelayed(step, FRAME_MS);
    }

    @Override
    public void onHide() {
        ui.removeCallbacks(step);
    }

    /**
     * Read what is on the card, and fetch a new one if it has had its hour.
     *
     * Both on a thread: the read is a couple of hundred kilobytes off a slow
     * card and the fetch is a network round trip, and neither belongs on the
     * hand that is trying to draw.
     */
    private void load(final boolean force) {
        if (loading) return;
        loading = true;
        new Thread(new Runnable() {
            public void run() {
                final File f = Rain.fetch(shell, force);
                Movie m = null;
                if (f != null) {
                    try {
                        m = Movie.decodeFile(f.getAbsolutePath());
                    } catch (Throwable t) {
                        // OutOfMemory included: this is a 2013 heap and a GIF
                        // decodes to full frames. Better a message than a
                        // process the launcher does not come back from.
                        Log.w("watchrain", "cannot decode " + f + ": " + t);
                    }
                }
                final Movie got = m;
                ui.post(new Runnable() {
                    public void run() {
                        loading = false;
                        if (got != null && got.duration() > 0) {
                            movie = got;
                            problem = "";
                            if (playing) {
                                ui.removeCallbacks(step);
                                ui.postDelayed(step, FRAME_MS);
                            }
                        } else {
                            problem = MapTiles.of(shell).online()
                                    ? "no forecast" : "no network";
                        }
                        if (view != null) view.invalidate();
                        shell.renderHint();
                    }
                });
            }
        }).start();
    }

    private final Runnable step = new Runnable() {
        public void run() {
            if (!playing || movie == null) return;
            advance(1);
            ui.postDelayed(this, FRAME_MS);
        }
    };

    /** Move by whole frames, wrapping at both ends. */
    private void advance(int frames) {
        if (movie == null) return;
        int len = movie.duration();
        if (len <= 0) return;
        posMs += frames * FRAME_MS;
        // Java's % keeps the sign of the left operand, so stepping back from
        // the first frame would land on a negative time and Movie would show
        // the first frame for ever.
        posMs = ((posMs % len) + len) % len;
        view.invalidate();
    }

    @Override
    public boolean onGesture(int button, int kind) {
        if (button == ShellActivity.BTN_A && kind == ShellActivity.TAP) {
            if (movie == null) {
                load(true);
                return true;
            }
            playing = !playing;
            ui.removeCallbacks(step);
            if (playing) ui.postDelayed(step, FRAME_MS);
            shell.renderHint();
            return true;
        }
        if (button == ShellActivity.BTN_B && movie != null) {
            // Stepping stops the animation: the point of stepping is to hold
            // an hour still and look at it.
            playing = false;
            ui.removeCallbacks(step);
            // Forward on a tap, back on a hold. A day is far enough round that
            // going only forwards means twenty-three steps to see the hour you
            // have just passed.
            advance(kind == ShellActivity.TAP ? 1 : -1);
            shell.renderHint();
            return true;
        }
        // Hold on A is left to the activity, which is what leaves the screen.
        return button != ShellActivity.BTN_A || kind == ShellActivity.TAP;
    }

    @Override
    public String hint() {
        if (movie == null) return loading ? "fetching..." : "A:retry   hold:back";
        if (playing) return "A:stop   hold A:back";
        return "A:play   B:+1h   hold B:-1h";
    }

    private class RainView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RainView(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (movie == null) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setColor(Ui.MUTED);
                paint.setTextSize(12);
                canvas.drawText(loading ? "fetching rain..." : "no forecast",
                        w / 2f, h / 2f - 4, paint);
                if (!loading && problem.length() > 0) {
                    paint.setTextSize(9);
                    paint.setColor(Ui.FAINT);
                    canvas.drawText(problem, w / 2f, h / 2f + 12, paint);
                }
                return;
            }

            movie.setTime(posMs);

            int mw = movie.width(), mh = movie.height();
            if (mw <= 0 || mh <= 0) return;
            // Fitted rather than filled: the country is taller than this screen
            // and cropping it would cut off whichever end the rain was over.
            float s = Math.min(w / (float) mw, h / (float) mh);

            canvas.save();
            canvas.translate((w - mw * s) / 2f, (h - mh * s) / 2f);
            canvas.scale(s, s);
            movie.draw(canvas, 0, 0);
            canvas.restore();
        }
    }
}
