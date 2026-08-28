package org.watchlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;

/**
 * Where the tracker reports to, and under what id.
 *
 * <h3>Why this no longer reads the vendor's file</h3>
 *
 * It used to take its host and port out of
 * {@code /data/data/com.enqualcomm.support/shared_prefs/...}, on the reasoning
 * that whatever the firmware was already talking to was the right answer, and
 * it read that file through the root shell because it is {@code system:system}.
 *
 * That app is not on this watch any more - no entry in {@code pm list
 * packages}, no apk in {@code /system/priv-app}, no {@code /data/data}
 * directory. So the read found nothing, fell through to the defaults, and spent
 * a root shell round trip on every load doing it. Worse, it was silent: a
 * config source that has quietly become a constant still reads like a config
 * source.
 *
 * <h3>What it reads instead</h3>
 *
 * Its own preferences, the same ones the rest of the tracker client uses, with
 * the built-in defaults until something sets them. Nothing here needs root.
 *
 * <h3>Changing it</h3>
 *
 * {@code #host#=<addr>} and {@code #ip#=<addr>} - over SMS, or through
 * {@code BPSM} on the socket, which is the server's own route into the same
 * command set - are handled by {@link SmsControl} and written by
 * {@link TrackerService#setEndpoint}. Both accept {@code host} or
 * {@code host:port}. {@code #imei#=} sets the reported id, for a unit whose
 * modem id is not what the server files it under.
 *
 * {@code BP19}, the protocol's own "report to this server instead", stays
 * refused. It arrives unauthenticated on a plaintext socket with no sender to
 * check, and obeying it would hand the watch to anyone who can reach the port;
 * the SMS route gates the same capability behind an allowlist. That asymmetry
 * is deliberate - see the BP19 branch in TrackerService.
 */
public class TrackerConfig {

    private static final String DEFAULT_HOST = "coredump.ws";
    private static final int DEFAULT_PORT = 9000;

    /** Set only for a unit whose reported id is not its modem's. */
    static final String KEY_IMEI = "client_imei";

    private final Context ctx;

    private String host = null;
    private int port = -1;
    private String imei = null;

    public TrackerConfig(Context c) {
        this.ctx = c.getApplicationContext();
    }

    /**
     * Kept for callers that still hand over a root shell. Nothing here needs
     * one now; the argument is ignored rather than the constructor removed, so
     * call sites that open a shell for their own reasons do not have to change.
     */
    public TrackerConfig(Context c, RootShell ignored) {
        this(c);
    }

    public String host() { return host == null ? DEFAULT_HOST : host; }

    public int port() { return port <= 0 ? DEFAULT_PORT : port; }

    public String imei() { return imei; }

    public boolean usable() { return imei != null && imei.length() >= 8; }

    /** Cheap now: preferences and one TelephonyManager call, no shell. */
    public synchronized void load() {
        SharedPreferences p = TrackerService.prefs(ctx);

        String h = p.getString(TrackerService.KEY_HOST, null);
        if (h != null && h.trim().length() > 0) host = h.trim();

        int n = p.getInt(TrackerService.KEY_PORT, 0);
        if (n > 0) port = n;

        readImei(p);
    }

    /**
     * The id the server files everything under.
     *
     * The modem's, normally. An override in our own preferences wins, for a
     * unit whose reported id is not its modem's - the vendor had
     * {@code persist.sys.protocol_IMEI} for the same purpose, and reading that
     * property needed root. A mismatch here files every reading against a
     * device that does not exist, so it is worth being able to set explicitly.
     */
    private void readImei(SharedPreferences p) {
        try {
            TelephonyManager t =
                    (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            String id = (t == null) ? null : t.getDeviceId();
            if (id != null && id.trim().length() >= 8) imei = id.trim();
        } catch (Exception e) {
            imei = null;
        }

        String override = p.getString(KEY_IMEI, null);
        if (override != null && override.trim().length() >= 8) imei = override.trim();
    }

    /** Set the reported id, or clear it back to the modem's with an empty string. */
    public static void setImei(Context c, String id) {
        SharedPreferences.Editor e = TrackerService.prefs(c).edit();
        if (id == null || id.trim().length() < 8) {
            e.remove(KEY_IMEI);
        } else {
            e.putString(KEY_IMEI, id.trim());
        }
        e.commit();
    }
}
