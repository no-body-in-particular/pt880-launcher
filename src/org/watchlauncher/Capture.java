package org.watchlauncher;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Take a photo with no screen and nobody holding the watch.
 *
 * <h3>Why this is separate from {@link CameraScreen}</h3>
 *
 * That one is a viewfinder: it owns a {@code SurfaceView}, a preview, a timer and a review step,
 * and all of it assumes somebody is looking. A capture asked for by the server or by SMS has
 * none of those. Sharing the code would mean starting a screen nobody sees.
 *
 * <h3>The preview surface that has to exist anyway</h3>
 *
 * On this API the camera will not deliver a picture without a preview running, and a preview
 * needs somewhere to go -- {@code takePicture} on a camera that was never previewing returns
 * nothing at all, silently. A {@link SurfaceTexture} that is never drawn satisfies that without
 * a window: it is a real target as far as the driver is concerned and invisible as far as the
 * user is concerned.
 *
 * <h3>What it deliberately does not do</h3>
 *
 * No shutter sound is suppressed and no indication is hidden. On a watch that can be told to
 * take a photo remotely, the difference between a camera and a covert camera is exactly whether
 * anyone can tell it fired, and that should stay on the visible side of the line.
 */
public final class Capture {

    private static final String TAG = "Capture";

    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    /** How long to let the sensor settle before firing. Long enough to expose, short enough
     *  that the LED and the sensor are not on any longer than they have to be. */
    private static final long SETTLE_MS = 1200;

    private static final long TIMEOUT_MS = 8000;

    private Capture() {
    }

    /**
     * Take one photo and return the file, or null if it could not be taken.
     *
     * Blocking, and safe to call from a worker thread only -- it waits on the driver's callback
     * and would deadlock the UI thread.
     */
    public static File once() {
        Camera camera = null;
        SurfaceTexture dummy = null;
        final File[] out = new File[1];
        final Object done = new Object();

        try {
            camera = Camera.open();
            if (camera == null) {
                Log.w(TAG, "no camera");
                return null;
            }

            dummy = new SurfaceTexture(0);
            camera.setPreviewTexture(dummy);
            camera.startPreview();
            Thread.sleep(SETTLE_MS);

            final Camera cam = camera;
            camera.takePicture(null, null, new Camera.PictureCallback() {
                public void onPictureTaken(byte[] data, Camera c) {
                    try {
                        out[0] = write(data);
                    } catch (Throwable t) {
                        Log.w(TAG, "could not write photo", t);
                    } finally {
                        synchronized (done) {
                            done.notifyAll();
                        }
                    }
                }
            });

            synchronized (done) {
                if (out[0] == null) done.wait(TIMEOUT_MS);
            }
            return out[0];

        } catch (Throwable t) {
            Log.w(TAG, "capture failed", t);
            return null;
        } finally {
            // Release before returning, always. A camera left open is not merely a leak here:
            // nothing else on the watch can open it afterwards, so one failed remote capture
            // would take the viewfinder with it until the process restarted.
            if (camera != null) {
                try { camera.stopPreview(); } catch (Throwable ignored) { }
                try { camera.release(); } catch (Throwable ignored) { }
            }
            if (dummy != null) {
                try { dummy.release(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Same place the viewfinder writes to, so remote and local photos land together. */
    private static File write(byte[] jpeg) throws Exception {
        File dir = new File("/sdcard/DCIM/Camera");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            dir = new File(Environment.getExternalStorageDirectory(), "DCIM");
            dir.mkdirs();
        }
        File f = new File(dir, "IMG_" + STAMP.format(new Date()) + ".jpg");
        FileOutputStream s = new FileOutputStream(f);
        try {
            s.write(jpeg);
        } finally {
            s.close();
        }
        Log.i(TAG, "wrote " + f);
        return f;
    }
}
