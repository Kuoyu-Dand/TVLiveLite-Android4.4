package com.mytv.lite;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 布局体检：在 1280x720（mdpi，典型 720p 电视盒子）下真正走一遍 measure/layout，
 * 把关键控件的位置写进 build/layout-report.txt，并断言不会超出屏幕、尺寸合理。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = App.class, qualifiers = "w1280dp-h720dp-mdpi")
public class LayoutTest {

    private static final int W = 1280;
    private static final int H = 720;

    @Test
    public void dashboardLayoutShouldFitScreen() throws Exception {
        ActivityController<com.mytv.lite.ui.MainActivity> controller =
                Robolectric.buildActivity(com.mytv.lite.ui.MainActivity.class).setup();
        com.mytv.lite.ui.MainActivity activity = controller.get();
        activity.getSupportFragmentManager().executePendingTransactions();

        View root = activity.findViewById(android.R.id.content);
        for (int i = 0; i < 2; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, W, H);
        }

        StringBuilder sb = new StringBuilder();
        dump(root, sb, 0);
        File dir = new File("build");
        FileOutputStream out = new FileOutputStream(new File(dir, "layout-report.txt"));
        out.write(sb.toString().getBytes("UTF-8"));
        out.close();

        TextView source = activity.findViewById(R.id.tvSourceName);
        View nav = activity.findViewById(R.id.navContainer);
        View recent = activity.findViewById(R.id.rvRecent);
        assertNotNull(source);
        assertNotNull(nav);
        assertNotNull(recent);

        // 顶部信息条在屏幕内
        assertTrue(source.getTop() >= 0);
        assertTrue(source.getBottom() <= H);
        // 导航区宽度/高度合理（三列两行）
        assertTrue("nav width=" + nav.getWidth(), nav.getWidth() > 300);
        assertTrue("nav height=" + nav.getHeight(), nav.getHeight() > 150);
        ViewGroup navGroup = (ViewGroup) nav;
        assertTrue("nav rows=" + navGroup.getChildCount(), navGroup.getChildCount() == 3);
        for (int r = 0; r < navGroup.getChildCount(); r++) {
            ViewGroup row = (ViewGroup) navGroup.getChildAt(r);
            assertTrue("nav columns=" + row.getChildCount(), row.getChildCount() == 2);
            for (int i = 0; i < row.getChildCount(); i++) {
                View card = row.getChildAt(i);
                assertTrue("card width=" + card.getWidth(), card.getWidth() > 150);
                assertTrue("card height=" + card.getHeight(), card.getHeight() > 100);
            }
        }
        // 最近观看不会跑到屏幕外
        assertTrue("recent bottom=" + recent.getBottom(), recent.getBottom() <= H);

        controller.pause().stop().destroy();
    }

    private void dump(View view, StringBuilder sb, int depth) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }
        String text = view instanceof TextView
                ? " text=" + ((TextView) view).getText() : "";
        sb.append(indent).append(view.getClass().getSimpleName())
                .append(" [").append(view.getLeft()).append(",").append(view.getTop())
                .append(" - ").append(view.getRight()).append(",").append(view.getBottom())
                .append("] ").append(view.getWidth()).append("x").append(view.getHeight())
                .append(" vis=").append(view.getVisibility())
                .append(text)
                .append("\n");
        if (view instanceof ViewGroup && depth < 6) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dump(group.getChildAt(i), sb, depth + 1);
            }
        }
    }
}
