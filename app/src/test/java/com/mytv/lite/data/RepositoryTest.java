package com.mytv.lite.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.mytv.lite.App;
import com.mytv.lite.Prefs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPOutputStream;

/**
 * 端到端验证：本地起一个 HTTP 服务，模拟真实订阅源与节目单，
 * 跑一遍「下载 → 解析 → 合并 → 缓存 → 查询」全链路（API 19 环境）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = App.class)
public class RepositoryTest {

    private ServerSocket server;
    private Thread serverThread;
    private volatile boolean running;
    private String base;

    private static final String LIVE_TXT =
            "央视频道,#genre#\n"
            + "CCTV1,http://replace/a.m3u8#http://replace/b.m3u8\n"
            + "CCTV2,http://replace/c.m3u8\n"
            + "地方频道,#genre#\n"
            + "湖南卫视,http://replace/d.m3u8$线路2\n";

    @Before
    public void setUp() throws Exception {
        Prefs.init(RuntimeEnvironment.getApplication());
        Context ctx = RuntimeEnvironment.getApplication();
        ChannelRepository.clearCache(ctx);
        EpgRepository.clearCache(ctx);

        // 极简 HTTP 服务：不依赖 com.sun.net.httpserver（Android 单元测试的 bootclasspath 里没有）
        server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
        base = "http://127.0.0.1:" + server.getLocalPort();
        running = true;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        final Socket socket = server.accept();
                        handle(socket);
                    } catch (IOException e) {
                        break;
                    }
                }
            }
        }, "test-http");
        serverThread.start();
    }

    private void handle(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String requestLine = reader.readLine();
            String path = "/";
            if (requestLine != null) {
                String[] parts = requestLine.split(" ");
                if (parts.length > 1) {
                    path = parts[1];
                }
            }
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                // 忽略请求头
            }

            byte[] body;
            if (path.startsWith("/e.xml.gz")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                GZIPOutputStream gz = new GZIPOutputStream(bos);
                gz.write(buildEpgXml().getBytes("UTF-8"));
                gz.close();
                body = bos.toByteArray();
            } else if (path.startsWith("/live.txt")) {
                body = LIVE_TXT.replace("http://replace", base).getBytes("UTF-8");
            } else {
                body = new byte[0];
            }

            OutputStream out = socket.getOutputStream();
            String head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @After
    public void tearDown() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
        }
    }

    private String buildEpgXml() {
        long now = System.currentTimeMillis();
        String now1 = fmt(now - 30 * 60 * 1000L);
        String now2 = fmt(now + 30 * 60 * 1000L);
        String now3 = fmt(now + 90 * 60 * 1000L);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<tv>\n"
                + "  <channel id=\"c1\"><display-name lang=\"zh\">CCTV1</display-name></channel>\n"
                + "  <channel id=\"c2\"><display-name>湖南卫视</display-name></channel>\n"
                + "  <programme start=\"" + now1 + "\" stop=\"" + now2 + "\" channel=\"c1\">"
                + "<title lang=\"zh\">新闻联播</title><desc>测试</desc></programme>\n"
                + "  <programme start=\"" + now2 + "\" stop=\"" + now3 + "\" channel=\"c1\">"
                + "<title lang=\"zh\">焦点访谈</title></programme>\n"
                + "  <programme start=\"" + now1 + "\" stop=\"" + now3 + "\" channel=\"c2\">"
                + "<title>快乐大本营</title></programme>\n"
                + "</tv>\n";
    }

    private String fmt(long time) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(time));
    }

    @Test
    public void loadChannelsAndEpg() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();

        List<IptvSource> sources = new ArrayList<IptvSource>();
        sources.add(new IptvSource("测试源", base + "/live.txt", true, false));
        Prefs.saveIptvSources(sources);

        List<EpgSource> epgSources = new ArrayList<EpgSource>();
        epgSources.add(new EpgSource("测试节目单", base + "/e.xml.gz", true));
        Prefs.saveEpgSources(epgSources);

        ChannelRepository.loadSync(ctx, true);

        // 分组与频道
        assertEquals(2, ChannelRepository.groups().size());
        assertEquals(3, ChannelRepository.all().size());

        Channel cctv1 = ChannelRepository.findByName("CCTV1");
        assertNotNull(cctv1);
        assertEquals("CCTV1", cctv1.standardName);
        // 同一个频道两条地址合并成两条线路
        assertEquals(2, cctv1.lineCount());
        assertEquals("01", cctv1.no());

        Channel hntv = ChannelRepository.findByName("湖南卫视");
        assertNotNull(hntv);
        // 带 $线路名 的地址要能正确取到可播放地址
        assertEquals(base + "/d.m3u8", hntv.currentLine().playableUrl());
        assertEquals("线路2", hntv.currentLine().displayName());

        // 节目单（gz 压缩 + 流式解析）
        EpgRepository.loadSync(ctx, true);
        assertTrue(EpgRepository.hasData());

        EpgRepository.Programme now = EpgRepository.current(cctv1);
        assertNotNull(now);
        assertEquals("新闻联播", now.title);

        EpgRepository.Programme next = EpgRepository.next(cctv1);
        assertNotNull(next);
        assertEquals("焦点访谈", next.title);

        assertNotNull(EpgRepository.current(hntv));
        assertEquals("快乐大本营", EpgRepository.current(hntv).title);
    }

    @Test
    public void cacheShouldBeReused() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();

        List<IptvSource> sources = new ArrayList<IptvSource>();
        sources.add(new IptvSource("测试源", base + "/live.txt", true, false));
        Prefs.saveIptvSources(sources);

        ChannelRepository.loadSync(ctx, true);
        int count = ChannelRepository.all().size();
        assertTrue(count > 0);

        File cache = new File(ctx.getFilesDir(), "channels_cache.json");
        assertTrue(cache.exists());

        // 关掉服务器后仍应能用缓存加载（不 force）
        tearDown();

        ChannelRepository.loadSync(ctx, false);
        assertEquals(count, ChannelRepository.all().size());
    }

    @SuppressWarnings("unused")
    private void writeFile(File file, String content) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        out.write(content.getBytes("UTF-8"));
        out.close();
    }
}
