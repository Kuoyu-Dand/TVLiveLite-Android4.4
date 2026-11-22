package com.mytv.lite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mytv.lite.data.IptvParser;

import org.junit.Test;

import java.util.List;

/**
 * 直播源解析的纯逻辑测试
 */
public class IptvParserTest {

    @Test
    public void parseM3u_shouldSplitGroupsAndLines() {
        String data = "#EXTM3U x-tvg-url=\"http://epg.example.com/e.xml\"\n"
                + "#EXTINF:-1 tvg-name=\"CCTV1\" tvg-logo=\"http://logo/1.png\" "
                + "group-title=\"央视;高清\",CCTV-1 综合\n"
                + "http://a.example.com/1.m3u8\n"
                + "#EXTINF:-1 group-title=\"卫视\",湖南卫视 HD\n"
                + "http://a.example.com/2.m3u8$线路1\n";

        List<IptvParser.Item> items = IptvParser.parse(data);
        assertEquals(3, items.size());

        assertEquals("CCTV-1 综合", items.get(0).name);
        assertEquals("CCTV1", items.get(0).epgName);
        assertEquals("央视", items.get(0).groupName);
        assertEquals("http://logo/1.png", items.get(0).logo);
        assertEquals("http://a.example.com/1.m3u8", items.get(0).url);

        assertEquals("高清", items.get(1).groupName);
        assertEquals("卫视", items.get(2).groupName);

        assertEquals("http://epg.example.com/e.xml", IptvParser.epgUrl(data));
    }

    @Test
    public void parseTxt_shouldHandleGenreAndMultiUrl() {
        String data = "央视频道,#genre#\n"
                + "CCTV1,http://a/1.m3u8#http://a/2.m3u8\n"
                + "CCTV2,http://a/3.m3u8\n"
                + "地方频道,#genre#\n"
                + "北京卫视,http://a/4.m3u8\n";

        List<IptvParser.Item> items = IptvParser.parse(data);
        assertEquals(4, items.size());
        assertEquals("央视频道", items.get(0).groupName);
        assertEquals("http://a/1.m3u8", items.get(0).url);
        assertEquals("http://a/2.m3u8", items.get(1).url);
        assertEquals("地方频道", items.get(3).groupName);
        assertEquals("北京卫视", items.get(3).name);
    }

    @Test
    public void parseTxt_shouldIgnoreCommentAndBadLines() {
        String data = "# 注释\n"
                + "// 另一个注释\n"
                + "没有地址的频道\n"
                + "CCTV1,,http://a/1.m3u8\n";

        List<IptvParser.Item> items = IptvParser.parse(data);
        assertTrue(items.size() <= 1);
    }

    @Test
    public void parse_m3uWithoutExtinf_shouldNotCrash() {
        assertTrue(IptvParser.parse("#EXTM3U\nhttp://a/1.m3u8\n").isEmpty());
    }
}
