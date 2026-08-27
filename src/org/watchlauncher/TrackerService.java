package org.watchlauncher;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Holds the connection to the tracker server, in the launcher, instead of the vendor app.
 *
 * <h3>Why the launcher should own it</h3>
 *
 * The vendor's {@code com.enqualcomm.support} is an ordinary app as far as Android is concerned,
 * so anything reclaiming processes takes it like any other, and {@link Guard} exists only to
 * make that less likely. The launcher is the home activity and survives. Moving the socket here
 * means the thing that holds the server link is the thing the system is least willing to kill,
 * and the recovery path stops depending on a process that can be taken at any moment.
 *
 * <h3>Two clients cannot share one device id</h3>
 *
 * The server binds a device to whichever connection last identified as it -- its own log says
 * "command ownership taken by connection &lt;id&gt;". If this ran while the vendor app was still
 * connected the two would take ownership from each other in a loop, and commands would land on
 * whichever happened to hold it. So this is off unless {@link #setEnabled} has been called, and
 * turning it on is the same decision as disabling the vendor app. It is not a default.
 *
 * <h3>Protocol</h3>
 *
 * {@link BeehomeCodec} owns the wire format; this owns the socket, the timing and the replies.
 * The sequence on a new connection is what the watch actually does, taken from server logs
 * rather than from the specification:
 *
 * <ol>
 *   <li>connect;
 *   <li>send {@code AP03} -- the heartbeat doubles as the login, and its device id is how the
 *       server decides which device this socket is. There is no {@code AP00} on the wire;
 *   <li>send {@code APVR} with the build string;
 *   <li>answer whatever arrives, echoing each command's token back under the same opcode.
 * </ol>
 */
public class TrackerService extends Service {

    private static final String TAG = "TrackerService";

    private static final String PREFS = "tracker";
    private static final String KEY_ENABLED = "client_enabled";
    private static final String KEY_HOST = "client_host";
    private static final String KEY_PORT = "client_port";
    private static final String KEY_CYCLE = "client_cycle_s";
    private static final String KEY_VITALS = "client_vitals_s";

    /** The vendor's own pulse type on the JK frame. */
    private static final int TYPE_PULSE = 2;

    /** Matches the cadence the vendor used, and what the server expects to see. */
    private static final int HEARTBEAT_MS = 10 * 60 * 1000;

    /** Backoff between reconnects: quick at first, then out of the way. */
    private static final int[] BACKOFF_MS = {5000, 15000, 60000, 300000};

    /** How often the loop wakes to check its timers when the socket is quiet. */
    private static final int TICK_MS = 20000;

    private volatile boolean running;
    private Thread worker;
    private volatile Socket sock;
    private volatile String lastState = "not started";

    /** Set by requestFix() so an out-of-band ask does not wait for the next cycle. */
    private volatile boolean fixNow;

    /** The running instance, so the SMS plane can poke it. Cleared in onDestroy so a stopped
     *  service is not mistaken for a live one. */
    private static volatile TrackerService live;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enabled(this)) {
            // Not an error: this is the normal state until the vendor app is retired.
            Log.i(TAG, "tracker client disabled; not connecting");
            stopSelf();
            return START_NOT_STICKY;
        }
        live = this;
        if (worker == null) {
            running = true;
            worker = new Thread(new Runnable() {
                public void run() { loop(); }
            }, "tracker");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (live == this) live = null;
        closeQuietly();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ connection

    private void loop() {
        int attempt = 0;
        while (running) {
            try {
                connectAndServe();
                attempt = 0;                       // a clean session resets the backoff
            } catch (Throwable t) {
                lastState = "disconnected: " + t;
                Log.w(TAG, "session ended", t);
            }
            if (!running) break;
            int wait = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
            attempt++;
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void connectAndServe() throws Exception {
        TrackerConfig cfg = config();
        String id = cfg.imei();
        if (id == null || id.length() < 8) {
            lastState = "no device id";
            throw new IllegalStateException("no device id");
        }

        Socket s = new Socket();
        s.connect(new InetSocketAddress(host(this, cfg), port(this, cfg)), 15000);
        // Short, deliberately. The read below is what paces the whole loop, so the
        // timeout has to be shorter than the soonest timer or a three-minute vitals
        // cycle would not fire until an eleven-minute read returned.
        s.setSoTimeout(TICK_MS);
        sock = s;
        lastState = "connected";

        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();

        // The heartbeat is the login: nothing else identifies this socket to the server.
        send(out, BeehomeCodec.heartbeat(id, TrackerSources.steps(this),
                TrackerSources.battery(this), cycleSeconds()));
        send(out, BeehomeCodec.version(id, android.os.Build.DISPLAY));

        // First position straight away: a server that has just seen a device reconnect wants to
        // know where it is, and waiting a full cycle to say so is the difference between a gap
        // on the map and a continuous track.
        send(out, TrackerSources.positionFrame(this, id));

        long nextBeat = SystemClock.elapsedRealtime() + HEARTBEAT_MS;
        long nextFix = SystemClock.elapsedRealtime() + cycleSeconds() * 1000L;
        long nextVitals = SystemClock.elapsedRealtime() + vitalsSeconds() * 1000L;
        StringBuilder buf = new StringBuilder();
        byte[] chunk = new byte[2048];

        while (running) {
            long now = SystemClock.elapsedRealtime();
            if (now >= nextBeat) {
                send(out, BeehomeCodec.heartbeat(id, TrackerSources.steps(this),
                        TrackerSources.battery(this), cycleSeconds()));
                nextBeat = now + HEARTBEAT_MS;
            }
            if (fixNow) {
                fixNow = false;
                send(out, TrackerSources.positionFrame(this, id));
                nextFix = now + cycleSeconds() * 1000L;
            }
            if (now >= nextFix) {
                send(out, TrackerSources.positionFrame(this, id));
                nextFix = now + cycleSeconds() * 1000L;
            }
            if (now >= nextVitals) {
                sendVitals(out);
                nextVitals = now + vitalsSeconds() * 1000L;
            }
            int n;
            try {
                n = in.read(chunk);
            } catch (java.net.SocketTimeoutException te) {
                continue;                          // idle is normal; the beat above drives us
            }
            if (n < 0) throw new java.io.EOFException("server closed");
            buf.append(new String(chunk, 0, n, "UTF-8"));

            String[] parts = BeehomeCodec.split(buf.toString());
            buf.setLength(0);
            buf.append(parts[parts.length - 1]);    // keep the partial tail
            for (int i = 0; i < parts.length - 1; i++) {
                handle(out, id, BeehomeCodec.decode(parts[i]));
            }
        }
    }

    /**
     * Answer one command.
     *
     * Unknown opcodes are acknowledged rather than ignored. The server retries a command it got
     * no answer to, and a silent client turns one unsupported command into a permanent retry
     * loop -- which costs the radio far more than the command would have.
     */
    private void handle(OutputStream out, String id, BeehomeCodec.Frame f) throws Exception {
        if (f == null) return;
        Log.i(TAG, "<- " + f);

        if ("18".equals(f.op)) {                    // reboot
            send(out, BeehomeCodec.ack(f.op, f.token()));
            reboot();
            return;
        }
        if ("15".equals(f.op)) {                    // set location interval
            // IWBP15,<id>,<token>,60#  -- the interval is the field after the token.
            applyInterval(KEY_CYCLE, f, 30, 24 * 3600);
            send(out, BeehomeCodec.ack(f.op, f.token()));
            return;
        }
        if ("SQ".equals(f.op)) {                    // vitals measurement period
            applyInterval(KEY_VITALS, f, 60, 24 * 3600);
            send(out, BeehomeCodec.ack(f.op, f.token()));
            return;
        }
        if ("50".equals(f.op)) {                    // a poll: answer with where we are
            send(out, BeehomeCodec.ack(f.op, f.token()));
            send(out, TrackerSources.positionFrame(this, id));
            return;
        }
        // XL, TE and anything else: echo the token so the server can close it out.
        send(out, BeehomeCodec.ack(f.op, f.token()));
    }

    /**
     * Take an interval out of a command's trailing numeric field.
     *
     * Clamped, because the interval arrives over an unauthenticated plaintext link and a value
     * of zero or one would turn the watch into a beacon that flattens itself in an afternoon.
     * A refused value is logged rather than silently corrected, so a server setting something
     * impossible finds out from the log rather than from the battery.
     */
    private void applyInterval(String key, BeehomeCodec.Frame f, int lo, int hi) {
        String tok = f.token();
        for (int i = f.fields.size() - 1; i >= 0; i--) {
            String v = f.fields.get(i);
            if (v.equals(tok) || v.length() == 0) continue;
            try {
                int n = Integer.parseInt(v.trim());
                if (n < lo || n > hi) {
                    Log.w(TAG, "refusing out-of-range interval " + n + " for " + key);
                    return;
                }
                prefs(this).edit().putInt(key, n).commit();
                Log.i(TAG, key + " set to " + n + "s by the server");
                return;
            } catch (NumberFormatException e) {
                // not the field we wanted; keep looking backwards
            }
        }
    }

    /**
     * A vitals reading, taken here rather than listened for.
     *
     * While the vendor app was running these arrived as broadcasts and {@link PpgWatchdog} only
     * had to notice them. Once it is gone nothing else takes a measurement, so the client has to
     * ask the sensor itself.
     */
    private void sendVitals(OutputStream out) {
        try {
            final HeartRate hr = new HeartRate(this, null);
            if (!hr.available()) return;
            hr.start();
            // An optical reading needs several seconds of clean signal; past that it is not
            // coming, and holding the LED against the skin for longer only costs battery.
            for (int i = 0; i < 40 && hr.bpm() <= 0; i++) Thread.sleep(500);
            int bpm = hr.bpm();
            if (bpm > 0) {
                send(out, BeehomeCodec.health(TrackerSources.stamp(), TYPE_PULSE, bpm));
            }
        } catch (Throwable t) {
            Log.w(TAG, "no vitals reading", t);
        }
    }

    private void send(OutputStream out, String frame) throws Exception {
        Log.i(TAG, "-> " + frame);
        out.write(frame.getBytes("UTF-8"));
        out.flush();
    }

    private void closeQuietly() {
        Socket s = sock;
        sock = null;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ inputs

    private TrackerConfig config() {
        RootShell sh = new RootShell();
        try {
            sh.open();
            TrackerConfig c = new TrackerConfig(this, sh);
            c.load();
            return c;
        } finally {
            try { sh.close(); } catch (Throwable ignored) { }
        }
    }

    private int battery() {
        try {
            Intent i = registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return 0;
            int level = i.getIntExtra("level", -1);
            int scale = i.getIntExtra("scale", 100);
            return (level < 0 || scale <= 0) ? 0 : (level * 100 / scale);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Location cycle, as the server last set it. 600 s is what the vendor shipped. */
    private int cycleSeconds() {
        return prefs(this).getInt(KEY_CYCLE, 600);
    }

    /** Vitals period. The firmware managed one every three minutes when it was working. */
    private int vitalsSeconds() {
        return prefs(this).getInt(KEY_VITALS, 180);
    }

    private void reboot() {
        RootShell sh = new RootShell();
        try {
            if (sh.open() && sh.isRoot()) sh.runQuiet("reboot");
        } catch (Throwable t) {
            Log.w(TAG, "reboot failed", t);
        } finally {
            try { sh.close(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ settings + hooks

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(KEY_ENABLED, false);
    }

    /** Turning this on is the same decision as disabling the vendor app. See the class note. */
    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).commit();
        Intent i = new Intent(c, TrackerService.class);
        if (on) c.startService(i); else c.stopService(i);
    }

    static String host(Context c, TrackerConfig cfg) {
        String h = prefs(c).getString(KEY_HOST, null);
        return (h != null && h.length() > 0) ? h : cfg.host();
    }

    static int port(Context c, TrackerConfig cfg) {
        int p = prefs(c).getInt(KEY_PORT, 0);
        return p > 0 ? p : cfg.port();
    }

    /** {@code host=1.2.3.4} or {@code host=1.2.3.4:9000}, from the SMS control plane. */
    public static void setEndpoint(Context c, String hostAndPort) {
        String h = hostAndPort;
        int p = 0;
        int colon = hostAndPort.lastIndexOf(':');
        if (colon > 0) {
            h = hostAndPort.substring(0, colon);
            try {
                p = Integer.parseInt(hostAndPort.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                p = 0;
            }
        }
        SharedPreferences.Editor e = prefs(c).edit().putString(KEY_HOST, h.trim());
        if (p > 0) e.putInt(KEY_PORT, p);
        e.commit();

        // Restart so the change takes effect now rather than at the next disconnect.
        if (enabled(c)) {
            c.stopService(new Intent(c, TrackerService.class));
            c.startService(new Intent(c, TrackerService.class));
        }
    }

    /**
     * Report position now rather than at the next cycle. Returns false if the client is not
     * connected, so the caller can say so instead of implying it worked.
     */
    public static boolean requestFix(Context c) {
        TrackerService t = live;
        if (t == null || !t.running) return false;
        t.fixNow = true;
        return true;
    }

    /**
     * Take a photo off the calling thread. The camera settles for over a second and the
     * callback is asynchronous, and a broadcast receiver that blocked on that would be killed
     * for taking too long before the shutter fired.
     */
    public static void requestPhoto(final Context c) {
        new Thread(new Runnable() {
            public void run() { Capture.once(); }
        }, "capture").start();
    }

    /** One line for the SMS {@code status} reply. */
    public static String describe(Context c) {
        if (!enabled(c)) return "tracker client off (vendor app still owns the link)";
        return "tracker client on";
    }
}
