package org.watchlauncher;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * TLS 1.2 on Android 4.4.
 *
 * API 19 <em>supports</em> TLSv1.1 and TLSv1.2 but does not <em>enable</em>
 * them: a socket from the default factory offers TLSv1 only, and any server
 * that has since turned the older protocols off answers with a handshake
 * failure and nothing more useful. coredump.ws is one of those -- it accepts
 * TLSv1.2 and refuses 1.0 and 1.1 -- so without this the sports screen could
 * not reach it at all.
 *
 * The fix is to wrap the real factory and switch the protocols on for every
 * socket it hands out.
 *
 * <h3>And the certificates</h3>
 *
 * The second half of the problem, and the one that actually stopped every
 * request. This watch's trust store was assembled in 2013 and contains no ISRG
 * root, so a Let's Encrypt chain -- which is what coredump.ws serves, and most
 * of the web with it -- fails to validate. Old Androids used to survive on
 * Let's Encrypt's cross-signature from DST Root CA X3, which they accepted
 * despite its expiry; that cross-sign is gone now.
 *
 * So the ISRG roots are carried in the APK and added to the platform's own
 * trust store rather than replacing it. Nothing is weakened: chains are still
 * verified, hostnames still checked, and a certificate from any other CA the
 * device already trusts still works. What changes is that a root the device is
 * simply too old to have heard of is now known to it.
 */
public class Tls12SocketFactory extends SSLSocketFactory {

    private static final String[] PROTOCOLS = {"TLSv1", "TLSv1.1", "TLSv1.2"};

    private final SSLSocketFactory inner;

    /** BouncyCastle already offers everything worth offering, so its sockets
     *  want the hostname and nothing else touched. */
    private final boolean isBc;

    private Tls12SocketFactory(SSLSocketFactory inner) {
        this(inner, false);
    }

    private Tls12SocketFactory(SSLSocketFactory inner, boolean isBc) {
        this.inner = inner;
        this.isBc = isBc;
    }

    /** Roots the device is too old to know about, added to the ones it has. */
    private static final String EXTRA_ROOTS = "roots.pem";

    private static SSLSocketFactory cached;

    /** @return a factory speaking TLS 1.2, or null if the platform cannot. */
    public static SSLSocketFactory create() {
        return create(null);
    }

    /**
     * A TLS stack that does not depend on this device's.
     *
     * Android 4.4 has no AES-GCM cipher suites - not disabled, absent: the
     * strings are not in the system image, so nothing an app enables can bring
     * them back. A server offering only ECDHE with GCM, which is what a modern
     * configuration offers, therefore has nothing in common with this platform
     * and the handshake fails before certificates are considered.
     *
     * The alternative to weakening the server is to stop using the platform's
     * TLS. BouncyCastle's implementation is pure Java, needs nothing from the
     * system, and speaks ECDHE with AES-GCM on a runtime from 2013. It is
     * slower than native crypto - a few hundred kilobytes of tile costs
     * fractions of a second - which is a fair price for not asking a public
     * web server to accept 2013 ciphers so that one watch can connect.
     *
     * @return null when BouncyCastle is not on the classpath, so the caller
     *         falls back to the platform and simply cannot reach strict hosts
     */
    private static SSLSocketFactory bouncyCastle(TrustManager[] tms) {
        try {
            Class.forName("org.bouncycastle.jsse.provider.BouncyCastleJsseProvider");
        } catch (Throwable t) {
            return null;
        }
        try {
            java.security.Provider crypto =
                    new org.bouncycastle.jce.provider.BouncyCastleProvider();
            java.security.Provider jsse =
                    new org.bouncycastle.jsse.provider.BouncyCastleJsseProvider(crypto);
            SSLContext sc = SSLContext.getInstance("TLSv1.2", jsse);
            sc.init(null, tms, null);
            return sc.getSocketFactory();
        } catch (Throwable t) {
            android.util.Log.w("watchmap", "bouncycastle tls unavailable: " + t);
            return null;
        }
    }

