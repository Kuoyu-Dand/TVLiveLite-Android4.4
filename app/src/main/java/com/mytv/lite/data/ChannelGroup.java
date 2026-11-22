package com.mytv.lite.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道分组
 */
public class ChannelGroup {

    public String name = "";
    public final List<Channel> channels = new ArrayList<Channel>();

    public ChannelGroup() {
    }

    public ChannelGroup(String name) {
        this.name = name;
    }
}
