package org.watchlauncher;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sends the nightly sleep summary to the tracker server, over the tracker's
 * own protocol.
 *
 * The firmware measures sleep and files it in {@code SLEEP_STATUS}, and then
 * does nothing with it: the live protocol, {@code protocol_beehome}, has no
 * sleep opcode at all. So the numbers sit on the watch until it forgets them.
 *
 * <h3>How it goes up</h3>
 *
 * The same way every other reading does. A short TCP session on the tracker's
 * own host and port, taken from the vendor's config by {@link TrackerConfig}:
 *
 * <pre>
 * -&gt; IWAP00,&lt;imei&gt;#                        identify
 * -&gt; IWAPJK,2026-08-24 08:00:00,5,675#     deep minutes
 * &lt;- IWBPJK,5#                             ack
 * </pre>
 *
 * {@code IWAPJK} is the frame the watch already uses for heart rate, blood
 * pressure, temperature and blood oxygen -- types 1 to 4. Sleep is types 5 to
 * 7 on the same frame, which is why the server side of this is three cases in
 * an existing switch rather than a new endpoint.
 *
 * Timestamps are UTC: the server parses them with {@code timegm}.
 *
 * <h3>Sending to a server that has not been taught about it yet</h3>
 *
 * Safe. {@code thinkrace_process_stat} ends in {@code default: break;}, so an
 * unrecognised type is ignored and still acked. Nothing breaks while the
 * server-side patch is unapplied -- the readings simply are not recorded yet.
 *
 * <h3>What the numbers actually are</h3>
 *
 * Worth knowing before reading a graph of them:
 *
 * <ul>
 *   <li>{@code DEEP_SLEEP} runs to 675 minutes, which is eleven hours. That is
 *       not deep sleep; the firmware appears to count nearly all sleep as
 *       deep. {@code LIGHT_SLEEP} was 15 once and 0 otherwise.
 *   <li>{@code SCORE} is 98 on every night that has data and 0 on every night
 *       that does not, so it is a flag rather than a score.
 *   <li>A row of all zeroes means the night was not recorded. That is not a
 *       night of no sleep, and it is skipped rather than sent as zero.
 *   <li>Dates repeat -- 20260822 appears twice, once empty -- so the highest
 *       row id for a date wins.
 * </ul>
 */
public class SleepUpload {

    /** Reading types on the JK frame. 1-4 are the vendor's.
     *
     *  5-7 are what the firmware measured. 8-12 are what we measured, from
     *  the accelerometer log, and are the ones worth reading: the firmware's
     *  "deep sleep" reaches eleven hours and its score is a flag.
     *
     *  Past 9 these need the server reading the type field two characters
     *  wide instead of one -- see the patch. */
    public static final int TYPE_DEEP = 5;
    public static final int TYPE_LIGHT = 6;
    public static final int TYPE_SCORE = 7;

    public static final int TYPE_TST = 8;          // total sleep time, minutes
    public static final int TYPE_SPT = 9;          // sleep period, minutes
    public static final int TYPE_WASO = 10;        // wake after onset, minutes
    public static final int TYPE_EFFICIENCY = 11;  // per cent
    public static final int TYPE_WAKEUPS = 12;     // count

    /** Asleep right now, 1 or 0. Sent on every change and every half hour
     *  either side of it, so the graph draws a square wave rather than two
     *  lonely points a night apart. */
    public static final int TYPE_SLEEPING = 13;

    /** Minutes slept so far today, counting anything that ended today: last
     *  night plus any naps since. */
    public static final int TYPE_DAY_TOTAL = 14;

    private static final String LAST_SENT = "sleepLastSent";

    private static final int CONNECT_MS = 10000;
    private static final int READ_MS = 10000;

    /** The hour, UTC, a night's summary is stamped at: near enough to waking
     *  that it lands on the right day in a graph. */
    private static final int STAMP_HOUR_UTC = 8;

