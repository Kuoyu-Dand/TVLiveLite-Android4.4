package com.mytv.lite.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import com.mytv.lite.Prefs;
import com.mytv.lite.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * 节目单（XMLTV）仓库。
 * 老设备内存有限，这里只保留 [昨天, 4 天后] 的节目，并落盘成精简 JSON，
 * 同一天内再次启动直接读缓存，不再解析几十 MB 的 xml。
 */
public final class EpgRepository {

    private static final String CACHE_JSON = "epg_cache.json";
    private static final String DIR = "epg";
    private static final int MAX_PROGRAMMES = 80000;
    private static final long DAY = 24 * 3600 * 1000L;

    public static class Programme {
        public long start;
        public long end;
        public String title = "";

        public Programme() {
        }

        public Programme(long start, long end, String title) {
            this.start = start;
            this.end = end;
            this.title = title;
        }
    }

    private static final Map<String, List<Programme>> MAP = new HashMap<String, List<Programme>>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean loading;
    private static volatile long loadedAt;
    private static volatile String lastError;

    public interface Callback {
        void onResult(String error);
    }

    private EpgRepository() {
    }

    public static boolean hasData() {
        return !MAP.isEmpty();
    }

    public static long loadedAt() {
        return loadedAt;
    }

    public static String lastError() {
        return lastError;
    }

