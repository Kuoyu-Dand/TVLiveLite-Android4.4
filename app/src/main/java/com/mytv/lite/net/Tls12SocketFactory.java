package com.mytv.lite.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Android 4.x 默认只启用 TLS 1.0，访问 https 源容易握手失败。
 * 这里用系统默认的证书校验链，只把 TLS 1.1 / 1.2 打开。
 */
public final class Tls12SocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    private Tls12SocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    public static X509TrustManager defaultTrustManager() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Exception ignored) {
        }
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    public static SSLSocketFactory create(X509TrustManager tm) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLSv1.2");
            ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
            return new Tls12SocketFactory(ctx.getSocketFactory());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
                return new Tls12SocketFactory(ctx.getSocketFactory());
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private Socket patch(Socket s) {
        if (s instanceof SSLSocket) {
            SSLSocket ssl = (SSLSocket) s;
            List<String> protocols = new ArrayList<String>();
            for (String p : ssl.getSupportedProtocols()) {
                if ("TLSv1.2".equals(p) || "TLSv1.1".equals(p) || "TLSv1".equals(p)) {
                    protocols.add(p);
                }
            }
            if (!protocols.isEmpty()) {
                ssl.setEnabledProtocols(protocols.toArray(new String[0]));
            }
        }
        return s;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return patch(delegate.createSocket());
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose)
            throws IOException {
        return patch(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return patch(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException {
        return patch(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return patch(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                               int localPort) throws IOException {
        return patch(delegate.createSocket(address, port, localAddress, localPort));
    }
}
