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
    /**
     * One line, for the screen.
     *
     * {@link #last()} trims a stack to about two hundred characters, which is the right size
     * for a report and four times too much for a 240 pixel label - it arrives as a wall of
     * package names with the useful part somewhere in the middle. What a wearer needs is
     * that something broke and roughly where, in a glance: "crash: RuntimeException
     * MapScreen.java:812".
     *
     * The exception's simple name and the first frame that belongs to us. The platform
     * frames above it are never the answer - Handler.&lt;init&gt; is where it threw, not
     * where it went wrong.
     */
    public static String summary() {
        String text = last();

        if (text == null) {
            return null;
        }

        String kind = null;
        String where = null;

        for (String raw : text.split("\n")) {
            String l = raw.trim();

            if (kind == null && l.indexOf("Exception") > 0 && l.indexOf(" at ") != 0) {
                int colon = l.indexOf(':');
                String full = colon > 0 ? l.substring(0, colon) : l;
                int dot = full.lastIndexOf('.');
                kind = dot >= 0 ? full.substring(dot + 1) : full;
            }

            if (where == null && l.contains("watchlauncher") && l.contains("(")) {
                int open = l.lastIndexOf('(');
                int close = l.lastIndexOf(')');

                if (open >= 0 && close > open) {
                    where = l.substring(open + 1, close);
                }
            }
        }

        if (kind == null && where == null) {
            return null;
        }

        return "crash: " + (kind == null ? "?" : kind) + (where == null ? "" : "  " + where);
    }

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
