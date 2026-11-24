package com.mytv.lite;

import android.content.Context;
import android.content.SharedPreferences;

import com.mytv.lite.data.EpgSource;
import com.mytv.lite.data.IptvSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 统一配置存储。所有数据都放在 SharedPreferences，避免老设备上引入数据库开销。
 */
public final class Prefs {

    private static final String FILE = "mytv_lite_prefs";

    public static final String K_UA = "ua";
    public static final String K_KERNEL = "kernel";           // exo / sys
    public static final String K_DECODE = "decode";           // auto / sw
    public static final String K_ASPECT = "aspect";           // fit / fill / zoom
    public static final String K_TIMEOUT = "timeout";          // ms
    public static final String K_CACHE_HOURS = "cache_hours";
    public static final String K_BOOT = "boot";
    public static final String K_SHOW_LOGO = "show_logo";
    public static final String K_EPG_ON = "epg_on";
    public static final String K_AUTOPLAY = "autoplay_last";   // 打开软件直进上次频道
    public static final String K_CARE = "care_mode";           // 关怀模式：大字号 + 界面从简
    public static final String K_RENDER = "render_mode";       // auto / surface / texture
    public static final String K_MAX_QUALITY = "max_quality";  // auto / 1080 / 720 / 480
    public static final String K_LAST_CHANNEL = "last_channel";
    public static final String K_LAST_GROUP = "last_group";

    public static final String K_IPTV_SOURCES = "iptv_sources";
    public static final String K_EPG_SOURCES = "epg_sources";
    public static final String K_FAVORITES = "favorites";
    public static final String K_HISTORY = "history";

    private static SharedPreferences sp;

    private Prefs() {
    }

