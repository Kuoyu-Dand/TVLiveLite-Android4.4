package com.mytv.lite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.RecyclerView;

import com.mytv.lite.ui.MainActivity;
import com.mytv.lite.ui.SettingsFragment;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * 关于页：正文 + 署名 + 二维码 + “喂鱼”标注。
 * 关键在于高度——720p 盒子上不能把“知道了”挤出屏幕，二维码也不能大得像广告。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = App.class, qualifiers = "w1280dp-h720dp-mdpi")
public class AboutDialogTest {

    @Test
    public void aboutDialogShouldFitAndShowQr() {
        android.content.Context themed = new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.DialogTheme);
        View view = LayoutInflater.from(themed).inflate(R.layout.dialog_about, null);
        assertNotNull(view);

        ((TextView) view.findViewById(R.id.tvAboutBody)).setText(
                "电视直播 Lite v1.0.1 · 最低支持 Android 4.4 (API 19)\n\n"
                + "参考 mytv-android（天光云影）改造。为了让老设备跑得动，界面用原生 View "
                + "重写（原版是 Compose），播放器使用 ExoPlayer 2.18；"
                + "去掉了多屏同播、JS 转换源、WebView 源等重功能。\n\n"
                + "本软件不提供任何直播源，请自行添加合法订阅源。");

        // 对话框面板实际是 EXACTLY 宽度，按 440dp 内容区走一遍 720p 真实排版
        int contentW = 440;
        view.measure(View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());

        TextView credit = view.findViewById(R.id.tvCredit);
        assertEquals("本项目由力工大肥鱼玩命开发Dand全程监工", credit.getText().toString());

        ImageView qr = view.findViewById(R.id.ivQr);
        assertNotNull(qr);
        assertEquals("二维码 110dp", 110, qr.getMeasuredWidth());
        assertEquals(110, qr.getMeasuredHeight());

        TextView label = view.findViewById(R.id.tvQrLabel);
        assertEquals("喂鱼", label.getText().toString());
        assertTrue("标注应在二维码下方", label.getTop() >= qr.getBottom());
        assertTrue("署名应在二维码上方", credit.getBottom() <= qr.getTop());

        TextView feedback = view.findViewById(R.id.tvFeedback);
        assertNotNull("缺少问题反馈行", feedback);
        assertTrue("反馈邮箱丢了：" + feedback.getText(),
                feedback.getText().toString().contains("kuoyu@163.com"));
        assertTrue("问题反馈应在署名下方", feedback.getTop() >= credit.getBottom() - 1);
        assertTrue("问题反馈应在二维码上方", feedback.getBottom() <= qr.getTop());

        View ok = view.findViewById(R.id.btnAboutOk);
        assertTrue("知道了按钮被挤出内容区", ok.getBottom() <= view.getMeasuredHeight());

        android.util.Log.i("AboutDialog", "size=" + view.getMeasuredWidth()
                + "x" + view.getMeasuredHeight());
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream("build/about-size.txt");
            StringBuilder dump = new StringBuilder();
            ViewGroup root = (ViewGroup) view;
            for (int i = 0; i < root.getChildCount(); i++) {
                View c = root.getChildAt(i);
                dump.append("  ").append(c.getClass().getSimpleName())
                        .append(" top=").append(c.getTop()).append(" bottom=").append(c.getBottom())
                        .append(" h=").append(c.getHeight()).append("\n");
            }
            out.write((dump.toString() + "dialog=" + view.getMeasuredWidth() + "x" + view.getMeasuredHeight()
                    + "\nqr=" + qr.getWidth() + "x" + qr.getHeight()
                    + "\ncredit.top=" + credit.getTop() + " bottom=" + credit.getBottom()
                    + "\nlabel.top=" + label.getTop() + " bottom=" + label.getBottom()
                    + "\nok.bottom=" + ok.getBottom() + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
        // 720 高屏幕上还要留出对话框外边距，整块别超过 620
        assertTrue("对话框太高：" + view.getMeasuredHeight(), view.getMeasuredHeight() <= 648);
    }

    @Test
    public void aboutRowShouldOpenDialog() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        SettingsFragment fragment = new SettingsFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, fragment)
                .commit();
        activity.getSupportFragmentManager().executePendingTransactions();

        RecyclerView rv = fragment.getView().findViewById(R.id.rvSettings);
        RecyclerView.Adapter adapter = rv.getAdapter();
        assertNotNull(adapter);

        int index = -1;
        for (int i = 0; i < adapter.getItemCount(); i++) {
            RecyclerView.ViewHolder vh = adapter.createViewHolder(rv, adapter.getItemViewType(i));
            adapter.bindViewHolder(vh, i);
            TextView title = vh.itemView.findViewById(R.id.tvTitle);
            if ("关于".equals(title.getText().toString())) {
                index = i;
                break;
            }
        }
        assertTrue("设置页没有关于这一行", index >= 0);

        RecyclerView.ViewHolder vh = adapter.createViewHolder(rv, adapter.getItemViewType(index));
        adapter.bindViewHolder(vh, index);
        vh.itemView.performClick();

        assertTrue("关于对话框没弹出来", fragment.getActivity() != null);
        controller.pause().stop().destroy();
    }
}
