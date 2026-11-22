package com.mytv.lite.data;

import android.content.Context;

import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 频道名标准化：把 “CCTV-1 综合HD”“CCTV1高清” 之类统一成 “CCTV1”，
 * 这样节目单和台标才能正确匹配。规则表来自 assets/channel_name_alias.json（沿用上游开源数据）。
 */
public final class ChannelAlias {

    private static final Map<String, String> REVERSE = new HashMap<String, String>();
    private static final List<String> SUFFIX = new ArrayList<String>();
    private static final LruCache<String, String> CACHE = new LruCache<String, String>(512);
    private static boolean loaded;

    private ChannelAlias() {
    }

    public static synchronized void init(Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        InputStream in = null;
        try {
            in = context.getAssets().open("channel_name_alias.json");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            JSONObject obj = new JSONObject(new String(bos.toByteArray(), "UTF-8"));

            JSONArray suffix = obj.optJSONArray("__suffix");
            if (suffix != null) {
                for (int i = 0; i < suffix.length(); i++) {
                    String s = suffix.optString(i, "");
                    if (!s.isEmpty()) {
                        SUFFIX.add(s);
                    }
                }
            }

            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String standard = keys.next();
                if ("__suffix".equals(standard)) {
                    continue;
                }
                REVERSE.put(norm(standard), standard);
                JSONArray aliases = obj.optJSONArray(standard);
                if (aliases == null) {
                    continue;
                }
                for (int i = 0; i < aliases.length(); i++) {
                    String alias = aliases.optString(i, "");
                    if (!alias.isEmpty()) {
                        REVERSE.put(norm(alias), standard);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static String standard(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String cached = CACHE.get(trimmed);
        if (cached != null) {
            return cached;
        }

        String base = trimmed;
        for (int i = 0; i < SUFFIX.size(); i++) {
            String s = SUFFIX.get(i);
            if (base.length() > s.length() && base.endsWith(s)) {
                base = base.substring(0, base.length() - s.length()).trim();
            }
        }
        String hit = REVERSE.get(norm(base));
        String out = hit != null ? hit : base;
        CACHE.put(trimmed, out);
        return out;
    }

    public static String key(String name) {
        return norm(standard(name));
    }

    private static String norm(String s) {
        return s.trim().toLowerCase(Locale.getDefault());
    }
}
