package com.mytv.lite.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.mytv.lite.Prefs;
import com.mytv.lite.net.Http;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 频道仓库：拉取 / 缓存 / 解析所有启用的订阅源，合并成 分组→频道→线路 三级结构。
 * 解析结果落盘，老设备冷启动时不用每次都跑正则。
 */
public final class ChannelRepository {

    private static final String CACHE_FILE = "channels_cache.json";
    private static final int MAX_LINES_PER_CHANNEL = 8;

    private static final Object LOCK = new Object();
    private static final List<ChannelGroup> GROUPS = new ArrayList<ChannelGroup>();
    private static final List<Channel> ALL = new ArrayList<Channel>();
    private static final Map<String, Channel> BY_KEY = new LinkedHashMap<String, Channel>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean loading;
    private static volatile long loadedAt;
    private static volatile String lastError;

    public interface Callback {
        void onResult(String error);
    }

    private ChannelRepository() {
    }

    public static List<ChannelGroup> groups() {
        synchronized (LOCK) {
            return new ArrayList<ChannelGroup>(GROUPS);
        }
    }

    public static List<Channel> all() {
        synchronized (LOCK) {
            return new ArrayList<Channel>(ALL);
        }
    }

    public static boolean isLoading() {
        return loading;
    }

    public static long loadedAt() {
        return loadedAt;
    }

    public static String lastError() {
        return lastError;
    }

    public static Channel findByKey(String key) {
        synchronized (LOCK) {
            return BY_KEY.get(key);
        }
    }

    public static Channel findByName(String name) {
        if (name == null) {
            return null;
        }
        return findByKey(ChannelAlias.key(name));
    }

    public static Channel first() {
        synchronized (LOCK) {
            return ALL.isEmpty() ? null : ALL.get(0);
        }
    }

