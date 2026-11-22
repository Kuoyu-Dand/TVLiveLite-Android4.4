package com.mytv.lite.player;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.video.VideoSize;

import com.mytv.lite.Prefs;
import com.mytv.lite.R;
import com.mytv.lite.data.Channel;
import com.mytv.lite.data.ChannelAlias;
import com.mytv.lite.data.ChannelLine;
import com.mytv.lite.data.ChannelRepository;
import com.mytv.lite.data.EpgRepository;
import com.mytv.lite.net.Http;
import com.mytv.lite.ui.MainActivity;
import com.mytv.lite.ui.Ui;
import com.mytv.lite.ui.adapter.Adapters;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 直播播放页。
 * 只用一个 SurfaceView，ExoPlayer 与系统 MediaPlayer 两种内核共用，
 * 这样老盒子上的解码器差异可以通过设置切换来绕过。
 */
public class PlayerActivity extends AppCompatActivity {

    private static final long BARS_HIDE_DELAY = 5000L;
    private static final long LOAD_TIMEOUT = 18000L;

    public static void start(Context context, String channelName) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra("channel", channelName);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    private Channel channel;
    private ExoPlayer exo;
    private MediaPlayer sysPlayer;
    private boolean usingSystemPlayer;
    private int lineRetry;
    private boolean panelOpen;
    private boolean errorVisible;
    private boolean loadingVisible;

    private AspectFrame aspect;
    private SurfaceView surface;
    private FrameLayout root;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private LinearLayout channelPanel;
    private LinearLayout loadingBox;
    private LinearLayout errorBox;
    private TextView tvChanNo;
    private TextView tvChanName;
    private TextView tvClock;
    private TextView tvNowTitle;
    private TextView tvNowTime;
    private TextView tvNext;
    private TextView tvLoading;
    private TextView tvError;
    private TextView tvToast;
    private TextView tvPanelTitle;
    private View vProgress;
    private RecyclerView rvPanel;
    private Adapters.PanelChannelAdapter panelAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Runnable> pending = new ArrayList<Runnable>();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshInfo();
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable hideBarsTask = new Runnable() {
        @Override
        public void run() {
            hideBars();
        }
    };

    private final Runnable loadTimeoutTask = new Runnable() {
        @Override
        public void run() {
            if (loadingVisible) {
                onPlaybackFailed("连接超时，可能是源失效或网络不通");
            }
        }
    };

    // ---------------- 生命周期 ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        bindViews();
        setupPanel();
        applyAspect();