    public static void init(Context context) {
        if (sp == null) {
            sp = context.getApplicationContext()
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences s() {
        if (sp == null) {
            init(App.ctx());
        }
        return sp;
    }

    public static String getString(String key, String def) {
        return s().getString(key, def);
    }

    public static void putString(String key, String value) {
        s().edit().putString(key, value).apply();
    }

    public static int getInt(String key, int def) {
        return s().getInt(key, def);
    }

    public static void putInt(String key, int value) {
        s().edit().putInt(key, value).apply();
    }

    public static boolean getBool(String key, boolean def) {
        return s().getBoolean(key, def);
    }

    public static void putBool(String key, boolean value) {
        s().edit().putBoolean(key, value).apply();
    }

    // ---------------- 订阅源 ----------------

    public static List<IptvSource> getIptvSources() {
        List<IptvSource> list = new ArrayList<IptvSource>();
        try {
            String json = s().getString(K_IPTV_SOURCES, null);
            if (json == null) {
                // 首次安装：给一个占位源，提示用户自行添加
                IptvSource placeholder = new IptvSource();
                placeholder.name = "示例源（请改成自己的地址）";
                placeholder.url = "http://1.2.3.4/live.m3u";
                placeholder.enabled = false;
                list.add(placeholder);
                saveIptvSources(list);
                return list;
            }
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(IptvSource.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void saveIptvSources(List<IptvSource> list) {
        JSONArray arr = new JSONArray();
        for (IptvSource s : list) {
            arr.put(s.toJson());
        }
        s().edit().putString(K_IPTV_SOURCES, arr.toString()).apply();
    }

    public static List<EpgSource> getEpgSources() {
        List<EpgSource> list = new ArrayList<EpgSource>();
        try {
            String json = s().getString(K_EPG_SOURCES, null);
            if (json == null) {
                EpgSource def = new EpgSource();
                def.name = "默认节目单 fanmingming";
                def.url = "https://live.fanmingming.com/e.xml";
                def.enabled = true;
                list.add(def);
                saveEpgSources(list);
                return list;
            }
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(EpgSource.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void saveEpgSources(List<EpgSource> list) {
        JSONArray arr = new JSONArray();
        for (EpgSource s : list) {
            arr.put(s.toJson());
        }
        s().edit().putString(K_EPG_SOURCES, arr.toString()).apply();
    }

    // ---------------- 字符串集合 ----------------

    public static LinkedHashSet<String> getSet(String key) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        try {
            String json = s().getString(key, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                set.add(arr.getString(i));
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    public static void putSet(String key, LinkedHashSet<String> set) {
        JSONArray arr = new JSONArray();
        for (String v : set) {
            arr.put(v);
        }
        s().edit().putString(key, arr.toString()).apply();
    }

    public static boolean isFavorite(String standardName) {
        return getSet(K_FAVORITES).contains(standardName);
    }

    public static void toggleFavorite(String standardName) {
        LinkedHashSet<String> set = getSet(K_FAVORITES);
        if (!set.remove(standardName)) {
            set.add(standardName);
        }
        putSet(K_FAVORITES, set);
    }

    public static void pushHistory(String standardName) {
        LinkedHashSet<String> set = getSet(K_HISTORY);
        set.remove(standardName);
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        out.add(standardName);
        for (String s : set) {
            if (out.size() >= 12) {
                break;
            }
            out.add(s);
        }
        putSet(K_HISTORY, out);
    }

    // ---------------- 便捷读取 ----------------

    public static String ua() {
        return getString(K_UA, "");
    }

    public static String kernel() {
        return getString(K_KERNEL, "exo");
    }

    public static boolean softwareDecode() {
        return "sw".equals(getString(K_DECODE, "auto"));
    }

    public static String aspect() {
        return getString(K_ASPECT, "fit");
    }

    /**
     * 画面渲染方式：auto / surface / texture。
     * 少数 4.1~4.3 的老盒子硬解能出声但 SurfaceView 上不出画面，
     * 换成 TextureView 走 GPU 合成就能显示，所以给它留一个开关。
     */
    public static String renderMode() {
        String mode = getString(K_RENDER, "auto");
        if (!"surface".equals(mode) && !"texture".equals(mode)) {
            return "auto";
        }
        return mode;
    }

    public static void setRenderMode(String mode) {
        putString(K_RENDER, "surface".equals(mode) || "texture".equals(mode) ? mode : "auto");
    }

    // ---------------- 最高画质 ----------------

    /**
     * 最高画质：auto / 1080 / 720 / 480。
     * 4.1~4.3 的老盒子硬解器很多只吃到 720p，喂 1080p 的源会「有声无画」，
     * 而 HLS 多码率源默认又倾向选最高码率，所以在解码这一层给它设个上限。
     */
    public static String maxQuality() {
        String q = getString(K_MAX_QUALITY, "auto");
        if (!"1080".equals(q) && !"720".equals(q) && !"480".equals(q)) {
            return "auto";
        }
        return q;
    }

    public static void setMaxQuality(String quality) {
        String q = quality;
        if (!"1080".equals(q) && !"720".equals(q) && !"480".equals(q)) {
            q = "auto";
        }
        putString(K_MAX_QUALITY, q);
    }

    /**
     * 允许的最大视频高度，0 表示不限制。
     * auto：Android 4.4 以下（API 16~18）默认按 720p 封顶——这段版本上的盒子普遍是
     * 单核/双核 A7 级别，1080p 高码率基本是黑屏或幻灯片；4.4 及以上交给系统判断。
     */
    public static int maxVideoHeight() {
        return maxVideoHeightFor(maxQuality(), android.os.Build.VERSION.SDK_INT);
    }

    /** 纯函数版本，方便单测覆盖低版本分支（Robolectric 跑不了 API 18 及以下）。 */
    public static int maxVideoHeightFor(String quality, int sdkInt) {
        if ("1080".equals(quality)) {
            return 1080;
        }
        if ("720".equals(quality)) {
            return 720;
        }
        if ("480".equals(quality)) {
            return 480;
        }
        return sdkInt < 19 ? 720 : 0;
    }

    /** 与高度上限配套的码率上限（bps），0 表示不限制。 */
    public static int maxVideoBitrate() {
        int h = maxVideoHeight();
        if (h == 0) {
            return 0;
        }
        if (h <= 480) {
            return 2_000_000;
        }
        if (h <= 720) {
            return 4_000_000;
        }
        return 8_000_000;
    }

    public static int timeoutMs() {
        return getInt(K_TIMEOUT, 12000);
    }

    public static int cacheHours() {
        return getInt(K_CACHE_HOURS, 24);
    }

    public static boolean bootAutoStart() {
        return getBool(K_BOOT, false);
    }

    public static boolean showLogo() {
        return getBool(K_SHOW_LOGO, true);
    }

    public static boolean epgEnabled() {
        return getBool(K_EPG_ON, true);
    }

    // ---------------- 直进播放 / 关怀模式 ----------------

    /** 打开软件后直接播放上次关闭时的频道（跳过首页）。 */
    public static boolean autoPlayLast() {
        return getBool(K_AUTOPLAY, false);
    }

    public static void setAutoPlayLast(boolean value) {
        putBool(K_AUTOPLAY, value);
    }

    /** 关怀模式：照顾老年视力，字号放大、界面从简。 */
    public static boolean careMode() {
        return getBool(K_CARE, false);
    }

    public static void setCareMode(boolean value) {
        putBool(K_CARE, value);
    }

    /** 上次播放过的频道名（关闭播放器时写入），没有则返回空串。 */
    public static String lastChannel() {
        String name = getString(K_LAST_CHANNEL, "");
        return name == null ? "" : name.trim();
    }

    public static boolean hasLastChannel() {
        return lastChannel().length() > 0;
    }

    /** 是否至少配置了一个可用的直播源——没有源时直进播放只会弹出错误，不如留在首页。 */
    public static boolean hasEnabledIptvSource() {
        for (IptvSource source : getIptvSources()) {
            if (source.enabled && source.url != null && !source.url.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
