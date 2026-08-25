package org.watchlauncher;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.os.Environment;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The camera above the screen, finally reachable.
 *
 * Minimal on purpose: a viewfinder, a shutter and a self-timer. The timer is
 * not a luxury here -- the shutter is a hardware button on the side of the
 * unit you are pointing, so pressing it moves the shot. Three seconds is
 * enough to stop the wobble.
 *
 *   A tap    shutter
 *   A hold   menu
 *   B tap    cycle the self-timer   off / 3s / 10s
 *   B hold   show the last photo
 *
 * Orientation is a setting rather than a guess. Which way up this sensor is
 * mounted is a per-unit fact nobody documents, so the menu rotates the
 * viewfinder and the saved file together and remembers the answer.
 */
public class CameraScreen extends Screen implements SurfaceHolder.Callback {

    private static final String DIR = "/sdcard/DCIM/Camera";
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private static final int[] TIMERS = {0, 3, 10};

    private FrameLayout frame;
    private SurfaceView surface;
    private SurfaceHolder holder;
    private TextView caption;
    private ImageView review;

    private Camera camera;
    private boolean previewing = false;
    private String problem = null;

    private int rotation = 90;
    private int timerIndex = 0;
    private int countdown = 0;
    private int sizeIndex = -1;
    private String lastSaved = null;

    private final List<Camera.Size> pictureSizes = new ArrayList<Camera.Size>();

    @Override
    public String title() { return "Camera"; }

