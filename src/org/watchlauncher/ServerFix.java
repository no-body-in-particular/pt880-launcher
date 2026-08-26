package org.watchlauncher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * The position and speed as the server already has them.
 *
 * This is the best of the three sources. The watch's own location frames carry
 * no coordinates indoors, so nothing local can measure a speed from them; the
 * server resolves those WiFi and cell readings into a real position and then
 * computes the speed between consecutive fixes. Asking it is both simpler and
 * more accurate than reproducing it here.
 *
 * It is the same request the tracker web page makes:
 *
 * <pre>
 * current.php?imei=&lt;imei&gt;&amp;viewonly=&lt;token&gt;
 *   -&gt; 2026-08-25T17:39:58Z,51.4667000,4.5000000,0.044441,2
 *      time                  lat       lon      km/h     fix type
 * </pre>
 *
 * Fix type is CTracker's: 0 GPS, 1 cell tower, 2 WiFi. It matters, because the
 * server refuses to measure a speed against a cell fix -- those hop kilometres
 * as the serving tower changes -- so a type 1 row carries no usable speed and
 * none is shown for one.
 *
 * <h3>Why the URL is not in this file</h3>
 *
 * That URL carries the watch's IMEI and a view-only token, and together they
 * are read access to its live location and its history. This APK is published
 * on a public web server for anyone to download, so a URL compiled into it
 * would hand that access to anyone who fetched the file. It is read from the
 * watch's own storage instead, the same way contacts are:
 *
 * <pre>
 * adb push tracker.txt /sdcard/Documents/
 * </pre>
 *
 * one line, the whole URL. The repository keeps per-unit identifiers out of
 * git for exactly the same reason, and the token can be regenerated from the
 * tracker page if it ever gets out.
 *
 * <h3>Why this may report SSLHandshake</h3>
 *
 * This watch cannot complete a TLS handshake with a modern server: it offers
 * only ECDHE with AES-GCM, and GCM arrived in Android's TLS stack at API 20
 * while this device is 19. Bundled CA roots do not help, because the failure
 * happens before any certificate is examined.
 *
 * The URL is deliberately not downgraded to http automatically. It carries a
 * token that reads a location history, and quietly putting that on the wire is
 * not a decision an app should make on its owner's behalf. Writing an http URL
 * into tracker.txt is a decision the owner can make.
 */
public class ServerFix {

    private static final String[] PATHS = {
        "/sdcard/Documents/tracker.txt",
        "/sdcard/documents/tracker.txt",
        "/sdcard/tracker.txt",
    };

    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 8000;

