package org.watchlauncher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Frames that had nowhere to go, kept until there is somewhere.
 *
 * The tracker measures on its own timers and the socket is not always up - a WiFi blip, a
 * reconnect backoff, a night out of coverage. Until now a measurement finishing on a dead
 * connection was logged and dropped, which is why a chart can show a pulse every three minutes
 * for an hour and then nothing for the next one: the readings were taken, they just had no
 * route.
 *
 * Nothing here is time-sensitive on the wire. Every frame this spools already carries its own
 * timestamp - JK health readings and the AP01 position frame both stamp themselves when they
 * are built - so a frame sent an hour late is still filed against the minute it was measured.
 * That is what makes replaying them worth doing rather than merely tidy.
 *
 * A plain text file, one frame to a line, oldest first. The volumes are small - a few frames
 * every three minutes at worst - and a line-oriented file survives a half-written tail, which
 * a serialised structure would not: the reader drops a malformed last line and keeps the rest.
 */
final class Spool {

    private static final String TAG = "Spool";
    private static final String FILE = "unsent.txt";

    /**
     * How many frames to keep.
     *
     * At the default cadences a disconnected day produces roughly five hundred - vitals every
     * three minutes, a position every ten. Two thousand covers a long outage with room to
     * spare, and caps the file at a few hundred kilobytes.
     *
     * When it overflows the oldest go first. A gap in yesterday's chart is a smaller loss than
     * missing the hours either side of coming back.
     */
    private static final int MAX_LINES = 2000;

    /**
     * Older than this and it is not worth sending.
     *
     * The server accepts a stamped frame whenever it arrives, so this is about usefulness
     * rather than correctness: a week-old reading arriving in a burst tells nobody anything
     * they can act on, and a watch that has been off for a month should not open its next
     * session with a thousand of them.
     */
    private static final long MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;

    private Spool() { }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** Keep a frame that could not be sent. */
    static synchronized void add(Context c, String frame) {
        if (c == null || frame == null || frame.length() == 0) return;
        if (frame.indexOf('\n') >= 0) return;         // one frame, one line
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(file(c), true), "UTF-8");
            w.write(System.currentTimeMillis() + " " + frame + "\n");
        } catch (IOException e) {
            Log.w(TAG, "could not spool a frame", e);
        } finally {
            close(w);
        }
        trim(c);
    }

    /** How many are waiting, for the log line that says so. */
    static synchronized int size(Context c) {
        return read(c).size();
    }

    /**
     * Hand every waiting frame to {@code sender}, oldest first, and drop the ones it took.
     *
     * Stops at the first failure and keeps the rest: a send that fails means the socket has
     * gone again, and the frames behind it would only fail too. What has already gone is
     * removed before returning, so a drain interrupted halfway does not resend its first half
     * on the next attempt.
     */
    static synchronized int drain(Context c, Sender sender) {
        List<String[]> rows = read(c);
        if (rows.isEmpty()) return 0;

        int sent = 0;
        for (String[] row : rows) {
            if (!sender.send(row[1])) break;
            sent++;
        }
        if (sent == 0) return 0;

        if (sent >= rows.size()) {
            file(c).delete();
        } else {
            write(c, rows.subList(sent, rows.size()));
        }
        Log.i(TAG, "sent " + sent + " frame(s) that had been waiting"
                + (sent < rows.size() ? ", " + (rows.size() - sent) + " still queued" : ""));
        return sent;
    }

    /** What a caller has to provide: true if the frame went, false if the connection is gone. */
    interface Sender {
        boolean send(String frame);
    }

    private static List<String[]> read(Context c) {
        List<String[]> out = new ArrayList<String[]>();
        File f = file(c);
        if (!f.exists()) return out;
        BufferedReader r = null;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp <= 0 || sp + 1 >= line.length()) continue;   // half-written tail
                long at;
                try {
                    at = Long.parseLong(line.substring(0, sp));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (at < cutoff) continue;
                out.add(new String[] { line.substring(0, sp), line.substring(sp + 1) });
            }
        } catch (IOException e) {
            Log.w(TAG, "could not read the spool", e);
        } finally {
            close(r);
        }
        return out;
    }

    private static void write(Context c, List<String[]> rows) {
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(file(c), false), "UTF-8");
            for (String[] row : rows) w.write(row[0] + " " + row[1] + "\n");
        } catch (IOException e) {
            Log.w(TAG, "could not rewrite the spool", e);
        } finally {
            close(w);
        }
    }

    /** Drop what is too old or too much, and only rewrite when something actually goes. */
    private static void trim(Context c) {
        List<String[]> rows = read(c);           // read() has already dropped the expired ones
        int over = rows.size() - MAX_LINES;
        if (over > 0) {
            rows = rows.subList(over, rows.size());
            Log.i(TAG, "spool full; dropped " + over + " of the oldest");
        } else if (countLines(c) == rows.size()) {
            return;                              // nothing expired either
        }
        write(c, rows);
    }

    private static int countLines(Context c) {
        BufferedReader r = null;
        int n = 0;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(file(c)), "UTF-8"));
            while (r.readLine() != null) n++;
        } catch (IOException e) {
            return -1;
        } finally {
            close(r);
        }
        return n;
    }

    private static void close(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }
}
