package com.mytv.lite.data;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 直播订阅源
 */
public class IptvSource {

    public String name = "";
    public String url = "";
    public boolean enabled = true;
    /** 本地文件（导入后复制到应用私有目录） */
    public boolean local = false;

    public IptvSource() {
    }

    public IptvSource(String name, String url, boolean enabled, boolean local) {
        this.name = name;
        this.url = url;
        this.enabled = enabled;
        this.local = local;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", name);
            o.put("url", url);
            o.put("enabled", enabled);
            o.put("local", local);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static IptvSource fromJson(JSONObject o) {
        IptvSource s = new IptvSource();
        s.name = o.optString("name", "");
        s.url = o.optString("url", "");
        s.enabled = o.optBoolean("enabled", true);
        s.local = o.optBoolean("local", false);
        return s;
    }

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }
        return url;
    }
}