    private static final SimpleDateFormat ISO =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    static {
        ISO.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /** CTracker's position types. */
    public static final int TYPE_GPS = 0;
    public static final int TYPE_CELL = 1;
    public static final int TYPE_WIFI = 2;

    /** Needed only to read the bundled CA roots out of the APK. This device's
     *  trust store predates every ISRG root, so without them no https request
     *  to a Let's Encrypt host completes at all. */
    private final Context ctx;

    private float speed = -1;
    private double lat, lon;
    private long at = 0;
    private int type = -1;
    private String problem = null;

    public ServerFix(Context c) {
        ctx = (c == null) ? null : c.getApplicationContext();
    }

    public float speed() { return speed; }
    public long at() { return at; }
    public int type() { return type; }
    public double lat() { return lat; }
    public double lon() { return lon; }
    public String problem() { return problem; }

    public boolean configured() { return url() != null; }

    /** The fix type in one word, for the line under the speed. */
    public String typeName() {
        switch (type) {
            case TYPE_GPS: return "gps";
            case TYPE_CELL: return "cell";
            case TYPE_WIFI: return "wifi";
            default: return "server";
        }
    }

    private static String url() {
        for (int i = 0; i < PATHS.length; i++) {
            File f = new File(PATHS[i]);
            if (!f.isFile() || !f.canRead()) continue;
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#")) continue;
                    if (line.startsWith("http://") || line.startsWith("https://")) return line;
                }
            } catch (Exception e) {
                /* unreadable is the same as absent */
            } finally {
                try { if (r != null) r.close(); } catch (Exception e) { /* ignore */ }
            }
        }
        return null;
    }

    /** Blocking network call. Never on the UI thread. */
    public synchronized void refresh() {
        problem = null;
        String u = url();
        if (u == null) {
            problem = "no tracker.txt";
            Log.w("watchmap", "ServerFix: no tracker.txt in any of the search paths");
            return;
        }
        Log.i("watchmap", "ServerFix: GET " + u.replaceAll("viewonly=[^&]*", "viewonly=***"));

        // Two attempts. The second asks for a fresh connection.
        //
        // Connections are pooled now, and this device's TLS is BouncyCastle
        // rather than the platform's. A pooled socket that the server has
        // since closed comes back as an IllegalStateException rather than as
        // a clean retry, and once one is in the pool every request after it
        // fails the same way - which is how the map came to sit on "no
        // position" with the network working perfectly well.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (fetchOnce(u, attempt > 0)) return;
        }
    }

    /** @return true if it worked, or failed in a way a retry will not fix */
    private boolean fetchOnce(String u, boolean fresh) {
        HttpURLConnection c = null;
        BufferedReader r = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            if (c instanceof HttpsURLConnection) {
                // API 19 supports TLS 1.2 but does not enable it, and the
                // server refuses everything older.
                SSLSocketFactory f = Tls12SocketFactory.create(ctx);
                if (f != null) ((HttpsURLConnection) c).setSSLSocketFactory(f);
            }
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestProperty("Accept", "text/plain");
            c.setUseCaches(false);
            if (fresh) c.setRequestProperty("Connection", "close");

            int code = c.getResponseCode();
            if (code != 200) {
                problem = "server said " + code;
                Log.w("watchmap", "ServerFix: http " + code);
                return true;                    // a retry will say the same
            }

            r = new BufferedReader(new InputStreamReader(c.getInputStream()));
            String line = r.readLine();
            if (!parse(line)) {
                // The endpoint answers with an HTML "please login" page when
                // the token no longer matches the imei, which is worth saying
                // plainly rather than reporting as a parse failure.
                problem = (line != null && line.startsWith("<"))
                        ? "token rejected" : "bad reply";
                return true;                    // the reply is wrong, not the socket
            }
            problem = null;                     // worked; clear any earlier fault
            return true;
        } catch (Exception e) {
            // The class name, not "offline". SSLHandshakeException,
            // UnknownHostException and ConnectException are three entirely
            // different faults with three different fixes, and collapsing them
            // into one word cost an evening: a certificate the device was too
            // old to trust looked exactly like having no signal.
            problem = e.getClass().getSimpleName().replace("Exception", "");
            // The class name is the useful half: SSLHandshakeException and
            // UnknownHostException are entirely different problems and both
            // read as "offline" without it.
            Log.w("watchmap", "ServerFix: " + e.getClass().getSimpleName()
                    + " " + String.valueOf(e.getMessage()));
            return false;                       // worth one more go
        } finally {
            try { if (r != null) r.close(); } catch (Exception e) { /* ignore */ }
            if (c != null) c.disconnect();
        }
    }

    /** {@code 2026-08-25T17:39:58Z,51.4667000,4.5000000,0.044441,2} */
    boolean parse(String line) {
        if (line == null) return false;
        String[] f = line.trim().split(",");
        if (f.length < 5) return false;
        try {
            long when = ISO.parse(f[0].trim()).getTime();
            double la = Double.parseDouble(f[1].trim());
            double lo = Double.parseDouble(f[2].trim());
            int ty = Integer.parseInt(f[4].trim());
            // The speed column is empty when the server could not measure one.
            // That is not zero and must not be turned into it.
            String sp = f[3].trim();
            float s = (sp.length() == 0) ? -1 : Float.parseFloat(sp);

            Log.i("watchmap", "ServerFix: parsed " + la + "," + lo + " type " + ty);
            at = when;
            lat = la;
            lon = lo;
            type = ty;
            // A cell fix cannot produce a speed; the server does not measure
            // one against it, so nothing is shown for one here either.
            speed = (ty == TYPE_CELL) ? -1 : s;
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