    /**
     * @param ctx used to read the bundled roots. Without one this falls back
     *            to the platform's trust store alone, which on this device
     *            cannot validate a Let's Encrypt chain.
     */
    public static synchronized SSLSocketFactory create(Context ctx) {
        // Only a factory built with the bundled roots is worth keeping. One
        // built without them would otherwise be cached by the first caller
        // that had no context and handed to every caller that did, quietly
        // undoing the whole point of carrying them.
        if (cached != null && cachedHasRoots) return cached;
        try {
            TrustManager[] tms = trustManagers(ctx);

            // BouncyCastle first: it is the only one of the two that can talk
            // to a server which requires AES-GCM, and this device's own stack
            // has none. Its sockets already offer everything worth offering,
            // so they are used as they come rather than being wrapped.
            SSLSocketFactory bc = bouncyCastle(tms);
            if (bc != null) {
                // Still wrapped. BouncyCastle needs to be told the hostname or
                // it cannot do its own endpoint identification, and the only
                // place that name exists is the createSocket call the wrapper
                // already intercepts.
                cached = new Tls12SocketFactory(bc, true);
                cachedHasRoots = (ctx != null);
                usingBouncyCastle = true;
                return cached;
            }

            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, tms, null);
            cached = new Tls12SocketFactory(sc.getSocketFactory());
            cachedHasRoots = (ctx != null);
            return cached;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean cachedHasRoots = false;
    private static boolean usingBouncyCastle = false;

    /**
     * The platform's trust managers, plus one seeded with the bundled roots.
     *
     * Two managers rather than one merged store, because the platform's own is
     * backed by a live keystore that also carries anything the user has
     * installed; copying it would freeze that at build time.
     */
    private static TrustManager[] trustManagers(Context ctx) throws Exception {
        final X509TrustManager platform = defaultManager(null);
        final X509TrustManager extra = (ctx == null) ? null : bundledManager(ctx);
        if (extra == null) {
            return new TrustManager[]{platform};
        }

        final List<X509Certificate> issuers = new ArrayList<X509Certificate>();
        for (X509Certificate c : platform.getAcceptedIssuers()) issuers.add(c);
        for (X509Certificate c : extra.getAcceptedIssuers()) issuers.add(c);

        X509TrustManager both = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String type)
                    throws java.security.cert.CertificateException {
                platform.checkClientTrusted(chain, type);
            }

            public void checkServerTrusted(X509Certificate[] chain, String type)
                    throws java.security.cert.CertificateException {
                try {
                    platform.checkServerTrusted(chain, type);
                } catch (java.security.cert.CertificateException e) {
                    // The platform is the first opinion, not the only one.
                    // Falling through to the bundled roots is what lets a
                    // modern chain validate on a 2013 trust store; a chain
                    // neither of them accepts still throws.
                    extra.checkServerTrusted(chain, type);
                }
            }

            public X509Certificate[] getAcceptedIssuers() {
                return issuers.toArray(new X509Certificate[issuers.size()]);
            }
        };
        return new TrustManager[]{both};
    }

    private static X509TrustManager defaultManager(KeyStore ks) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager t : f.getTrustManagers()) {
            if (t instanceof X509TrustManager) return (X509TrustManager) t;
        }
        throw new IllegalStateException("no X509 trust manager");
    }

    private static X509TrustManager bundledManager(Context ctx) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(EXTRA_ROOTS);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(in);
            if (certs.isEmpty()) return null;

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : certs) ks.setCertificateEntry("extra" + (i++), c);
            return defaultManager(ks);
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) { /* ignore */ }
        }
    }

    private static boolean reported = false;

    private Socket enable(Socket s) {
        return enable(s, null);
    }

    /**
     * @param host the name being connected to, when the caller knew it.
     *
     * Setting it as an SNI server name does two jobs at once: it is what a
     * virtual host needs to serve the right certificate, and it is the only
     * way BouncyCastle's endpoint identification learns which name to check.
     * Without it the handshake fails with "no hostname specified", which reads
     * like a certificate problem and is not one.
     */
    private Socket enable(Socket s, String host) {
        if (!(s instanceof SSLSocket)) return s;
        SSLSocket ssl = (SSLSocket) s;

        if (host != null && host.length() > 0) {
            try {
                javax.net.ssl.SSLParameters p = ssl.getSSLParameters();
                java.util.List<javax.net.ssl.SNIServerName> names =
                        new java.util.ArrayList<javax.net.ssl.SNIServerName>();
                names.add(new javax.net.ssl.SNIHostName(host));
                p.setServerNames(names);
                ssl.setSSLParameters(p);
            } catch (Throwable t) {
                // SNIHostName is API 24 on Android. Where it is missing the
                // platform sets SNI itself from the socket's own hostname, and
                // this is the path that does not need BouncyCastle anyway.
            }
        }

        if (isBc) return s;          // nothing else to widen; it offers it all

        try {
            ssl.setEnabledProtocols(PROTOCOLS);
        } catch (Exception e) {
            // A provider that will not take all three: leave it with whatever
            // it had rather than failing the connection here.
        }
        try {
            // Protocols were only half the problem. API 19 also *supports*
            // cipher suites it does not *enable* - the AES-GCM ones among them
            // - and a server that offers only ECDHE with GCM then has nothing
            // in common with the default list, which fails as a handshake
            // error indistinguishable from an untrusted certificate.
            //
            // Everything the platform can do is turned on. That is not a
            // weakening: the server picks from what both ends offer, and this
            // end offering more cannot make the server choose worse.
            String[] all = ssl.getSupportedCipherSuites();
            ssl.setEnabledCipherSuites(all);

            if (!reported) {
                reported = true;
                StringBuilder gcm = new StringBuilder();
                for (int i = 0; i < all.length; i++) {
                    if (all[i].contains("GCM")) gcm.append(all[i]).append(' ');
                }
                android.util.Log.i("watchmap", "tls: " + all.length
                        + " suites supported; GCM: "
                        + (gcm.length() == 0 ? "NONE" : gcm.toString()));
            }
        } catch (Exception e) {
            android.util.Log.w("watchmap", "tls: cannot widen cipher list: " + e);
        }
        return s;
    }

    /** What the platform can actually negotiate, for the About screen. There
     *  is no point speculating about this from the outside. */
    /** For the About screen: which TLS stack is actually in use. */
    public static String stack() {
        return usingBouncyCastle ? "bouncycastle" : "platform (no GCM)";
    }

    public static String describeSupport() {
        try {
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, null, null);
            SSLSocket s = (SSLSocket) sc.getSocketFactory().createSocket();
            String[] all = s.getSupportedCipherSuites();
            int gcm = 0, ecdhe = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i].contains("GCM")) gcm++;
                if (all[i].contains("ECDHE")) ecdhe++;
            }
            s.close();
            return all.length + " suites, " + ecdhe + " ecdhe, " + gcm + " gcm";
        } catch (Exception e) {
            return "no TLSv1.2";
        }
    }

    @Override
    public String[] getDefaultCipherSuites() { return inner.getDefaultCipherSuites(); }

    @Override
    public String[] getSupportedCipherSuites() { return inner.getSupportedCipherSuites(); }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose)
            throws IOException {
        return enable(inner.createSocket(s, host, port, autoClose), host);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return enable(inner.createSocket(host, port), host);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return enable(inner.createSocket(host, port, localHost, localPort), host);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enable(inner.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port,
                               InetAddress localAddress, int localPort) throws IOException {
        return enable(inner.createSocket(address, port, localAddress, localPort));
    }
}
