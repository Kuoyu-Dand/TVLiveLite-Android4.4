package com.mytv.lite.data;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 节目单（EPG）源，支持 xmltv 的 .xml 与 .xml.gz
 */
public class EpgSource {

    public String name = "";
    public String url = "";
    public boolean enabled = true;
    /** 本地文件（从 U 盘等导入后复制到应用私有目录） */
    public boolean local = false;

    public EpgSource() {
    }

    public EpgSource(String name, String url, boolean enabled) {
        this.name = name;
        this.url = url;
        this.enabled = enabled;
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

    public static EpgSource fromJson(JSONObject o) {
        EpgSource s = new EpgSource();
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
