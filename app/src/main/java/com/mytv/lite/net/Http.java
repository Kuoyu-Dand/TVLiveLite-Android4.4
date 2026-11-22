package com.mytv.lite.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 网络访问封装。使用 OkHttp 3.12（支持 API 9+），并强制开启 TLS1.2。
 */
public final class Http {

    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 4.4.2) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/33.0.0.0 Safari/537.36";

    private static volatile OkHttpClient base;

    private Http() {
    }

    public static OkHttpClient client(int timeoutMs) {
        if (base == null) {
            synchronized (Http.class) {
                if (base == null) {
                    OkHttpClient.Builder b = new OkHttpClient.Builder()
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .retryOnConnectionFailure(true)
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS)
                            .writeTimeout(20, TimeUnit.SECONDS);
                    X509TrustManager tm = Tls12SocketFactory.defaultTrustManager();
                    javax.net.ssl.SSLSocketFactory f = Tls12SocketFactory.create(tm);
                    if (f != null) {
                        b.sslSocketFactory(f, tm);
                    }
                    base = b.build();
                }
            }
        }
        OkHttpClient.Builder b = base.newBuilder();
        if (timeoutMs > 0) {
            b.connectTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            b.readTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        }
        return b.build();
    }

    private static Request build(String url, String ua) {
        Request.Builder rb = new Request.Builder()
                .url(url)
                .header("User-Agent", (ua == null || ua.trim().isEmpty()) ? DEFAULT_UA : ua)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate");
        return rb.build();
    }

    public static String getString(String url, String ua, int timeoutMs) throws IOException {
        Response resp = null;
        try {
            resp = client(timeoutMs).newCall(build(url, ua)).execute();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code());
            }
            ResponseBody body = resp.body();
            if (body == null) {
                throw new IOException("响应为空");
            }
            return body.string();
        } finally {
            if (resp != null) {
                resp.close();
            }
        }
    }

    public static byte[] getBytes(String url, String ua, int timeoutMs) throws IOException {
        Response resp = null;
        try {
            resp = client(timeoutMs).newCall(build(url, ua)).execute();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code());
            }
            ResponseBody body = resp.body();
            if (body == null) {
                throw new IOException("响应为空");
            }
            return body.bytes();
        } finally {
            if (resp != null) {
                resp.close();
            }
        }
    }

    /**
     * 下载到文件，用于节目单（可能是几十 MB 的 xml）。
     */
    public static void download(String url, String ua, int timeoutMs, File out)
            throws IOException {
        Response resp = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            resp = client(timeoutMs).newCall(build(url, ua)).execute();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code());
            }
            ResponseBody body = resp.body();
            if (body == null) {
                throw new IOException("响应为空");
            }
            in = new BufferedInputStream(body.byteStream(), 32 * 1024);
            File tmp = new File(out.getAbsolutePath() + ".tmp");
            fos = new FileOutputStream(tmp);
            byte[] buf = new byte[32 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
            fos.flush();
            fos.close();
            fos = null;
            if (out.exists()) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            if (!tmp.renameTo(out)) {
                throw new IOException("写入缓存失败");
            }
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (IOException ignored) {
            }
            if (resp != null) {
                resp.close();
            }
        }
    }

    public static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) {
            bos.write(buf, 0, len);
        }
        return new String(bos.toByteArray(), "UTF-8");
    }
}
