package org.watchlauncher;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Write down why the app died.
 *
 * On Android an uncaught exception on <em>any</em> thread kills the process,
 * and this app is the home screen - so the system relaunches it at once and
 * what the wearer sees is the map blinking back to the launcher. It looks
 * like navigation, not like a crash, which is exactly how a GATT callback
 * throwing on a binder thread once got diagnosed as "six found then nothing".
 *
 * Nothing here prevents the crash. It records it, on the card, where it can
 * be read afterwards - the watch has no adb attached in the field and no
 * screen worth printing a stack trace on.
 */
public final class Crash {

    private static final String FILE = "/sdcard/Documents/crash.txt";

    /** Keep the last few, not just the newest: a crash loop is more
     *  informative than any single instance of it. */
    private static final int KEEP_BYTES = 16384;

    private Crash() { }

    /**
     * Whether the previous run ended by being killed rather than by crashing or exiting.
     *
     * A crash writes a stack. A clean exit clears a marker. Anything else - the kernel's low
     * memory killer picking this process, which on a watch with a 64 MB heap picks the
     * largest app and that is usually this one - leaves no trace at all, because a SIGKILL
     * runs no handler. So the last run is presumed killed until it says otherwise: a marker
     * is written at startup and cleared on the way out, and finding it still there means the
     * way out never happened.
     *
     * This matters because the two look identical from the wrist. The map vanishes and the
     * launcher is on screen, and "it crashed" and "it was killed" are different problems with
     * different fixes - one is a bug here, the other is memory pressure from somewhere else
     * on the device.
     *
     * Called before the marker is re-armed, so it reports the run before this one.
     */
    public static boolean wasKilled(Context ctx) {
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences("watchlauncher", Context.MODE_PRIVATE);
            boolean running = p.getBoolean(KEY_RUNNING, false);
            String screen = p.getString(KEY_SCREEN, "");
            p.edit().putBoolean(KEY_RUNNING, true).apply();
            return running && screen.length() > 0;

        } catch (Throwable t) {
            return false;
        }
    }

    /** What was on screen when it went, which is the first thing anyone asks. */
    public static String lastScreen(Context ctx) {
        try {
            return ctx.getSharedPreferences("watchlauncher", Context.MODE_PRIVATE)
                    .getString(KEY_SCREEN, "");

        } catch (Throwable t) {
            return "";
        }
    }

    /** Called as screens are pushed, so a kill can say what was open. */
    public static void noteScreen(Context ctx, String name) {
        try {
            ctx.getSharedPreferences("watchlauncher", Context.MODE_PRIVATE)
                    .edit().putString(KEY_SCREEN, name).apply();

        } catch (Throwable t) {
            //a marker that cannot be written only costs the diagnosis, not the run
        }
    }

    /** Cleared on the way out, so the next start can tell a kill from a clean exit. */
    public static void noteCleanExit(Context ctx) {
        try {
            ctx.getSharedPreferences("watchlauncher", Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_RUNNING, false).apply();

        } catch (Throwable t) {
            //ignore
        }
    }

    private static final String KEY_RUNNING = "crash.running";
    private static final String KEY_SCREEN = "crash.screen";

    public static void install(final Context ctx) {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(
                new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    write(t, e);
                } catch (Throwable ignored) {
                    // Never let the recorder be the thing that fails.
                }
                // Hand on, so the process still dies the way it would have.
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private static void write(Thread t, Throwable e) throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println();
        pw.println("---- " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()) + "  thread " + t.getName());
        e.printStackTrace(pw);
        pw.flush();

        File f = new File(FILE);
        String old = "";
        if (f.isFile() && f.length() > 0) {
            byte[] b = new byte[(int) Math.min(f.length(), KEEP_BYTES)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                in.skip(Math.max(0, f.length() - b.length));
                int n = in.read(b);
                if (n > 0) old = new String(b, 0, n);
            } finally {
                in.close();
            }
        }

        FileOutputStream os = new FileOutputStream(f, false);
        try {
            os.write((old + sw.toString()).getBytes("UTF-8"));
        } finally {
            os.close();
        }
    }

    /** Did the last run end in a crash, rather than some crash weeks ago?
     *  Ten minutes, because the home screen restarts within seconds of one. */
    public static boolean freshlyCrashed() {
        File f = new File(FILE);
        return f.isFile()
                && (System.currentTimeMillis() - f.lastModified()) < 600000L;
    }

    /** The most recent crash, or null. Shown once on the next start so a
     *  failure that only happens in the field is not silent. */
    public static String last() {
        try {
            File f = new File(FILE);
            if (!f.isFile() || f.length() == 0) return null;
            byte[] b = new byte[(int) Math.min(f.length(), KEEP_BYTES)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n;
            try { n = in.read(b); } finally { in.close(); }
            if (n <= 0) return null;
            String all = new String(b, 0, n, "UTF-8");
            int at = all.lastIndexOf("---- ");
            String one = at >= 0 ? all.substring(at) : all;
            String[] lines = one.split("\n");
            // The header, the exception, and the first frame of ours: enough
            // to say what and where on a 240px screen.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length && sb.length() < 200; i++) {
                String l = lines[i].trim();
                if (l.length() == 0) continue;
                if (i > 1 && !l.contains("watchlauncher")) continue;
                sb.append(l).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
