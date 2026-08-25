package org.watchlauncher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

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
 * socket it hands out. Nothing here weakens anything: the certificate chain is
 * still verified by the platform in the usual way, and the alternative would
 * be plaintext http, which would put a location-history token on the wire.
 */
public class Tls12SocketFactory extends SSLSocketFactory {

    private static final String[] PROTOCOLS = {"TLSv1", "TLSv1.1", "TLSv1.2"};

    private final SSLSocketFactory inner;

    private Tls12SocketFactory(SSLSocketFactory inner) {
        this.inner = inner;
    }

    /** @return a factory speaking TLS 1.2, or null if the platform cannot. */
    public static SSLSocketFactory create() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, null, null);
            return new Tls12SocketFactory(ctx.getSocketFactory());
        } catch (Exception e) {
            return null;
        }
    }

    private Socket enable(Socket s) {
        if (s instanceof SSLSocket) {
            try {
                ((SSLSocket) s).setEnabledProtocols(PROTOCOLS);
            } catch (Exception e) {
                // A provider that will not take all three: leave it with
                // whatever it had rather than failing the connection here.
            }
        }
        return s;
    }

    @Override
    public String[] getDefaultCipherSuites() { return inner.getDefaultCipherSuites(); }

    @Override
    public String[] getSupportedCipherSuites() { return inner.getSupportedCipherSuites(); }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose)
            throws IOException {
        return enable(inner.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return enable(inner.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return enable(inner.createSocket(host, port, localHost, localPort));
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
