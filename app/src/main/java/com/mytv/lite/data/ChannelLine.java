package com.mytv.lite.data;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 同一频道的一条线路
 */
public class ChannelLine {

    public String url = "";
    public String userAgent;
    public String name;

    public ChannelLine() {
    }

    public ChannelLine(String url) {
        this.url = url;
    }

    public ChannelLine(String url, String userAgent) {
        this.url = url;
        this.userAgent = userAgent;
    }

    /**
     * 部分源会在地址后用 “$” 附加线路名，例如 http://xxx.m3u8$线路1
     * 另外有些地址以 “?” 结尾，直接请求会被服务端丢弃参数，这里补一个字符。
     */
    public String playableUrl() {
        String u = url;
        int idx = u.indexOf('$');
        if (idx > 0) {
            u = u.substring(0, idx);
        }
        if (u.endsWith("?")) {
            u = u + "t";
        }
        return u.trim();
    }

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }
        int idx = url.indexOf('$');
        if (idx > 0 && idx < url.length() - 1) {
            return url.substring(idx + 1);
        }
        return "线路";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("u", url);
            if (userAgent != null) {
                o.put("ua", userAgent);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static ChannelLine fromJson(JSONObject o) {
        ChannelLine l = new ChannelLine();
        l.url = o.optString("u", "");
        l.userAgent = o.has("ua") ? o.optString("ua", null) : null;
        return l;
    }
}