    private static final SimpleDateFormat FRAME =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat DAY =
            new SimpleDateFormat("yyyyMMdd", Locale.US);
    static {
        FRAME.setTimeZone(TimeZone.getTimeZone("UTC"));
        DAY.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /** One night's summary. */
    public static class Night {
        public String date;          // yyyyMMdd
        public int deep, light, score;

        boolean empty() { return deep == 0 && light == 0 && score == 0; }
    }

    private String problem = null;
    private int sent = 0;

    public String problem() { return problem; }
    public int sent() { return sent; }

    /**
     * Nights in {@code SLEEP_STATUS} newer than the last one sent, oldest
     * first so a run of them arrives in order.
     */
    public static List<Night> unsent(SQLiteDatabase h, String lastSent) {
        List<Night> out = new ArrayList<Night>();
        Cursor c = null;
        try {
            // Highest row id per date: a repeated date is the firmware writing
            // the night twice, and the later row is the one with the data.
            c = h.rawQuery(
                    "SELECT DATE, DEEP_SLEEP, LIGHT_SLEEP, SCORE FROM SLEEP_STATUS"
                  + " WHERE _id IN (SELECT MAX(_id) FROM SLEEP_STATUS GROUP BY DATE)"
                  + " ORDER BY DATE ASC", null);
            if (c == null) return out;
            while (c.moveToNext()) {
                Night n = new Night();
                n.date = c.getString(0);
                n.deep = c.getInt(1);
                n.light = c.getInt(2);
                n.score = c.getInt(3);
                if (n.date == null || n.date.length() != 8) continue;
                if (n.empty()) continue;                 // not recorded, not zero
                if (lastSent != null && n.date.compareTo(lastSent) <= 0) continue;
                out.add(n);
            }
        } catch (Exception e) {
            /* no such table on a firmware that does not track sleep */
        } finally {
            try { if (c != null) c.close(); } catch (Exception e) { /* ignore */ }
        }
        return out;
    }

    /** yyyyMMdd to the UTC millis the readings get stamped with. */
    static long stampFor(String yyyymmdd) {
        try {
            Date d = DAY.parse(yyyymmdd);
            return d.getTime() + STAMP_HOUR_UTC * 3600000L;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Connect, identify, send every night, disconnect. Blocking; never on the
     * UI thread.
     *
     * @return how many readings the server acknowledged.
     */
    public int send(TrackerConfig cfg, List<Night> nights) {
        problem = null;
        sent = 0;
        if (nights == null || nights.isEmpty()) return 0;
        if (!cfg.usable()) { problem = "no imei"; return 0; }

        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(cfg.host(), cfg.port()), CONNECT_MS);
            s.setSoTimeout(READ_MS);

            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));

            // Identify first. Everything after this is filed against this imei.
            //
            // No comma after the opcode. The server splits on ',' starting six
            // characters in and reads the imei from field 0, so a comma there
            // makes field 0 empty, pad_imei() turns that into
            // 0000000000000000, and every reading is filed against a device
            // that does not exist. The position frames have no comma either.
            write(out, "IWAP00" + cfg.imei() + "#");

            for (int i = 0; i < nights.size(); i++) {
                Night n = nights.get(i);
                long at = stampFor(n.date);
                if (at == 0) continue;
                String when = FRAME.format(new Date(at));

                sent += one(out, in, when, TYPE_DEEP, n.deep);
                sent += one(out, in, when, TYPE_LIGHT, n.light);
                sent += one(out, in, when, TYPE_SCORE, n.score);
            }
            return sent;
        } catch (Exception e) {
            problem = "cannot reach " + cfg.host() + ":" + cfg.port();
            return sent;
        } finally {
            try { if (s != null) s.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    /** One reading, and its ack. Returns 1 if the server answered. */
    private int one(OutputStream out, BufferedReader in,
                    String when, int type, int value) throws Exception {
        write(out, "IWAPJK," + when + "," + type + "," + value + "#");
        // The server replies IWBPJK,<type># to each. Reading it keeps the
        // exchange in step and proves the frame arrived; an unrecognised type
        // is acked too, which is what makes sending ahead of the patch safe.
        String reply = read(in);
        return (reply != null && reply.indexOf("IWBPJK") >= 0) ? 1 : 0;
    }

    private static void write(OutputStream out, String frame) throws Exception {
        out.write(frame.getBytes("US-ASCII"));
        out.flush();
    }

    /** Frames are terminated by '#', not by a newline. */
    private static String read(BufferedReader in) {
        try {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 128; i++) {
                int c = in.read();
                if (c < 0) break;
                b.append((char) c);
                if (c == '#') break;
            }
            return b.length() == 0 ? null : b.toString();
        } catch (Exception e) {
            return null;                         // timed out waiting for an ack
        }
    }

    /**
     * Send one night's computed sleep metrics, stamped at the moment the
     * sleeper actually woke rather than a nominal hour -- the scorer knows it,
     * so there is no reason to guess.
     *
     * @return how many readings the server acknowledged
     */
    public int sendScore(TrackerConfig cfg, SleepScore.Result r) {
        problem = null;
        sent = 0;
        if (r == null || !r.valid) { problem = "nothing scored"; return 0; }
        if (!cfg.usable()) { problem = "no imei"; return 0; }

        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(cfg.host(), cfg.port()), CONNECT_MS);
            s.setSoTimeout(READ_MS);
            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));

            write(out, "IWAP00" + cfg.imei() + "#");   // no comma; see send()

            String when = FRAME.format(new Date(r.wakeAt));
            sent += one(out, in, when, TYPE_TST, r.tstMin);
            sent += one(out, in, when, TYPE_SPT, r.sptMin);
            sent += one(out, in, when, TYPE_WASO, r.wasoMin);
            sent += one(out, in, when, TYPE_EFFICIENCY, r.efficiencyPct);
            sent += one(out, in, when, TYPE_WAKEUPS, r.wakeups);
            return sent;
        } catch (Exception e) {
            problem = "cannot reach " + cfg.host() + ":" + cfg.port();
            return sent;
        } finally {
            try { if (s != null) s.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    /**
     * One reading, on its own connection. Used for the live sleeping flag and
     * the running day total, which are single numbers sent as they change
     * rather than a batch at the end of a night.
     */
    public boolean sendOne(TrackerConfig cfg, int type, int value, long at) {
        problem = null;
        sent = 0;
        if (!cfg.usable()) { problem = "no imei"; return false; }

        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(cfg.host(), cfg.port()), CONNECT_MS);
            s.setSoTimeout(READ_MS);
            OutputStream out = s.getOutputStream();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));

            write(out, "IWAP00" + cfg.imei() + "#");   // no comma; see send()
            sent = one(out, in, FRAME.format(new Date(at)), type, value);
            return sent > 0;
        } catch (Exception e) {
            problem = "cannot reach " + cfg.host() + ":" + cfg.port();
            return false;
        } finally {
            try { if (s != null) s.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    /** Remember the newest night sent, so a refresh does not resend it. */
    public static void markSent(SharedPreferences prefs, List<Night> nights) {
        if (nights == null || nights.isEmpty()) return;
        String newest = nights.get(nights.size() - 1).date;
        prefs.edit().putString(LAST_SENT, newest).commit();
    }

    public static String lastSent(SharedPreferences prefs) {
        return prefs.getString(LAST_SENT, null);
    }
}
