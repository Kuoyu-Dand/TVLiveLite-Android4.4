package com.mytv.lite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.mytv.lite.ui.FileBrowserActivity;
import com.mytv.lite.ui.StorageVolumes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置文件浏览器：不依赖系统选择器，直接列目录并选文件。
 * 这里验证「列目录 → 只剩源文件可选 → 点击回传绝对路径」这条链路不依赖任何
 * 系统 DocumentsUI（4.4 盒子上根本没有）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = App.class, qualifiers = "w1280dp-h720dp-mdpi")
public class FileBrowserTest {

    @Test
    public void volumesShouldNotBeEmpty() {
        List<StorageVolumes.Volume> volumes =
                StorageVolumes.list(RuntimeEnvironment.getApplication());
        assertNotNull(volumes);
        assertFalse("至少要能列出内部存储", volumes.isEmpty());
        for (StorageVolumes.Volume v : volumes) {
            assertTrue(v.dir.getAbsolutePath(), v.dir.isDirectory());
            assertTrue(v.label, v.label != null && v.label.length() > 0);
        }
    }

    @Test
    public void shouldListDirectoryAndPickSourceFile() throws Exception {
        File usb = newUsbDir("usbhost1");
        write(new File(usb, "live.m3u"), "#EXTM3U\n#EXTINF:-1,CCTV1\nhttp://a/b.m3u8\n");
        write(new File(usb, "note.mp4"), "not a source");
        assertTrue(new File(usb, "sub").mkdirs());

        Intent intent = new Intent(RuntimeEnvironment.getApplication(), FileBrowserActivity.class);
        intent.putExtra(FileBrowserActivity.EXTRA_MODE, "iptv");
        intent.putExtra(FileBrowserActivity.EXTRA_START, usb.getAbsolutePath());
        ActivityController<FileBrowserActivity> controller =
                Robolectric.buildActivity(FileBrowserActivity.class, intent).setup();
        FileBrowserActivity activity = controller.get();

        RecyclerView rv = activity.findViewById(R.id.rvFiles);
        RecyclerView.Adapter adapter = rv.getAdapter();
        assertNotNull(adapter);

        List<String> titles = titles(adapter, rv);
        assertTrue(titles.toString(), titles.contains("live.m3u"));
        assertTrue(titles.toString(), titles.contains("sub"));
        assertEquals("‹ 上一级", titles.get(0));

        // 点文件夹不应该结束界面
        click(adapter, rv, titles.indexOf("sub"));
        assertFalse(activity.isFinishing());

        // 回到上一级，点源文件应该回传绝对路径
        activity.onBackPressed();
        titles = titles(adapter, rv);
        click(adapter, rv, titles.indexOf("live.m3u"));
        assertTrue("选中文件后应结束浏览", activity.isFinishing());

        Intent result = Shadows.shadowOf(activity).getResultIntent();
        assertNotNull(result);
        assertEquals(android.app.Activity.RESULT_OK,
                Shadows.shadowOf(activity).getResultCode());
        assertEquals(new File(usb, "live.m3u").getAbsolutePath(),
                result.getStringExtra(FileBrowserActivity.EXTRA_PATH));
    }

    @Test
    public void unsupportedFileShouldNotBePicked() throws Exception {
        File usb = newUsbDir("udisk");
        write(new File(usb, "movie.mp4"), "not a source");

        Intent intent = new Intent(RuntimeEnvironment.getApplication(), FileBrowserActivity.class);
        intent.putExtra(FileBrowserActivity.EXTRA_MODE, "iptv");
        intent.putExtra(FileBrowserActivity.EXTRA_START, usb.getAbsolutePath());
        ActivityController<FileBrowserActivity> controller =
                Robolectric.buildActivity(FileBrowserActivity.class, intent).setup();
        FileBrowserActivity activity = controller.get();

        RecyclerView rv = activity.findViewById(R.id.rvFiles);
        RecyclerView.Adapter adapter = rv.getAdapter();
        List<String> titles = titles(adapter, rv);
        click(adapter, rv, titles.indexOf("movie.mp4"));
        assertFalse("不该把视频当源导入", activity.isFinishing());
    }

    @Test
    public void browserLayoutShouldFitScreen() throws Exception {
        File usb = newUsbDir("usb_layout");
        for (int i = 0; i < 8; i++) {
            write(new File(usb, "source_" + i + ".txt"), "央视,#genre#\nCCTV" + i + ",http://a/x\n");
        }
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), FileBrowserActivity.class);
        intent.putExtra(FileBrowserActivity.EXTRA_MODE, "iptv");
        intent.putExtra(FileBrowserActivity.EXTRA_START, usb.getAbsolutePath());
        ActivityController<FileBrowserActivity> controller =
                Robolectric.buildActivity(FileBrowserActivity.class, intent).setup();
        FileBrowserActivity activity = controller.get();

        android.view.View root = activity.findViewById(android.R.id.content);
        for (int i = 0; i < 2; i++) {
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(1280,
                            android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(720,
                            android.view.View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1280, 720);
        }

        RecyclerView rv = activity.findViewById(R.id.rvFiles);
        android.widget.LinearLayout chips = activity.findViewById(R.id.llVolumes);
        assertTrue("存储卷按钮没渲染出来", chips.getChildCount() > 0);
        assertTrue("chip width=" + chips.getChildAt(0).getWidth(), chips.getChildAt(0).getWidth() > 0);
        assertTrue("列表高度=" + rv.getHeight(), rv.getHeight() > 300);
        assertTrue("列表超出屏幕 bottom=" + rv.getBottom(), rv.getBottom() <= 720);
        android.widget.TextView hint = activity.findViewById(R.id.tvBrowserHint);
        assertTrue("提示条超出屏幕 bottom=" + hint.getBottom(), hint.getBottom() <= 720);
    }

    // ---------------- helpers ----------------

    private File newUsbDir(String name) throws IOException {
        File dir = new File(RuntimeEnvironment.getApplication().getCacheDir(), name);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("mkdir failed: " + dir);
        }
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        return dir;
    }

    private void write(File f, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        out.write(text.getBytes("UTF-8"));
        out.close();
    }

    private List<String> titles(RecyclerView.Adapter adapter, RecyclerView rv) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < adapter.getItemCount(); i++) {
            RecyclerView.ViewHolder vh = adapter.createViewHolder(rv, adapter.getItemViewType(i));
            adapter.bindViewHolder(vh, i);
            TextView tv = vh.itemView.findViewById(R.id.tvTitle);
            out.add(tv.getText().toString());
        }
        return out;
    }

    private void click(RecyclerView.Adapter adapter, RecyclerView rv, int index) {
        assertTrue("index=" + index, index >= 0);
        RecyclerView.ViewHolder vh = adapter.createViewHolder(rv, adapter.getItemViewType(index));
        adapter.bindViewHolder(vh, index);
        vh.itemView.performClick();
    }
}
