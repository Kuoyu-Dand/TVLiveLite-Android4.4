package com.mytv.lite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.mytv.lite.data.Channel;
import com.mytv.lite.data.ChannelRepository;
import com.mytv.lite.data.IptvSource;
import com.mytv.lite.player.PlayerActivity;
import com.mytv.lite.ui.MainActivity;
import com.mytv.lite.ui.Ui;
import com.mytv.lite.ui.adapter.Adapters;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 1.0.5 新增的三件事：
 * 关怀模式（大字号 / 界面从简）、打开软件直进上次频道、OK 键同时亮信息条与呼出面板。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = App.class, qualifiers = "w1280dp-h720dp-mdpi")
public class CareModeTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.init(context);
        Prefs.setCareMode(false);
        Prefs.setAutoPlayLast(false);
        Prefs.putString(Prefs.K_LAST_CHANNEL, "");
        Prefs.saveIptvSources(new ArrayList<IptvSource>());
        ChannelRepository.clearCache(context);
    }

    @Test
    public void careModeRowShouldTurnOnAutoPlayToo() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        activity.openSettings();
        activity.getSupportFragmentManager().executePendingTransactions();

        RecyclerView rv = activity.findViewById(R.id.rvSettings);
        assertNotNull(rv);
        rv.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
        rv.layout(0, 0, 1280, 720);

        View first = rv.getChildAt(0);
        assertNotNull("设置列表第一行没渲染出来", first);
        TextView title = first.findViewById(R.id.tvTitle);
        assertEquals("关怀模式", title.getText().toString());

        first.performClick();
        assertTrue("关怀模式没打开", Prefs.careMode());
        assertTrue("关怀模式应同时打开直进播放", Prefs.autoPlayLast());

        // 再点一次关掉，两个开关的独立性要保住（直进播放由用户自己决定）
        rv.getChildAt(0).performClick();
        assertTrue("关怀模式没关掉", !Prefs.careMode());
        assertTrue("关关怀模式不该顺手关掉直进播放", Prefs.autoPlayLast());

        controller.pause().stop().destroy();
    }

    @Test
    public void panelAdapterShouldGrowInCareMode() {
        Adapters.PanelChannelAdapter adapter = new Adapters.PanelChannelAdapter(null);
        Channel c = new Channel("CCTV1");
        adapter.submit(Arrays.asList(c), c.standardName);

        FrameLayout parent = new FrameLayout(context);
        Adapters.PanelChannelAdapter.VH normal = adapter.onCreateViewHolder(parent, 0);
        adapter.bindViewHolder(normal, 0);
        TextView normalName = normal.itemView.findViewById(R.id.tvName);
        assertEquals(Ui.dp(context, 52), normal.itemView.getLayoutParams().height);
        assertEquals(14f, normalName.getTextSize(), 0.5f);

        adapter.setLarge(true);
        Adapters.PanelChannelAdapter.VH large = adapter.onCreateViewHolder(parent, 0);
        adapter.bindViewHolder(large, 0);
        TextView largeName = large.itemView.findViewById(R.id.tvName);
        TextView largeNo = large.itemView.findViewById(R.id.tvNo);
        assertEquals("关怀模式行高", Ui.dp(context, 80),
                large.itemView.getLayoutParams().height);
        assertEquals("关怀模式频道名", 24f, largeName.getTextSize(), 0.5f);
        assertEquals("关怀模式序号", 18f, largeNo.getTextSize(), 0.5f);
    }

    @Test
    public void playerShouldUseLargePanelInCareMode() {
        Prefs.setCareMode(true);
        ActivityController<PlayerActivity> controller =
                Robolectric.buildActivity(PlayerActivity.class).setup();
        PlayerActivity activity = controller.get();

        LinearLayout panel = activity.findViewById(R.id.channelPanel);
        assertEquals("面板宽度", Ui.dp(activity, 520), panel.getLayoutParams().width);
        assertEquals("关怀模式底栏不要“接下来”", View.GONE,
                activity.findViewById(R.id.tvNext).getVisibility());
        TextView panelTitle = activity.findViewById(R.id.tvPanelTitle);
        assertEquals("面板标题字号", 24f, panelTitle.getTextSize(), 0.5f);
        TextView toast = activity.findViewById(R.id.tvToast);
        assertEquals("换台提示字号", 21f, toast.getTextSize(), 0.5f);

        controller.pause().stop().destroy();
    }

    @Test
    public void okShouldShowBarsAndPanelTogether() throws Exception {
        // 用 rtsp 地址，走系统内核，测试里不拉起 ExoPlayer
        List<IptvSource> sources = new ArrayList<IptvSource>();
        File f = new File(context.getFilesDir(), "keytest.txt");
        FileOutputStream out = new FileOutputStream(f);
        out.write(("#EXTM3U\n#EXTINF:-1 group-title=\"央视\",CCTV1\n"
                + "rtsp://127.0.0.1/live\n").getBytes("UTF-8"));
        out.close();
        sources.add(new IptvSource("测试源", f.getAbsolutePath(), true, true));
        Prefs.saveIptvSources(sources);
        ChannelRepository.loadSync(context, true);

        // 让 Robolectric 的 MediaPlayer 认为这个地址能正常打开，否则会弹错误框抢走按键
        String url = "rtsp://127.0.0.1/live";
        java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
        headers.put("User-Agent", com.mytv.lite.net.Http.DEFAULT_UA);
        ShadowMediaPlayer.addMediaInfo(
                DataSource.toDataSource(context, android.net.Uri.parse(url), headers),
                new ShadowMediaPlayer.MediaInfo(60000, 0));

        ActivityController<PlayerActivity> controller =
                Robolectric.buildActivity(PlayerActivity.class).setup();
        PlayerActivity activity = controller.get();

        View panel = activity.findViewById(R.id.channelPanel);
        View topBar = activity.findViewById(R.id.topBar);
        View bottomBar = activity.findViewById(R.id.bottomBar);
        assertEquals("刚进播放页信息条应先亮着", View.VISIBLE, topBar.getVisibility());

        key(activity, KeyEvent.KEYCODE_BACK);
        assertEquals("返回应先收起信息条，而不是退出", View.GONE, topBar.getVisibility());
        assertTrue(!activity.isFinishing());

        // OK：信息条 + 频道面板一起出来
        key(activity, KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals("OK 没呼出频道面板", View.VISIBLE, panel.getVisibility());
        assertEquals("OK 没亮信息条", View.VISIBLE, topBar.getVisibility());
        assertEquals("面板打开时底栏让位，避免叠在一起", View.GONE,
                bottomBar.getVisibility());

        // 面板里按返回：面板和信息条一起收掉
        key(activity, KeyEvent.KEYCODE_BACK);
        assertEquals(View.GONE, panel.getVisibility());
        assertEquals(View.GONE, topBar.getVisibility());

        // 右键同样能呼出面板（不带信息条）
        key(activity, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("右键没呼出面板", View.VISIBLE, panel.getVisibility());
        assertEquals(View.GONE, topBar.getVisibility());

        controller.pause().stop().destroy();
    }

    private static void key(PlayerActivity activity, int code) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    @Test
    public void autoPlayShouldJumpIntoLastChannel() throws Exception {
        prepareOneChannel();
        Prefs.setAutoPlayLast(true);
        Prefs.putString(Prefs.K_LAST_CHANNEL, "CCTV1");

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        android.content.Intent started =
                org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivity();
        assertNotNull("打开软件没有直进播放", started);
        assertEquals(PlayerActivity.class.getName(),
                started.getComponent().getClassName());
        assertEquals("CCTV1", started.getStringExtra("channel"));

        controller.pause().stop().destroy();
    }

    @Test
    public void autoPlayShouldStayHomeWhenNoSource() {
        Prefs.setAutoPlayLast(true);
        Prefs.putString(Prefs.K_LAST_CHANNEL, "CCTV1");

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertNull("没有可用订阅源时不该直进播放，否则一开就是错误页",
                org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivity());
        controller.pause().stop().destroy();
    }

    private void prepareOneChannel() throws Exception {
        File f = new File(context.getFilesDir(), "autoplay.txt");
        FileOutputStream out = new FileOutputStream(f);
        out.write(("#EXTM3U\n#EXTINF:-1 group-title=\"央视\",CCTV1\n"
                + "rtsp://127.0.0.1/live\n").getBytes("UTF-8"));
        out.close();
        List<IptvSource> sources = new ArrayList<IptvSource>();
        sources.add(new IptvSource("测试源", f.getAbsolutePath(), true, true));
        Prefs.saveIptvSources(sources);
        ChannelRepository.loadSync(context, true);
    }
}