    public static void loadAsync(final Context context, final boolean force, final Callback callback) {
        if (loading) {
            return;
        }
        loading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    loadSync(context, force);
                } catch (Throwable t) {
                    error = t.getMessage() == null ? "节目单加载失败" : t.getMessage();
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
        }, "epg-loader").start();
    }

    static void loadSync(Context context, boolean force) throws Exception {
        if (!Prefs.epgEnabled()) {
            synchronized (MAP) {
                MAP.clear();
            }
            return;
        }

        String today = dayKey(System.currentTimeMillis());
        File json = new File(context.getFilesDir(), CACHE_JSON);

        if (!force && json.exists()) {
            try {
                JSONObject root = new JSONObject(ChannelRepository.readText(json));
                if (today.equals(root.optString("day", ""))) {
                    if (applyJson(root)) {
                        loadedAt = System.currentTimeMillis();
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        List<EpgSource> sources = Prefs.getEpgSources();
        Map<String, List<Programme>> merged = new LinkedHashMap<String, List<Programme>>();
        StringBuilder errors = new StringBuilder();
        int okCount = 0;

        for (EpgSource source : sources) {
            if (!source.enabled || source.url == null || source.url.trim().isEmpty()) {
                continue;
            }
            try {
                File xml = ensureXml(context, source, force);
                Map<String, List<Programme>> single = new HashMap<String, List<Programme>>();
                parseXml(xml, single);
                if (single.isEmpty()) {
                    errors.append(source.displayName()).append(": 未解析到节目\n");
                    continue;
                }
                okCount++;
                for (Map.Entry<String, List<Programme>> e : single.entrySet()) {
                    if (!merged.containsKey(e.getKey())) {
                        merged.put(e.getKey(), e.getValue());
                    }
                }
            } catch (Throwable t) {
                errors.append(source.displayName()).append(": ")
                        .append(t.getMessage() == null ? "加载失败" : t.getMessage()).append("\n");
            }
        }

        if (okCount == 0 && merged.isEmpty()) {
            throw new Exception(errors.length() > 0 ? errors.toString().trim() : "没有启用节目单源");
        }

        synchronized (MAP) {
            MAP.clear();
            MAP.putAll(merged);
        }
        loadedAt = System.currentTimeMillis();
        saveJson(json, today);
    }

    private static boolean applyJson(JSONObject root) {
        try {
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return false;
            }
            Map<String, List<Programme>> map = new HashMap<String, List<Programme>>();
            java.util.Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONArray arr = data.optJSONArray(key);
                if (arr == null) {
                    continue;
                }
                List<Programme> list = new ArrayList<Programme>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray item = arr.optJSONArray(i);
                    if (item == null || item.length() < 3) {
                        continue;
                    }
                    list.add(new Programme(item.optLong(0), item.optLong(1),
                            item.optString(2, "")));
                }
                map.put(key, list);
            }
            if (map.isEmpty()) {
                return false;
            }
            synchronized (MAP) {
                MAP.clear();
                MAP.putAll(map);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void saveJson(File file, String day) {
        try {
            JSONObject root = new JSONObject();
            root.put("day", day);
            JSONObject data = new JSONObject();
            synchronized (MAP) {
                for (Map.Entry<String, List<Programme>> e : MAP.entrySet()) {
                    JSONArray arr = new JSONArray();
                    for (Programme p : e.getValue()) {
                        JSONArray item = new JSONArray();
                        item.put(p.start);
                        item.put(p.end);
                        item.put(p.title);
                        arr.put(item);
                    }
                    data.put(e.getKey(), arr);
                }
            }
            root.put("data", data);
            ChannelRepository.writeText(file, root.toString());
        } catch (Throwable ignored) {
        }
    }

    private static File ensureXml(Context context, EpgSource source, boolean force) throws Exception {
        if (source.local) {
            File local = new File(source.url);
            if (!local.exists() || !local.canRead() || local.length() <= 0) {
                throw new Exception("本地节目单文件不存在");
            }
            return local;
        }
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("无法创建目录");
        }
        File f = new File(dir, "epg_" + Integer.toHexString(source.url.hashCode()) + ".xml");
        String today = dayKey(System.currentTimeMillis());
        boolean fresh = f.exists() && f.length() > 1024 && today.equals(Prefs.getString(
                "epg_day_" + Integer.toHexString(source.url.hashCode()), ""));
        if (force || !fresh) {
            File tmp = new File(f.getAbsolutePath() + ".dl");
            Http.download(source.url.trim(), Prefs.ua(), 60000, tmp);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            if (!tmp.renameTo(f)) {
                throw new Exception("节目单写入失败");
            }
            Prefs.putString("epg_day_" + Integer.toHexString(source.url.hashCode()), today);
        }
        return f;
    }

    // ---------------- XMLTV 解析 ----------------

    private static void parseXml(File file, Map<String, List<Programme>> out) throws Exception {
        InputStream raw = new BufferedInputStream(new FileInputStream(file), 32 * 1024);
        InputStream in = raw;
        try {
            raw.mark(4);
            int b1 = raw.read();
            int b2 = raw.read();
            raw.reset();
            if (b1 == 0x1f && b2 == 0x8b) {
                in = new GZIPInputStream(raw, 32 * 1024);
            }

            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(in, null);

            long now = System.currentTimeMillis();
            long minStart = now - DAY;
            long maxStart = now + 4 * DAY;

            Map<String, String> idToKey = new HashMap<String, String>();
            String curId = null;
            String curKey = null;
            int total = 0;

            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if ("channel".equals(tag)) {
                        curId = p.getAttributeValue(null, "id");
                        curKey = null;
                    } else if ("display-name".equals(tag)) {
                        String text = safeNextText(p);
                        if (curKey == null && text != null && !text.trim().isEmpty()) {
                            curKey = ChannelAlias.key(text);
                        }
                    } else if ("programme".equals(tag)) {
                        String channelId = p.getAttributeValue(null, "channel");
                        long start = parseTime(p.getAttributeValue(null, "start"));
                        long end = parseTime(p.getAttributeValue(null, "stop"));
                        String title = null;

                        while (true) {
                            int e2 = p.next();
                            if (e2 == XmlPullParser.END_DOCUMENT) {
                                break;
                            }
                            if (e2 == XmlPullParser.START_TAG && "title".equals(p.getName())
                                    && title == null) {
                                title = safeNextText(p);
                            } else if (e2 == XmlPullParser.END_TAG
                                    && "programme".equals(p.getName())) {
                                break;
                            }
                        }

                        if (title != null && start > 0 && end > start
                                && start >= minStart && start <= maxStart) {
                            String key = idToKey.get(channelId);
                            if (key != null) {
                                List<Programme> list = out.get(key);
                                if (list == null) {
                                    list = new ArrayList<Programme>(64);
                                    out.put(key, list);
                                }
                                if (list.size() < 800) {
                                    list.add(new Programme(start, end, title.trim()));
                                    total++;
                                }
                            }
                        }
                        if (total > MAX_PROGRAMMES) {
                            break;
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if ("channel".equals(p.getName()) && curId != null && curKey != null) {
                        idToKey.put(curId, curKey);
                        curId = null;
                        curKey = null;
                    }
                }
                event = p.next();
            }

            for (List<Programme> list : out.values()) {
                Collections.sort(list, new Comparator<Programme>() {
                    @Override
                    public int compare(Programme a, Programme b) {
                        if (a.start == b.start) {
                            return 0;
                        }
                        return a.start < b.start ? -1 : 1;
                    }
                });
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String safeNextText(XmlPullParser p) {
        try {
            return p.nextText();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long parseTime(String value) {
        if (value == null) {
            return 0;
        }
        String v = value.trim();
        if (v.length() < 14) {
            return 0;
        }
        String pattern = v.length() > 14 ? "yyyyMMddHHmmss Z" : "yyyyMMddHHmmss";
        try {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
            f.setTimeZone(TimeZone.getDefault());
            Date d = f.parse(v);
            return d == null ? 0 : d.getTime();
        } catch (Throwable t) {
            try {
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                Date d = f.parse(v.substring(0, 14));
                return d == null ? 0 : d.getTime();
            } catch (Throwable t2) {
                return 0;
            }
        }
    }

    // ---------------- 查询 ----------------

    private static String dayKey(long time) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(time));
    }

    public static List<Programme> programmes(Channel channel) {
        if (channel == null) {
            return Collections.emptyList();
        }
        List<Programme> list;
        synchronized (MAP) {
            list = MAP.get(channel.epgKey());
        }
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        long startOfDay = startOfToday();
        long endOfDay = startOfDay + DAY;
        List<Programme> out = new ArrayList<Programme>();
        for (Programme p : list) {
            if (p.end > startOfDay && p.start < endOfDay + DAY) {
                out.add(p);
            }
        }
        return out;
    }

    public static List<Programme> allProgrammes(Channel channel) {
        if (channel == null) {
            return Collections.emptyList();
        }
        synchronized (MAP) {
            List<Programme> list = MAP.get(channel.epgKey());
            return list == null ? Collections.<Programme>emptyList() : list;
        }
    }

    public static Programme current(Channel channel) {
        long now = System.currentTimeMillis();
        for (Programme p : allProgrammes(channel)) {
            if (p.start <= now && p.end > now) {
                return p;
            }
        }
        return null;
    }

    public static Programme next(Channel channel) {
        long now = System.currentTimeMillis();
        for (Programme p : allProgrammes(channel)) {
            if (p.start > now) {
                return p;
            }
        }
        return null;
    }

    public static long startOfToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static void clearCache(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        File json = new File(context.getFilesDir(), CACHE_JSON);
        if (json.exists()) {
            //noinspection ResultOfMethodCallIgnored
            json.delete();
        }
        synchronized (MAP) {
            MAP.clear();
        }
    }
}