        String name = getIntent() == null ? null : getIntent().getStringExtra("channel");
        if (name == null || name.trim().isEmpty()) {
            name = Prefs.getString(Prefs.K_LAST_CHANNEL, "");
        }
        Channel found = ChannelRepository.findByName(name);
        if (found != null) {
            begin(found);
        } else if (ChannelRepository.all().isEmpty()) {
            showLoading(true, "正在加载频道…");
            ChannelRepository.loadAsync(this, false, new ChannelRepository.Callback() {
                @Override
                public void onResult(String error) {
                    if (isFinishing()) {
                        return;
                    }
                    Channel c = ChannelRepository.findByName(
                            Prefs.getString(Prefs.K_LAST_CHANNEL, ""));
                    if (c == null) {
                        c = ChannelRepository.first();
                    }
                    if (c == null) {
                        showLoading(false, null);
                        showError(error == null ? "没有可用频道，请先添加订阅源" : error);
                    } else {
                        begin(c);
                    }
                }
            });
        } else {
            Channel c = ChannelRepository.first();
            if (c == null) {
                Ui.toast(this, "没有可用频道");
                finish();
            } else {
                begin(c);
            }
        }
    }

    private void begin(Channel c) {
        channel = c;
        channel.lineIndex = 0;
        lineRetry = 0;
        Prefs.putString(Prefs.K_LAST_CHANNEL, channel.standardName);
        Prefs.pushHistory(channel.standardName);
        updateChannelText();
        startPlayback();
        handler.post(ticker);
        showBars();
        if (Prefs.epgEnabled() && !EpgRepository.hasData()) {
            EpgRepository.loadAsync(this, false, null);
        }
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        aspect = findViewById(R.id.aspect);
        surface = findViewById(R.id.surface);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        channelPanel = findViewById(R.id.channelPanel);
        loadingBox = findViewById(R.id.loadingBox);
        errorBox = findViewById(R.id.errorBox);
        tvChanNo = findViewById(R.id.tvChanNo);
        tvChanName = findViewById(R.id.tvChanName);
        tvClock = findViewById(R.id.tvClock);
        tvNowTitle = findViewById(R.id.tvNowTitle);
        tvNowTime = findViewById(R.id.tvNowTime);
        tvNext = findViewById(R.id.tvNext);
        tvLoading = findViewById(R.id.tvLoading);
        tvError = findViewById(R.id.tvError);
        tvToast = findViewById(R.id.tvToast);
        tvPanelTitle = findViewById(R.id.tvPanelTitle);
        vProgress = findViewById(R.id.vProgress);
        rvPanel = findViewById(R.id.rvPanel);

        root.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // 面板/错误框显示时把触摸交给子控件
                if (panelOpen || errorVisible) {
                    return false;
                }
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX = event.getX();
                    downY = event.getY();
                    downTime = System.currentTimeMillis();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) < 40 && Math.abs(dy) < 40
                            && System.currentTimeMillis() - downTime < 600) {
                        float w = v.getWidth();
                        if (panelOpen || errorVisible) {
                            return true;
                        }
                        if (downX < w * 0.22f) {
                            switchChannel(-1);
                        } else if (downX > w * 0.78f) {
                            switchChannel(1);
                        } else if (topBar.getVisibility() == View.VISIBLE) {
                            hideBars();
                        } else {
                            showBars();
                        }
                    }
                    return true;
                }
                return false;
            }
        });

        findViewById(R.id.btnRetry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                lineRetry = 0;
                startPlayback();
            }
        });
        findViewById(R.id.btnNextLine).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                nextLine();
            }
        });
        findViewById(R.id.btnNextChannel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideError();
                switchChannel(1);
            }
        });
    }

    private void setupPanel() {
        rvPanel.setLayoutManager(new LinearLayoutManager(this));
        panelAdapter = new Adapters.PanelChannelAdapter(new Adapters.OnChannelAction() {
            @Override
            public void onClick(Channel c) {
                closePanel();
                if (channel != null
                        && ChannelAlias.key(c.standardName)
                        .equals(ChannelAlias.key(channel.standardName))) {
                    return;
                }
                channel = c;
                channel.lineIndex = 0;
                lineRetry = 0;
                Prefs.putString(Prefs.K_LAST_CHANNEL, c.standardName);
                Prefs.pushHistory(c.standardName);
                updateChannelText();
                startPlayback();
            }

            @Override
            public void onLongClick(Channel c) {
                Prefs.toggleFavorite(c.standardName);
                panelAdapter.notifyDataSetChanged();
                showToast(Prefs.isFavorite(c.standardName) ? "已收藏" : "已取消收藏");
            }
        });
        rvPanel.setAdapter(panelAdapter);
    }

    // ---------------- 播放内核 ----------------

    private void startPlayback() {
        releasePlayers();
        hideError();
        if (channel == null) {
            return;
        }
        ChannelLine line = channel.currentLine();
        if (line == null) {
            showError("该频道没有可用线路");
            return;
        }
        String url = line.playableUrl();
        String ua = resolveUserAgent(line);
        showLoading(true, "正在连接 " + line.displayName() + "…");

        handler.removeCallbacks(loadTimeoutTask);
        handler.postDelayed(loadTimeoutTask, LOAD_TIMEOUT);

        if ("sys".equals(Prefs.kernel()) || isSystemOnly(url)) {
            startSystemPlayer(url, ua);
        } else {
            startExoPlayer(url, ua);
        }
    }

    private boolean isSystemOnly(String url) {
        String u = url.toLowerCase();
        return u.startsWith("rtsp://") || u.startsWith("rtmp://") || u.startsWith("udp://")
                || u.startsWith("rtp://") || u.startsWith("mms://");
    }

    private String resolveUserAgent(ChannelLine line) {
        if (line.userAgent != null && !line.userAgent.trim().isEmpty()) {
            return line.userAgent.trim();
        }
        String ua = Prefs.ua();
        if (ua != null && !ua.trim().isEmpty()) {
            return ua.trim();
        }
        return Http.DEFAULT_UA;
    }

    private void startExoPlayer(String url, String ua) {
        usingSystemPlayer = false;
        try {
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(this);
            renderers.setEnableDecoderFallback(true);
            if (Prefs.softwareDecode()) {
                renderers.setMediaCodecSelector(SOFTWARE_DECODER);
            }
            exo = new ExoPlayer.Builder(this, renderers).build();
            exo.setVideoSurfaceView(surface);
            exo.addListener(exoListener);

            DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                    .setUserAgent(ua)
                    .setConnectTimeoutMs(Prefs.timeoutMs())
                    .setReadTimeoutMs(Prefs.timeoutMs())
                    .setAllowCrossProtocolRedirects(true);
            DataSource.Factory factory = new DefaultDataSource.Factory(this, http);

            MediaItem item = MediaItem.fromUri(url);
            MediaSource source;
            if (url.toLowerCase().contains(".m3u8")) {
                source = new HlsMediaSource.Factory(factory).createMediaSource(item);
            } else {
                source = new ProgressiveMediaSource.Factory(factory).createMediaSource(item);
            }
            exo.setMediaSource(source);
            exo.prepare();
            exo.setPlayWhenReady(true);
        } catch (Throwable t) {
            onPlaybackFailed("播放器初始化失败：" + t.getMessage());
        }
    }

    private void startSystemPlayer(final String url, String ua) {
        usingSystemPlayer = true;
        try {
            sysPlayer = new MediaPlayer();
            sysPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            sysPlayer.setDisplay(surface.getHolder());
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", ua);
            sysPlayer.setDataSource(this, android.net.Uri.parse(url), headers);
            sysPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    handler.removeCallbacks(loadTimeoutTask);
                    showLoading(false, null);
                    try {
                        mp.start();
                    } catch (Throwable ignored) {
                    }
                }
            });
            sysPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                    aspect.setVideoSize(width, height);
                }
            });
            sysPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    onPlaybackFailed("系统播放器错误 " + what + "/" + extra);
                    return true;
                }
            });
            sysPlayer.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        showLoading(true, "缓冲中…");
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                            || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        showLoading(false, null);
                    }
                    return false;
                }
            });
            sysPlayer.prepareAsync();
        } catch (Throwable t) {
            onPlaybackFailed("系统播放器无法打开该地址");
        }
    }

    private static final MediaCodecSelector SOFTWARE_DECODER = new MediaCodecSelector() {
        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder,
                                                    boolean requiresTunnelingDecoder)
                throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> all = MediaCodecUtil.getDecoderInfos(mimeType,
                    requiresSecureDecoder, requiresTunnelingDecoder);
            List<MediaCodecInfo> soft = new ArrayList<MediaCodecInfo>();
            for (MediaCodecInfo info : all) {
                if (info.softwareOnly) {
                    soft.add(info);
                }
            }
            return soft.isEmpty() ? all : soft;
        }
    };

    private final Player.Listener exoListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) {
                handler.removeCallbacks(loadTimeoutTask);
                showLoading(false, null);
            } else if (state == Player.STATE_BUFFERING) {
                if (!errorVisible) {
                    showLoading(true, "缓冲中…");
                }
            } else if (state == Player.STATE_ENDED) {
                switchChannel(1);
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            String msg = error == null ? "播放失败" : error.getErrorCodeName();
            onPlaybackFailed("播放失败：" + msg);
        }

        @Override
        public void onVideoSizeChanged(VideoSize videoSize) {
            aspect.setVideoSize(videoSize.width, videoSize.height);
        }
    };

    private void onPlaybackFailed(String message) {
        handler.removeCallbacks(loadTimeoutTask);
        if (channel != null && channel.lineCount() > 1
                && lineRetry < Math.min(3, channel.lineCount() - 1)) {
            lineRetry++;
            channel.lineIndex = (channel.lineIndex + 1) % channel.lineCount();
            showToast("线路异常，自动切到 " + channel.currentLine().displayName());
            startPlayback();
            return;
        }
        showLoading(false, null);
        showError(message);
    }

    private void nextLine() {
        if (channel == null || channel.lineCount() <= 1) {
            Ui.toast(this, "该频道只有一条线路");
            return;
        }
        channel.lineIndex = (channel.lineIndex + 1) % channel.lineCount();
        lineRetry = 0;
        showToast("线路：" + channel.currentLine().displayName());
        startPlayback();
    }

    private void releasePlayers() {
        if (exo != null) {
            try {
                exo.removeListener(exoListener);
                exo.stop();
                exo.release();
            } catch (Throwable ignored) {
            }
            exo = null;
        }
        if (sysPlayer != null) {
            try {
                sysPlayer.reset();
                sysPlayer.release();
            } catch (Throwable ignored) {
            }
            sysPlayer = null;
        }
    }

    // ---------------- 频道切换 ----------------

    private void switchChannel(int delta) {
        List<Channel> all = ChannelRepository.all();
        if (all.isEmpty()) {
            return;
        }
        int index = all.indexOf(channel);
        if (index < 0) {
            index = 0;
        }
        int size = all.size();
        index = ((index + delta) % size + size) % size;
        Channel next = all.get(index);
        channel = next;
        channel.lineIndex = 0;
        lineRetry = 0;
        Prefs.putString(Prefs.K_LAST_CHANNEL, next.standardName);
        Prefs.pushHistory(next.standardName);
        updateChannelText();
        startPlayback();
        showBars();
        showToast(next.no() + "  " + next.name);
    }

    private void updateChannelText() {
        if (channel == null) {
            return;
        }
        tvChanNo.setText(channel.no());
        tvChanName.setText(channel.name);
        tvPanelTitle.setText("全部频道（" + ChannelRepository.all().size() + "）");
    }

    // ---------------- 界面状态 ----------------

    private void showLoading(boolean show, String text) {
        loadingVisible = show;
        loadingBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (text != null && tvLoading != null) {
            tvLoading.setText(text);
        }
        if (!show) {
            handler.removeCallbacks(loadTimeoutTask);
        }
    }

    private void showError(String message) {
        errorVisible = true;
        errorBox.setVisibility(View.VISIBLE);
        tvError.setText(message == null ? "" : message);
        showBars(true);
        View retry = findViewById(R.id.btnRetry);
        if (retry != null) {
            retry.requestFocus();
        }
    }

    private void hideError() {
        errorVisible = false;
        errorBox.setVisibility(View.GONE);
    }

    private void showBars() {
        showBars(false);
    }

    private void showBars(boolean keep) {
        if (panelOpen) {
            return;
        }
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        refreshInfo();
        handler.removeCallbacks(hideBarsTask);
        if (!keep && !errorVisible) {
            handler.postDelayed(hideBarsTask, BARS_HIDE_DELAY);
        }
    }

    private void hideBars() {
        if (panelOpen || errorVisible) {
            return;
        }
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
    }

    private void showToast(String text) {
        if (tvToast == null) {
            return;
        }
        tvToast.setText(text);
        tvToast.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideToastTask);
        handler.postDelayed(hideToastTask, 2600);
    }

    private final Runnable hideToastTask = new Runnable() {
        @Override
        public void run() {
            if (tvToast != null) {
                tvToast.setVisibility(View.GONE);
            }
        }
    };

    private void refreshInfo() {
        if (channel == null || tvClock == null) {
            return;
        }
        tvClock.setText(Ui.clock());
        EpgRepository.Programme now = EpgRepository.current(channel);
        if (now != null) {
            tvNowTitle.setText(now.title);
            tvNowTime.setText(Ui.time(now.start) + " - " + Ui.time(now.end));
            long span = Math.max(1, now.end - now.start);
            float ratio = (float) (System.currentTimeMillis() - now.start) / (float) span;
            setProgress(ratio);
        } else {
            tvNowTitle.setText("暂无节目信息");
            tvNowTime.setText("");
            setProgress(0f);
        }
        EpgRepository.Programme next = EpgRepository.next(channel);
        if (next != null) {
            tvNext.setText("接下来 " + Ui.time(next.start) + "  " + next.title);
        } else {
            tvNext.setText("");
        }
    }

    private void setProgress(float ratio) {
        if (vProgress == null || !(vProgress.getParent() instanceof FrameLayout)) {
            return;
        }
        FrameLayout parent = (FrameLayout) vProgress.getParent();
        int width = parent.getWidth();
        if (width <= 0) {
            return;
        }
        float clamped = Math.max(0f, Math.min(1f, ratio));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) vProgress.getLayoutParams();
        lp.width = (int) (width * clamped);
        vProgress.setLayoutParams(lp);
    }

    private void applyAspect() {
        String mode = Prefs.aspect();
        if ("fill".equals(mode)) {
            aspect.setMode(AspectFrame.MODE_FILL);
        } else if ("zoom".equals(mode)) {
            aspect.setMode(AspectFrame.MODE_CROP);
        } else {
            aspect.setMode(AspectFrame.MODE_FIT);
        }
    }

    // ---------------- 频道面板 ----------------

    private void openPanel() {
        if (channel == null) {
            return;
        }
        panelOpen = true;
        hideBars();
        channelPanel.setVisibility(View.VISIBLE);
        panelAdapter.submit(ChannelRepository.all(),
                ChannelAlias.key(channel.standardName));
        int index = panelAdapter.indexOf(ChannelAlias.key(channel.standardName));
        rvPanel.scrollToPosition(index);
        final int target = index;
        rvPanel.post(new Runnable() {
            @Override
            public void run() {
                RecyclerView.LayoutManager lm = rvPanel.getLayoutManager();
                if (lm instanceof LinearLayoutManager) {
                    View v = ((LinearLayoutManager) lm).findViewByPosition(target);
                    if (v != null) {
                        v.requestFocus();
                    }
                }
            }
        });
    }

    private void closePanel() {
        panelOpen = false;
        channelPanel.setVisibility(View.GONE);
        showBars();
    }

    // ---------------- 按键 ----------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        int code = event.getKeyCode();

        if (errorVisible) {
            if (code == KeyEvent.KEYCODE_BACK) {
                hideError();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (panelOpen) {
            if (code == KeyEvent.KEYCODE_BACK) {
                closePanel();
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                closePanel();
                showOptions();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                switchChannel(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                switchChannel(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                showProgrammeList();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                openPanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (topBar.getVisibility() == View.VISIBLE) {
                    hideBars();
                } else {
                    showBars();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                showOptions();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (topBar.getVisibility() == View.VISIBLE) {
                    hideBars();
                    return true;
                }
                finish();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void showOptions() {
        if (channel == null) {
            return;
        }
        final boolean fav = Prefs.isFavorite(channel.standardName);
        String[] items = {
                fav ? "取消收藏" : "收藏该频道",
                "切换线路（" + channel.lineCount() + " 条）",
                "画面比例",
                "播放设置",
                "退出播放",
        };
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle(channel.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            Prefs.toggleFavorite(channel.standardName);
                            showToast(Prefs.isFavorite(channel.standardName)
                                    ? "已收藏" : "已取消收藏");
                        } else if (which == 1) {
                            nextLine();
                        } else if (which == 2) {
                            chooseAspect();
                        } else if (which == 3) {
                            Intent intent = new Intent(PlayerActivity.this, MainActivity.class);
                            intent.putExtra("open_settings", true);
                            startActivity(intent);
                        } else {
                            finish();
                        }
                    }
                })
                .show();
    }

    private void chooseAspect() {
        final String[] items = {"适应屏幕（保持比例）", "拉伸铺满", "裁剪铺满"};
        String current = Prefs.aspect();
        int checked = "fill".equals(current) ? 1 : ("zoom".equals(current) ? 2 : 0);
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("画面比例")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.putString(Prefs.K_ASPECT,
                                which == 1 ? "fill" : (which == 2 ? "zoom" : "fit"));
                        applyAspect();
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void showProgrammeList() {
        if (channel == null) {
            return;
        }
        final List<EpgRepository.Programme> list = EpgRepository.programmes(channel);
        if (list.isEmpty()) {
            showToast("暂无节目信息");
            return;
        }
        final String[] items = new String[list.size()];
        long now = System.currentTimeMillis();
        int checked = -1;
        for (int i = 0; i < list.size(); i++) {
            EpgRepository.Programme p = list.get(i);
            String prefix = (p.start <= now && p.end > now) ? "▶ " : "   ";
            items[i] = prefix + Ui.time(p.start) + "  " + p.title;
            if (prefix.startsWith("▶")) {
                checked = i;
            }
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DialogTheme);
        b.setTitle(channel.name + " · 今日节目");
        b.setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        b.setNegativeButton("关闭", null);
        b.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyAspect();
        if (exo != null) {
            exo.setPlayWhenReady(true);
        } else if (sysPlayer != null) {
            try {
                sysPlayer.start();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exo != null) {
            exo.setPlayWhenReady(false);
        } else if (sysPlayer != null && sysPlayer.isPlaying()) {
            try {
                sysPlayer.pause();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        releasePlayers();
    }
}