    /**
     * 收藏频道（按标准名匹配，跨源也能对上）
     */
    public static List<Channel> favorites() {
        List<Channel> out = new ArrayList<Channel>();
        java.util.LinkedHashSet<String> fav = Prefs.getSet(Prefs.K_FAVORITES);
        for (String key : fav) {
            Channel c = findByKey(key);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    public static List<Channel> history() {
        List<Channel> out = new ArrayList<Channel>();
        java.util.LinkedHashSet<String> his = Prefs.getSet(Prefs.K_HISTORY);
        for (String key : his) {
            Channel c = findByKey(key);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    public static void loadAsync(final Context context, final boolean force, final Callback callback) {
        synchronized (LOCK) {
            if (loading) {
                return;
            }
            loading = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    loadSync(context, force);
                } catch (Throwable t) {
                    error = t.getMessage() == null ? "加载订阅源失败" : t.getMessage();
                }
                lastError = error;
                loading = false;
                if (callback != null) {
                    final String e = error;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResult(e);
                        }
                    });
                }
            }
        }, "channel-loader").start();
    }

    public static void loadSync(Context context, boolean force) throws Exception {
        List<IptvSource> sources = Prefs.getIptvSources();
        final List<IptvSource> enabled = new ArrayList<IptvSource>();
        for (IptvSource s : sources) {
            if (s.enabled && s.url != null && !s.url.trim().isEmpty()) {
                enabled.add(s);
            }
        }

        File cacheFile = new File(context.getFilesDir(), CACHE_FILE);
        String sig = signature(enabled);
        long now = System.currentTimeMillis();

        if (!force && cacheFile.exists()) {
            try {
                String json = readText(cacheFile);
                JSONObject o = new JSONObject(json);
                String cachedSig = o.optString("sig", "");
                long ts = o.optLong("ts", 0);
                long ttl = Math.max(1, Prefs.cacheHours()) * 3600000L;
                if (cachedSig.equals(sig) && (enabled.isEmpty() || now - ts < ttl)) {
                    if (applyCache(o)) {
                        loadedAt = ts;
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            List<ChannelGroup> groups = build(context, enabled);
            synchronized (LOCK) {
                apply(groups);
            }
            loadedAt = System.currentTimeMillis();
            saveCache(cacheFile, sig, groups);
            lastError = null;
        } catch (Throwable t) {
            // 网络挂了优先用上一次的结果，避免老设备上白屏
            boolean restored = false;
            if (cacheFile.exists()) {
                try {
                    restored = applyCache(new JSONObject(readText(cacheFile)));
                } catch (Throwable ignored) {
                }
            }
            if (!restored) {
                synchronized (LOCK) {
                    GROUPS.clear();
                    ALL.clear();
                    BY_KEY.clear();
                }
                throw new Exception(describe(t));
            }
        }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return "加载订阅源失败，请检查网络或订阅地址";
        }
        return msg;
    }

    private static List<ChannelGroup> build(Context context, List<IptvSource> enabled)
            throws IOException {
        Map<String, ChannelGroup> groupMap = new LinkedHashMap<String, ChannelGroup>();
        Map<String, Map<String, Channel>> index = new LinkedHashMap<String, Map<String, Channel>>();
        StringBuilder errors = new StringBuilder();

        for (IptvSource source : enabled) {
            String raw;
            try {
                raw = fetchRaw(context, source);
            } catch (Exception e) {
                errors.append(source.displayName()).append(": ")
                        .append(e.getMessage() == null ? "拉取失败" : e.getMessage()).append("\n");
                continue;
            }
            if (raw == null || raw.trim().isEmpty()) {
                errors.append(source.displayName()).append(": 内容为空\n");
                continue;
            }

            List<IptvParser.Item> items = IptvParser.parse(raw);
            for (IptvParser.Item it : items) {
                if (it.name == null || it.name.trim().isEmpty() || it.url == null
                        || it.url.trim().isEmpty()) {
                    continue;
                }
                String groupName = (it.groupName == null || it.groupName.trim().isEmpty())
                        ? "其他" : it.groupName.trim();

                ChannelGroup group = groupMap.get(groupName);
                if (group == null) {
                    group = new ChannelGroup(groupName);
                    groupMap.put(groupName, group);
                    index.put(groupName, new LinkedHashMap<String, Channel>());
                }
                Map<String, Channel> channels = index.get(groupName);
                String key = it.name.trim().toLowerCase();

                Channel channel = channels.get(key);
                if (channel == null) {
                    channel = new Channel(it.name.trim());
                    channel.groupName = groupName;
                    channel.standardName = ChannelAlias.standard(it.name);
                    channel.epgName = ChannelAlias.standard(
                            it.epgName == null || it.epgName.trim().isEmpty()
                                    ? it.name : it.epgName);
                    channel.logo = it.logo;
                    channels.put(key, channel);
                    group.channels.add(channel);
                } else if ((channel.logo == null || channel.logo.trim().isEmpty())
                        && it.logo != null && !it.logo.trim().isEmpty()) {
                    channel.logo = it.logo;
                }

                if (channel.lines.size() >= MAX_LINES_PER_CHANNEL) {
                    continue;
                }
                boolean dup = false;
                for (ChannelLine l : channel.lines) {
                    if (l.url.equals(it.url)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    channel.lines.add(new ChannelLine(it.url, it.ua));
                }
            }
        }

        List<ChannelGroup> groups = new ArrayList<ChannelGroup>();
        for (ChannelGroup g : groupMap.values()) {
            if (!g.channels.isEmpty()) {
                groups.add(g);
            }
        }
        if (groups.isEmpty()) {
            String detail = errors.length() > 0 ? errors.toString().trim() : "没有解析到任何频道";
            throw new IOException(detail);
        }
        return groups;
    }

    private static void apply(List<ChannelGroup> groups) {
        GROUPS.clear();
        ALL.clear();
        BY_KEY.clear();
        int index = 0;
        for (ChannelGroup g : groups) {
            GROUPS.add(g);
            for (Channel c : g.channels) {
                c.groupName = g.name;
                c.index = index++;
                ALL.add(c);
                String key = ChannelAlias.key(c.standardName);
                if (!BY_KEY.containsKey(key)) {
                    BY_KEY.put(key, c);
                }
            }
        }
    }

    private static String signature(List<IptvSource> sources) {
        StringBuilder sb = new StringBuilder();
        for (IptvSource s : sources) {
            sb.append(s.local ? "L:" : "R:").append(s.url).append('|');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static String fetchRaw(Context context, IptvSource source) throws Exception {
        if (source.local) {
            File f = new File(source.url);
            if (!f.exists() || !f.canRead()) {
                throw new IOException("本地文件不存在");
            }
            return readText(f);
        }

        File dir = new File(context.getFilesDir(), "src_cache");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建缓存目录");
        }
        File cache = new File(dir, "src_" + Integer.toHexString(source.url.hashCode()) + ".txt");
        long ttl = Math.max(1, Prefs.cacheHours()) * 3600000L;
        boolean expired = !cache.exists()
                || System.currentTimeMillis() - cache.lastModified() > ttl;

        if (!expired) {
            String cached = readText(cache);
            if (cached != null && !cached.trim().isEmpty()) {
                return cached;
            }
        }
        try {
            String data = Http.getString(source.url.trim(), Prefs.ua(), 30000);
            if (data != null && !data.trim().isEmpty()) {
                writeText(cache, data);
                return data;
            }
            throw new IOException("内容为空");
        } catch (Exception e) {
            if (cache.exists()) {
                String cached = readText(cache);
                if (cached != null && !cached.trim().isEmpty()) {
                    return cached;
                }
            }
            throw e;
        }
    }

    // ---------------- 缓存读写 ----------------

    private static boolean applyCache(JSONObject o) {
        try {
            JSONArray arr = o.optJSONArray("groups");
            if (arr == null) {
                return false;
            }
            List<ChannelGroup> groups = new ArrayList<ChannelGroup>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject go = arr.optJSONObject(i);
                if (go == null) {
                    continue;
                }
                ChannelGroup g = new ChannelGroup(go.optString("name", "其他"));
                JSONArray cs = go.optJSONArray("channels");
                if (cs != null) {
                    for (int j = 0; j < cs.length(); j++) {
                        JSONObject co = cs.optJSONObject(j);
                        if (co != null) {
                            g.channels.add(Channel.fromJson(co));
                        }
                    }
                }
                groups.add(g);
            }
            apply(groups);
            return !ALL.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void saveCache(File file, String sig, List<ChannelGroup> groups) {
        try {
            JSONObject root = new JSONObject();
            root.put("ts", System.currentTimeMillis());
            root.put("sig", sig);
            JSONArray arr = new JSONArray();
            for (ChannelGroup g : groups) {
                JSONObject go = new JSONObject();
                go.put("name", g.name);
                JSONArray cs = new JSONArray();
                for (Channel c : g.channels) {
                    cs.put(c.toJson());
                }
                go.put("channels", cs);
                arr.put(go);
            }
            root.put("groups", arr);
            writeText(file, root.toString());
        } catch (Throwable ignored) {
        }
    }

    public static void clearCache(Context context) {
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        File dir = new File(context.getFilesDir(), "src_cache");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        synchronized (LOCK) {
            GROUPS.clear();
            ALL.clear();
            BY_KEY.clear();
        }
    }

    static String readText(File file) throws IOException {
        byte[] data = readBytes(file, 64L * 1024 * 1024);
        if (data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            data = gunzip(data);
        }
        return decodeText(data);
    }

    private static byte[] readBytes(File file, long cap) throws IOException {
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(
                    (int) Math.min(Math.max(file.length(), 4096), cap));
            byte[] buf = new byte[16 * 1024];
            int len;
            long total = 0;
            while ((len = in.read(buf)) > 0) {
                total += len;
                if (total > cap) {
                    break;
                }
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 本地/缓存文件可能是 gz 压缩的（有些源直接给 .txt.gz） */
    private static byte[] gunzip(byte[] data) {
        java.util.zip.GZIPInputStream gz = null;
        try {
            gz = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(data), 32 * 1024);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 3);
            byte[] buf = new byte[32 * 1024];
            int len;
            long total = 0;
            while ((len = gz.read(buf)) > 0) {
                total += len;
                if (total > 64L * 1024 * 1024) {
                    break;
                }
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return data;
        } finally {
            if (gz != null) {
                try {
                    gz.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 老盒子上的 txt 源大量是 GBK 编码，直接按 UTF-8 解会整片乱码（甚至频道名都匹配不上）。
     * 这里先严格试 UTF-8，失败再退回 GB18030（GBK 的超集）。
     */
    static String decodeText(byte[] data) {
        int offset = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        try {
            java.nio.charset.CharsetDecoder decoder = java.nio.charset.Charset
                    .forName("UTF-8").newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(data, offset, data.length - offset))
                    .toString();
        } catch (Throwable ignored) {
        }
        try {
            return new String(data, offset, data.length - offset, "GB18030");
        } catch (Throwable ignored) {
        }
        return new String(data, offset, data.length - offset);
    }

    static void writeText(File file, String text) throws IOException {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
