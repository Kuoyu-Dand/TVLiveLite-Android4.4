package com.mytv.lite.data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播源解析：m3u（#EXTM3U 开头）与 txt（含 #genre#）。
 * 逻辑与上游项目保持一致，去掉了 Js 转换之类老设备上不实用的能力。
 */
public final class IptvParser {

    private static final Pattern P_TVG_NAME = Pattern.compile("tvg-name=\"(.*?)\"");
    private static final Pattern P_GROUP = Pattern.compile("group-title=\"(.+?)\"");
    private static final Pattern P_LOGO = Pattern.compile("tvg-logo=\"(.+?)\"");
    private static final Pattern P_UA = Pattern.compile("http-user-agent=\"(.+?)\"");
    private static final Pattern P_TVG_URL = Pattern.compile("x-tvg-url=\"(.*?)\"");
    private static final Pattern P_URL_TVG = Pattern.compile("url-tvg=\"(.*?)\"");

    public static class Item {
        public String name = "";
        public String epgName = "";
        public String groupName = "";
        public String url = "";
        public String logo;
        public String ua;
    }

    private IptvParser() {
    }

    public static boolean isM3u(String data) {
        return data != null && data.trim().startsWith("#EXTM3U");
    }

    public static boolean isTxt(String data) {
        return data != null && data.contains("#genre#");
    }

    public static List<Item> parse(String data) {
        if (data == null || data.trim().isEmpty()) {
            return new ArrayList<Item>();
        }
        if (isM3u(data)) {
            return parseM3u(data);
        }
        if (isTxt(data)) {
            return parseTxt(data);
        }
        // 兜底：当作 txt 再试一次（很多源省掉了 #genre#）
        return parseTxt(data);
    }

    private static List<Item> parseM3u(String data) {
        List<Item> list = new ArrayList<Item>();
        String[] lines = data.split("\r\n|\n|\r");

        List<Item> pending = new ArrayList<Item>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF")) {
                String name = line;
                int comma = line.lastIndexOf(',');
                if (comma >= 0 && comma < line.length() - 1) {
                    name = line.substring(comma + 1).trim();
                }
                if (name.isEmpty()) {
                    name = "未命名频道";
                }

                String epgName = match(P_TVG_NAME, line);
                if (epgName == null || epgName.isEmpty()) {
                    epgName = name;
                }
                String group = match(P_GROUP, line);
                String logo = match(P_LOGO, line);
                String ua = match(P_UA, line);

                List<String> groupNames = new ArrayList<String>();
                if (group != null && !group.isEmpty()) {
                    for (String g : group.split(";")) {
                        if (!g.trim().isEmpty()) {
                            groupNames.add(g.trim());
                        }
                    }
                }
                if (groupNames.isEmpty()) {
                    groupNames.add("其他");
                }

                pending = new ArrayList<Item>();
                for (String g : groupNames) {
                    Item it = new Item();
                    it.name = name;
                    it.epgName = epgName;
                    it.groupName = g;
                    it.logo = logo;
                    it.ua = ua;
                    pending.add(it);
                }
            } else if (line.startsWith("#")) {
                // 其它标签忽略（含 KODIPROP 等 DRM 相关标签，老内核用不上）
                continue;
            } else {
                for (Item it : pending) {
                    Item copy = new Item();
                    copy.name = it.name;
                    copy.epgName = it.epgName;
                    copy.groupName = it.groupName;
                    copy.url = line;
                    copy.logo = it.logo;
                    copy.ua = it.ua;
                    list.add(copy);
                }
                pending = new ArrayList<Item>();
            }
        }
        return list;
    }

    private static List<Item> parseTxt(String data) {
        List<Item> list = new ArrayList<Item>();
        String[] lines = data.split("\r\n|\n|\r");
        String group = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            if (line.contains("#genre#")) {
                String g = line.split(",")[0];
                group = g.trim();
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length < 2) {
                continue;
            }
            String name = parts[0].trim();
            if (name.isEmpty()) {
                continue;
            }
            String urls = line.substring(line.indexOf(',') + 1).trim();
            for (String u : urls.split("#")) {
                String url = u.trim();
                if (url.isEmpty() || !(url.startsWith("http") || url.startsWith("rtmp")
                        || url.startsWith("rtsp") || url.startsWith("udp") || url.startsWith("rtp")
                        || url.contains("://"))) {
                    continue;
                }
                Item it = new Item();
                it.name = name;
                it.epgName = name;
                it.groupName = group == null ? "其他" : group;
                it.url = url;
                list.add(it);
            }
        }
        return list;
    }

    /**
     * m3u 头里的 x-tvg-url（节目单地址）
     */
    public static String epgUrl(String data) {
        if (data == null) {
            return null;
        }
        String m = match(P_TVG_URL, data);
        if (m == null) {
            m = match(P_URL_TVG, data);
        }
        if (m == null) {
            return null;
        }
        String first = m.split(",")[0].trim();
        return first.isEmpty() ? null : first;
    }

    private static String match(Pattern p, String line) {
        Matcher m = p.matcher(line);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }
}
