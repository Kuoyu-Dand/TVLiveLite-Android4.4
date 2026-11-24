package com.mytv.lite.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.mytv.lite.App;
import com.mytv.lite.Prefs;
import com.mytv.lite.R;
import com.mytv.lite.data.IptvSource;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

/**
 * 1.0.7：有声无画的兜底。
 * 4.1~4.3 的部分盒子硬解正常出声，但 SurfaceView 上一直是黑的，
 * 换 TextureView（GPU 合成）或换系统内核能救回来，这里验证这条链路。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = App.class, qualifiers = "w1280dp-h720dp-mdpi")
public class RenderFallbackTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.init(context);
        Prefs.setRenderMode("auto");
        Prefs.setCareMode(false);
        Prefs.setAutoPlayLast(false);
        Prefs.saveIptvSources(new ArrayList<IptvSource>());
    }

    @Test
    public void renderModeShouldDefaultToAutoAndRejectGarbage() {
        assertEquals("auto", Prefs.renderMode());
        Prefs.setRenderMode("texture");
        assertEquals("texture", Prefs.renderMode());
        Prefs.putString(Prefs.K_RENDER, "whatever");
        assertEquals("非法值应回落到 auto", "auto", Prefs.renderMode());
    }

    @Test
    public void playerShouldUseSurfaceViewByDefault() {
        ActivityController<PlayerActivity> controller =
                Robolectric.buildActivity(PlayerActivity.class).setup();
        FrameLayout group = controller.get().findViewById(R.id.aspect);
        assertEquals("只应有一个渲染视图", 1, group.getChildCount());
        assertTrue("默认应是 SurfaceView", group.getChildAt(0) instanceof SurfaceView);
        controller.pause().stop().destroy();
    }

    @Test
    public void playerShouldUseTextureViewWhenConfigured() {
        Prefs.setRenderMode("texture");
        ActivityController<PlayerActivity> controller =
                Robolectric.buildActivity(PlayerActivity.class).setup();
        FrameLayout group = controller.get().findViewById(R.id.aspect);
        assertEquals(1, group.getChildCount());
        assertTrue("设置成兼容渲染时应是 TextureView",
                group.getChildAt(0) instanceof TextureView);
        ViewGroup.LayoutParams lp = group.getChildAt(0).getLayoutParams();
        assertEquals("渲染视图要铺满比例容器", ViewGroup.LayoutParams.MATCH_PARENT, lp.width);
        controller.pause().stop().destroy();
    }

    @Test
    public void noFirstFrameShouldSwitchToTextureThenSystem() {
        ActivityController<PlayerActivity> controller =
                Robolectric.buildActivity(PlayerActivity.class).setup();
        PlayerActivity activity = controller.get();
        FrameLayout group = activity.findViewById(R.id.aspect);
        assertNotNull(group);
        assertTrue(group.getChildAt(0) instanceof SurfaceView);

        // 第一级：换成 TextureView，并记进设置里（换台不必再等 8 秒）
        assertEquals(1, activity.noFirstFrameFallback());
        assertTrue("换完应是 TextureView", group.getChildAt(0) instanceof TextureView);
        assertEquals("回退结果应持久化", "texture", Prefs.renderMode());

        // 第二级：仍然没有画面，换系统内核，并把渲染视图换回 SurfaceView
        assertEquals(2, activity.noFirstFrameFallback());
        assertTrue("系统内核只能用 SurfaceView", group.getChildAt(0) instanceof SurfaceView);

        // 手段用尽，不再折腾
        assertEquals(0, activity.noFirstFrameFallback());

        controller.pause().stop().destroy();
    }

    @Test
    public void explicitSurfaceChoiceShouldNotBeOverwritten() {
        Prefs.setRenderMode("surface");
        ActivityController<PlayerActivity> controller =
                Robolectric.buildActivity(PlayerActivity.class).setup();
        PlayerActivity activity = controller.get();

        assertEquals(1, activity.noFirstFrameFallback());
        assertEquals("用户手动选了 performance 就别偷偷改设置", "surface", Prefs.renderMode());
        controller.pause().stop().destroy();
    }
}
