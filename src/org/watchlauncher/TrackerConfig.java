package org.watchlauncher;

import android.content.Context;
import android.telephony.TelephonyManager;

/**
 * Where the tracker firmware reports to, read from the tracker firmware.
 *
 * Rather than hardcoding a host, this takes the same settings the vendor app
 * is using, out of its own preferences file:
 *
 * <pre>
 * &lt;string name="ProtocolServerHOST"&gt;193.24.208.184&lt;/string&gt;
 * &lt;int name="ProtoclServerPort" value="9000" /&gt;
 * </pre>
 *
 * The misspelling of "Protocl" is the vendor's and is matched exactly, because
 * that is the key that is actually in the file. Both spellings are accepted in
 * case a later firmware fixes it.
 *
 * That file is {@code system:system}, so it is read through the root shell,
 * the same one the terminal and the sleep upload use.
 */
public class TrackerConfig {

    private static final String PREFS =
            "/data/data/com.enqualcomm.support/shared_prefs/"
          + "com.enqualcomm.support_preferences.xml";

    private static final String DEFAULT_HOST = "coredump.ws";
    private static final int DEFAULT_PORT = 9000;

    private final Context ctx;
    private final RootShell root;

    private String host = null;
    private int port = -1;
    private String imei = null;

    public TrackerConfig(Context c, RootShell root) {
        this.ctx = c.getApplicationContext();
        this.root = root;
    }

    public String host() { return host == null ? DEFAULT_HOST : host; }
    public int port() { return port <= 0 ? DEFAULT_PORT : port; }
    public String imei() { return imei; }

    public boolean usable() { return imei != null && imei.length() >= 8; }

    /** Blocking: one root shell round trip. Not on the UI thread. */
    public synchronized void load() {
        readImei();
        if (root == null || !root.isRoot()) return;

        String xml = root.exec("cat " + PREFS + " 2>/dev/null");
        if (xml == null) return;

        String h = value(xml, "ProtocolServerHOST");
        if (h == null) h = value(xml, "ProtoclServerHOST");
        if (h != null && h.length() > 0) host = h;

        String p = value(xml, "ProtoclServerPort");
        if (p == null) p = value(xml, "ProtocolServerPort");
        try {
            if (p != null) port = Integer.parseInt(p.trim());
        } catch (Exception e) { /* leave the default */ }
    }

    /**
     * The IMEI the server files everything under. Taken from the modem rather
     * than from the vendor's config, because {@code persist.sys.protocol_IMEI}
     * can override what the tracker reports and a mismatch would file the
     * sleep data under a device that does not exist.
     */
    private void readImei() {
        try {
            TelephonyManager t =
                    (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            String id = (t == null) ? null : t.getDeviceId();
            if (id != null && id.length() >= 8) imei = id.trim();
        } catch (Exception e) {
            imei = null;
        }
        // An override wins, for a unit whose reported id is not its modem's.
        String prop = getprop("persist.sys.protocol_IMEI");
        if (prop != null && prop.length() >= 8) imei = prop;
    }

    private String getprop(String name) {
        if (root == null) return null;
        String r = root.exec("getprop " + name);
        if (r == null) return null;
        r = r.trim();
        return r.length() == 0 ? null : r;
    }

    /** Pull one value out of an Android shared_prefs XML, string or int. */
    private static String value(String xml, String name) {
        String[] patterns = {
            "name=\"" + name + "\">",          // <string name="X">value</string>
            "name=\"" + name + "\" value=\"",  // <int name="X" value="1" />
        };
        for (int i = 0; i < patterns.length; i++) {
            int at = xml.indexOf(patterns[i]);
            if (at < 0) continue;
            int from = at + patterns[i].length();
            int to = (i == 0) ? xml.indexOf('<', from) : xml.indexOf('"', from);
            if (to > from) return xml.substring(from, to).trim();
        }
        return null;
    }
}
