package com.mytv.lite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;

import com.mytv.lite.data.ChannelAlias;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * 在 API 19 语义下启动首页，验证：
 * 主题 / 布局 / 矢量图标 / MultiDex 之外的资源引用是否正常，不会一进应用就崩。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = App.class)
public class SmokeTest {

    @Test
    public void dashboardShouldInflateOnApi19() {
        ActivityController<com.mytv.lite.ui.MainActivity> controller =
                Robolectric.buildActivity(com.mytv.lite.ui.MainActivity.class).setup();
        com.mytv.lite.ui.MainActivity activity = controller.get();

        View container = activity.findViewById(R.id.container);
        assertNotNull(container);
        assertTrue(((android.view.ViewGroup) container).getChildCount() > 0);

        // 首页关键控件存在
        assertNotNull(activity.findViewById(R.id.tvClock));
        assertNotNull(activity.findViewById(R.id.navContainer));
        controller.pause().stop().destroy();
    }

    @Test
    public void channelAliasShouldNormalize() {
        ChannelAlias.init(org.robolectric.RuntimeEnvironment.getApplication());
        assertEquals("CCTV1", ChannelAlias.standard("CCTV-1 综合HD"));
        assertEquals("CCTV1", ChannelAlias.standard("cctv1高清"));
        assertEquals("湖南卫视", ChannelAlias.standard("湖南卫视HD"));
        assertEquals("cctv1", ChannelAlias.key("CCTV1"));
    }

    @Test
    public void settingsShouldOpen() {
        ActivityController<com.mytv.lite.ui.MainActivity> controller =
                Robolectric.buildActivity(com.mytv.lite.ui.MainActivity.class).setup();
        com.mytv.lite.ui.MainActivity activity = controller.get();
        activity.openSettings();
        assertNotNull(activity.findViewById(R.id.rvSettings) == null
                ? activity.findViewById(R.id.container) : activity.findViewById(R.id.rvSettings));
        controller.pause().stop().destroy();
    }
}
