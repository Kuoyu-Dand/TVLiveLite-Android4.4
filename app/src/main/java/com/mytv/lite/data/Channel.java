package com.mytv.lite.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道（同一名称下的多条线路合并在一起，播放失败可自动切线路）
 */
public class Channel {

    public String name = "";
    /** 标准名，用于匹配台标 / 收藏 / 节目单 */
    public String standardName = "";
    /** 节目单中用于查询的名称 */
    public String epgName = "";
    public String logo;
    public String groupName = "";
    /** 全局序号，从 0 开始；-1 表示未编号 */
    public int index = -1;

    public final List<ChannelLine> lines = new ArrayList<ChannelLine>();
    /** 当前选中的线路下标（运行期状态，不持久化） */
    public int lineIndex = 0;

    public Channel() {
    }

    public Channel(String name) {
        this.name = name;
        this.standardName = ChannelAlias.standard(name);
        this.epgName = this.standardName;
    }

    public String no() {
        if (index < 0) {
            return "--";
        }
        int n = index + 1;
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    public ChannelLine currentLine() {
        if (lines.isEmpty()) {
            return null;
        }
        if (lineIndex < 0 || lineIndex >= lines.size()) {
            lineIndex = 0;
        }
        return lines.get(lineIndex);
    }

    /**
     * 同一个频道可能有多条线路，最多尝试 4 条，避免在坏源上反复卡住。
     */
    public int lineCount() {
        return lines.size();
    }

    public String epgKey() {
        String key = epgName;
        if (key == null || key.trim().isEmpty()) {
            key = standardName;
        }
        return ChannelAlias.key(key);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("n", name);
            o.put("sn", standardName);
            o.put("en", epgName);
            o.put("g", groupName);
            if (logo != null) {
                o.put("l", logo);
            }
            JSONArray arr = new JSONArray();
            for (ChannelLine l : lines) {
                arr.put(l.toJson());
            }
            o.put("ls", arr);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Channel fromJson(JSONObject o) {
        Channel c = new Channel();
        c.name = o.optString("n", "");
        c.standardName = o.optString("sn", c.name);
        c.epgName = o.optString("en", c.standardName);
        c.groupName = o.optString("g", "");
        c.logo = o.has("l") ? o.optString("l", null) : null;
        JSONArray arr = o.optJSONArray("ls");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject lo = arr.optJSONObject(i);
                if (lo != null) {
                    c.lines.add(ChannelLine.fromJson(lo));
                }
            }
        }
        return c;
    }
}