    @Override
    protected View build() {
        SharedPreferences p = shell.prefs();
        rotation = p.getInt("camRotation", 90);
        timerIndex = p.getInt("camTimer", 0);
        sizeIndex = p.getInt("camSize", -1);

        frame = new FrameLayout(shell);
        frame.setBackgroundColor(Ui.BG);

        surface = new SurfaceView(shell);
        holder = surface.getHolder();
        holder.addCallback(this);
        // SURFACE_TYPE_PUSH_BUFFERS is deprecated and unnecessary from API 11;
        // the default works and the deprecated constant is a no-op here.
        frame.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        review = new ImageView(shell);
        review.setVisibility(View.GONE);
        review.setScaleType(ImageView.ScaleType.FIT_CENTER);
        review.setBackgroundColor(Ui.BG);
        frame.addView(review, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        caption = Ui.text(shell, Ui.SMALL_PX, Ui.ACCENT, true);
        caption.setGravity(Gravity.CENTER);
        caption.setBackgroundColor(0x99000000);
        caption.setPadding(2, 1, 2, 1);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        frame.addView(caption, cp);

        LinearLayout col = Ui.column(shell);
        col.addView(frame, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return col;
    }

    // ---------------------------------------------------------------- camera

    @Override
    public void onShow() {
        hideReview();
        open();
        caption();
        // configure() runs before the frame has been measured, so the first
        // letterbox is computed from a guess. Redo it once the real size is
        // known; the resulting layout pass restarts the preview by itself.
        frame.post(new Runnable() {
            public void run() {
                if (camera == null) return;
                try {
                    applyPreviewShape(camera.getParameters().getPreviewSize());
                } catch (Exception e) { /* keep the guess */ }
            }
        });
    }

    @Override
    public void onHide() {
        countdown = 0;
        close();
    }

    private void open() {
        if (camera != null) return;
        try {
            camera = Camera.open();
            if (camera == null) { problem = "No camera"; caption(); return; }
            configure();
            problem = null;
            if (holder != null && holder.getSurface() != null) startPreview();
        } catch (Exception e) {
            camera = null;
            // Almost always the vendor tracker app holding the device open for
            // its remote-capture command; say so rather than "unknown error".
            problem = "Camera busy";
        }
        caption();
    }

    private void configure() {
        Camera.Parameters p = camera.getParameters();

        pictureSizes.clear();
        List<Camera.Size> ps = p.getSupportedPictureSizes();
        if (ps != null) pictureSizes.addAll(ps);
        if (sizeIndex < 0 || sizeIndex >= pictureSizes.size()) {
            sizeIndex = biggest(pictureSizes);
        }
        if (sizeIndex >= 0) {
            Camera.Size s = pictureSizes.get(sizeIndex);
            p.setPictureSize(s.width, s.height);
        }

        Camera.Size pv = bestPreview(p.getSupportedPreviewSizes());
        if (pv != null) p.setPreviewSize(pv.width, pv.height);

        List<String> focus = p.getSupportedFocusModes();
        if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        }

        p.setJpegQuality(90);
        try { camera.setParameters(p); } catch (Exception e) { /* keep defaults */ }

        camera.setDisplayOrientation(rotation);
        applyPreviewShape(pv);
    }

    /** Letterbox the surface to the preview's aspect ratio. Stretching a 4:3
     *  sensor across a square screen makes every face wrong, and the whole
     *  point of a viewfinder is that it shows what will be saved. */
    private void applyPreviewShape(Camera.Size pv) {
        if (pv == null || frame == null) return;
        int w = pv.width, h = pv.height;
        if (rotation == 90 || rotation == 270) { int t = w; w = h; h = t; }

        final int fw = frame.getWidth() > 0 ? frame.getWidth() : 228;
        final int fh = frame.getHeight() > 0 ? frame.getHeight() : 190;
        float scale = Math.min(fw / (float) w, fh / (float) h);
        int sw = Math.max(1, (int) (w * scale));
        int sh = Math.max(1, (int) (h * scale));

        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(sw, sh, Gravity.CENTER);
        surface.setLayoutParams(lp);
    }

    private static int biggest(List<Camera.Size> sizes) {
        int best = -1;
        long area = -1;
        for (int i = 0; i < sizes.size(); i++) {
            long a = (long) sizes.get(i).width * sizes.get(i).height;
            if (a > area) { area = a; best = i; }
        }
        return best;
    }

    /** Smallest preview that still covers the 240px screen: anything larger is
     *  scaled down for display anyway and costs frame rate this SoC does not
     *  have to spare. */
    private static Camera.Size bestPreview(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = null;
        for (int i = 0; i < sizes.size(); i++) {
            Camera.Size s = sizes.get(i);
            if (Math.min(s.width, s.height) < 240) continue;
            if (best == null || (long) s.width * s.height < (long) best.width * best.height) {
                best = s;
            }
        }
        if (best != null) return best;
        // Nothing reaches 240: take the largest there is.
        Camera.Size big = sizes.get(0);
        for (int i = 1; i < sizes.size(); i++) {
            Camera.Size s = sizes.get(i);
            if ((long) s.width * s.height > (long) big.width * big.height) big = s;
        }
        return big;
    }

    private void startPreview() {
        if (camera == null || previewing) return;
        try {
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            previewing = true;
        } catch (Exception e) {
            problem = "Preview failed";
        }
    }

    private void close() {
        if (camera == null) return;
        try {
            if (previewing) camera.stopPreview();
            camera.release();
        } catch (Exception e) { /* releasing anyway */ }
        camera = null;
        previewing = false;
    }

    public void surfaceCreated(SurfaceHolder h) { startPreview(); }

    public void surfaceChanged(SurfaceHolder h, int fmt, int w, int height) {
        if (camera == null) { open(); return; }
        try {
            if (previewing) { camera.stopPreview(); previewing = false; }
        } catch (Exception e) { /* ignore */ }
        startPreview();
    }

    public void surfaceDestroyed(SurfaceHolder h) {
        previewing = false;
    }

    // ---------------------------------------------------------------- shutter

    @Override
    public void tick() {
        if (countdown > 0) {
            countdown--;
            if (countdown == 0) capture();
            caption();
        }
    }

    private void shutter() {
        if (camera == null) { open(); return; }
        int secs = TIMERS[timerIndex];
        if (secs > 0) {
            countdown = secs;
            caption();
        } else {
            capture();
        }
    }

    private void capture() {
        if (camera == null) return;
        caption.setText("...");
        try {
            camera.takePicture(null, null, jpeg);
            previewing = false;             // takePicture stops the preview
        } catch (Exception e) {
            problem = "Capture failed";
            caption();
        }
    }

    private final Camera.PictureCallback jpeg = new Camera.PictureCallback() {
        public void onPictureTaken(byte[] data, Camera c) {
            String saved = write(data);
            lastSaved = saved;
            problem = (saved == null) ? "Save failed" : null;
            if (saved != null) {
                shell.toast(new File(saved).getName());
            }
            // The preview is dead after a capture and has to be restarted, or
            // the viewfinder freezes on the shot just taken.
            startPreview();
            caption();
        }
    };

    private String write(byte[] data) {
        try {
            File dir = new File(DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                dir = new File(Environment.getExternalStorageDirectory(), "DCIM");
                dir.mkdirs();
            }
            File out = new File(dir, "IMG_" + STAMP.format(new Date()) + ".jpg");
            FileOutputStream f = new FileOutputStream(out);
            try {
                f.write(data);
                f.flush();
            } finally {
                f.close();
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- review

    private void showReview() {
        String path = lastSaved != null ? lastSaved : newestPhoto();
        if (path == null) { shell.toast("No photos yet"); return; }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            // The full JPEG is several megapixels and the view is 240px wide;
            // decoding it whole is how this runs out of heap.
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / sample > 480) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeFile(path, o);
            if (bm == null) { shell.toast("Cannot read photo"); return; }
            review.setImageBitmap(bm);
            review.setVisibility(View.VISIBLE);
            caption.setText(new File(path).getName());
        } catch (Exception e) {
            shell.toast("Cannot read photo");
        }
    }

    private void hideReview() {
        if (review == null) return;
        if (review.getVisibility() == View.VISIBLE) {
            review.setVisibility(View.GONE);
            review.setImageBitmap(null);
        }
    }

    private boolean reviewing() {
        return review != null && review.getVisibility() == View.VISIBLE;
    }

    private String newestPhoto() {
        File dir = new File(DIR);
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        File best = null;
        for (int i = 0; i < kids.length; i++) {
            if (!kids[i].getName().toLowerCase(Locale.US).endsWith(".jpg")) continue;
            if (best == null || kids[i].lastModified() > best.lastModified()) best = kids[i];
        }
        return best == null ? null : best.getAbsolutePath();
    }

    // ---------------------------------------------------------------- ui

    private void caption() {
        if (caption == null) return;
        if (problem != null) { caption.setText(problem); return; }
        if (countdown > 0) { caption.setText(Integer.toString(countdown)); return; }
        if (reviewing()) return;
        int secs = TIMERS[timerIndex];
        caption.setText(secs == 0 ? "ready" : ("timer " + secs + "s"));
    }

    @Override
    public boolean onGesture(int button, int kind) {
        if (reviewing()) {                  // any press leaves the review
            hideReview();
            caption();
            return true;
        }
        if (button == ShellActivity.BTN_A) {
            if (kind == ShellActivity.TAP) { shutter(); return true; }
            shell.push(new CameraMenuScreen(this));
            return true;
        }
        if (kind == ShellActivity.TAP) {
            timerIndex = (timerIndex + 1) % TIMERS.length;
            shell.prefs().edit().putInt("camTimer", timerIndex).commit();
            caption();
        } else {
            showReview();
        }
        return true;
    }

    @Override
    public String hint() {
        if (reviewing()) return "any press: back to viewfinder";
        return shell.twoButtons() ? "A:shoot  hold:menu  B:timer  B hold:last"
                                  : "tap:shoot  hold:menu";
    }

    // ---------------------------------------------------------------- menu

    void setRotation(int deg) {
        rotation = ((deg % 360) + 360) % 360;
        shell.prefs().edit().putInt("camRotation", rotation).commit();
        close();
        open();
    }

    int rotation() { return rotation; }

    void cycleSize() {
        if (pictureSizes.isEmpty()) return;
        sizeIndex = (sizeIndex + 1) % pictureSizes.size();
        shell.prefs().edit().putInt("camSize", sizeIndex).commit();
        close();
        open();
    }

    String sizeLabel() {
        if (sizeIndex < 0 || sizeIndex >= pictureSizes.size()) return "auto";
        Camera.Size s = pictureSizes.get(sizeIndex);
        return s.width + "x" + s.height;
    }

    void cycleTimer() {
        timerIndex = (timerIndex + 1) % TIMERS.length;
        shell.prefs().edit().putInt("camTimer", timerIndex).commit();
    }

    String timerLabel() {
        int s = TIMERS[timerIndex];
        return s == 0 ? "off" : (s + "s");
    }

    void reviewLast() { showReview(); }

    static class CameraMenuScreen extends ListScreen {
        private final CameraScreen cam;

        CameraMenuScreen(CameraScreen c) { cam = c; }

        @Override
        public String title() { return "Camera"; }

        @Override
        protected List<Item> items() {
            List<Item> l = list();
            l.add(new Item("Rotate", cam.rotation() + "°", AppIcons.CAMERA));
            l.add(new Item("Size", cam.sizeLabel()));
            l.add(new Item("Self-timer", cam.timerLabel()));
            l.add(new Item("Last photo"));
            addBack(l);
            l.add(new Item("Exit camera", null, AppIcons.HOME));
            return l;
        }

        @Override
        protected void onPick(int index) {
            switch (index) {
                case 0: cam.setRotation(cam.rotation() + 90); render(); break;
                case 1: cam.cycleSize(); render(); break;
                case 2: cam.cycleTimer(); render(); break;
                case 3: shell.pop(); cam.reviewLast(); break;
                case 4: shell.pop(); break;              // Back, to the viewfinder
                default: shell.popToRoot(); break;       // out to the launcher
            }
        }
    }
}
