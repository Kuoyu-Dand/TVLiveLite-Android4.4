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
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
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
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.TrackSelectionParameters;
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
 * 画面渲染视图（SurfaceView / TextureView）与 ExoPlayer、系统 MediaPlayer 两种内核解耦，
 * 这样老盒子上「有声无画」的情况可以靠换渲染方式或换内核绕过去。
 */
public class PlayerActivity extends AppCompatActivity {

    /** 渲染方式：SurfaceView 走独立图层，性能好；TextureView 走 GPU 合成，兼容性好。 */
    private static final int RENDER_SURFACE = 0;
    private static final int RENDER_TEXTURE = 1;

    private static final long BARS_HIDE_DELAY = 5000L;
    /** 关怀模式：信息条多留一会儿，老人看字慢，5 秒来不及读完。 */
    private static final long BARS_HIDE_DELAY_CARE = 10000L;
    private static final long TOAST_DELAY = 2600L;
    private static final long TOAST_DELAY_CARE = 4200L;
    private static final long LOAD_TIMEOUT = 18000L;
    /**
     * 首帧超时：音频已经在走、但一帧画面都没渲染出来的机器，
     * 8 秒后自动换一种渲染方式，而不是让用户对着黑屏干等。
     */
    private static final long FIRST_FRAME_TIMEOUT = 8000L;
    /** 系统内核没有可靠的首帧回调，宽容期给长一些，避免把「慢」误判成「黑」。 */
    private static final long FIRST_FRAME_TIMEOUT_SYS = 14000L;

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
    private boolean care;

    private AspectFrame aspect;
    private SurfaceView surface;
    private TextureView texture;
    private int renderKind = RENDER_SURFACE;
    /** ExoPlayer 是否已经真的把第一帧画到屏幕上——有声无画就是它一直是 false。 */
    private boolean firstFrameRendered;
    /** 自动回退到系统内核后置位，避免在 startPlayback 里又被 Exo 抢回去。 */
    private boolean forceSystemKernel;
    private String currentUrl;
    private String currentUa;
    /** 诊断用：当前流的编码、分辨率、码率、解码器。黑屏时靠它判断是源超了还是渲染出问题。 */
    private String diagMime;
    private int diagWidth;
    private int diagHeight;
    private int diagBitrate;
    private float diagFrameRate;
    private String diagDecoder;
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
    private TextView tvPanelHint;
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
        applyCareMode();

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
        installRenderView(wantedRenderKind());
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
        tvPanelHint = findViewById(R.id.tvPanelHint);
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
                // 关怀模式下关掉长按收藏：老人容易把“按住 OK”当成“按一下”，
                // 一抖就把常看的台取消收藏了。
                if (care) {
                    return;
                }
                Prefs.toggleFavorite(c.standardName);
                panelAdapter.notifyDataSetChanged();
                showToast(Prefs.isFavorite(c.standardName) ? "已收藏" : "已取消收藏");
            }
        });
        rvPanel.setAdapter(panelAdapter);
    }

    // ---------------- 渲染视图 ----------------

    /** auto 先按 SurfaceView 走；首帧超时后再自动换成 TextureView。 */
    private int wantedRenderKind() {
        return "texture".equals(Prefs.renderMode()) ? RENDER_TEXTURE : RENDER_SURFACE;
    }

    /** 重建渲染视图。两种视图类型不能互换，只能整个换掉。 */
    private void installRenderView(int kind) {
        renderKind = kind == RENDER_TEXTURE ? RENDER_TEXTURE : RENDER_SURFACE;
        if (aspect == null) {
            return;
        }
        aspect.removeAllViews();
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (renderKind == RENDER_TEXTURE) {
            texture = new TextureView(this);
            texture.setLayoutParams(lp);
            aspect.addView(texture);
            surface = null;
        } else {
            surface = new SurfaceView(this);
            surface.setLayoutParams(lp);
            aspect.addView(surface);
            texture = null;
        }
        aspect.requestLayout();
    }

    /**
     * 有声无画的兜底。4.1~4.3 的很多盒子（Amlogic / Rockchip 老固件）会出现
     * 「解码器初始化成功、音频正常、SurfaceView 上永远是黑的」，这时：
     *   1. 换 TextureView（走 GPU 合成，绕开老 SurfaceFlinger 的图层问题）
     *   2. 还不行就换系统内核（MediaPlayer 走另一套 ACodec 路径）
     */
    private final Runnable firstFrameTimeoutTask = new Runnable() {
        @Override
        public void run() {
            if (firstFrameRendered || errorVisible || channel == null || isFinishing()) {
                return;
            }
            if (usingSystemPlayer) {
                // 两种渲染方式 + 两套内核都试过还是黑的，基本可以断定是解码能力不够。
                // 但如果解码器已经报了尺寸，说明固件可能只是不爱发上屏回调，不打断播放。
                if (diagHeight <= 0) {
                    onPlaybackFailed(blackScreenHint());
                } else {
                    showToast("画面还没出来？按菜单 →「播放诊断」看看原因");
                }
                return;
            }
            int step = noFirstFrameFallback();
            if (step == 1) {
                showToast("画面没出来，改用兼容渲染…");
            } else if (step == 2) {
                showToast("画面仍未出现，换系统播放器…");
            }
            if (step != 0) {
                startPlayback();
            }
        }
    };

    /**
     * 黑屏时的解释文案。用户最需要知道的是「为什么」和「怎么办」：
     * 同一个源在 480p/720p 出画、1080p 黑屏，基本都是硬解器吃不下这个分辨率/编码。
     */
    String blackScreenHint() {
        StringBuilder sb = new StringBuilder("有声音、没有画面。");
        if (diagMime != null && diagMime.contains("hevc")) {
            sb.append("该源是 H.265(HEVC) 编码，本机多半不支持。");
        } else if (diagHeight >= 1080) {
            sb.append("该源是 ").append(diagHeight).append("p，超出本机硬解上限。");
        } else if (diagHeight > 0) {
            sb.append("该源分辨率 ").append(diagWidth).append("×").append(diagHeight)
                    .append("，本机解码器仍无法上屏。");
        } else {
            sb.append("本机解码器无法把该源画到屏幕上。");
        }
        sb.append("\n请按「换线路」换一个低码率源，");
        sb.append("或在设置里把「最高画质」调成 720p / 480p 再试。");
        return sb.toString();
    }


    /**
     * 执行一级回退，返回 1（换成 TextureView）/ 2（换成系统内核）/ 0（没有更多手段）。
     * 抽成独立方法，方便单测直接验证，不必真等 8 秒。
     */
    int noFirstFrameFallback() {
        if (forceSystemKernel) {
            // 已经退到系统内核，再换渲染方式也没有意义，避免来回折腾
            return 0;
        }
        if (renderKind != RENDER_TEXTURE) {
            if ("auto".equals(Prefs.renderMode())) {
                // 记到设置里，换台时不用再等这 8 秒
                Prefs.setRenderMode("texture");
            }
            releasePlayers();
            installRenderView(RENDER_TEXTURE);
            return 1;
        }
        if (!forceSystemKernel) {
            forceSystemKernel = true;
            releasePlayers();
            installRenderView(RENDER_SURFACE);
            return 2;
        }
        return 0;
    }

    // ---------------- 播放内核 ----------------

    private void startPlayback() {
        releasePlayers();
        hideError();
        firstFrameRendered = false;
        resetDiag();
        handler.removeCallbacks(firstFrameTimeoutTask);
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
        currentUrl = url;
        currentUa = ua;
        showLoading(true, "正在连接 " + line.displayName() + "…");

        handler.removeCallbacks(loadTimeoutTask);
        handler.postDelayed(loadTimeoutTask, LOAD_TIMEOUT);

        boolean system = forceSystemKernel || "sys".equals(Prefs.kernel()) || isSystemOnly(url);
        // 系统 MediaPlayer 只能往 SurfaceView 上画，纹理视图要换回来
        if (system && renderKind == RENDER_TEXTURE) {
            installRenderView(RENDER_SURFACE);
        }
        if (system) {
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
            if (renderKind == RENDER_TEXTURE && texture != null) {
                exo.setVideoTextureView(texture);
            } else {
                if (surface == null) {
                    installRenderView(RENDER_SURFACE);
                }
                exo.setVideoSurfaceView(surface);
            }
            exo.addListener(exoListener);
            applyQualityLimit(exo);

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
            if (surface == null) {
                installRenderView(RENDER_SURFACE);
            }
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
                    readSystemTrackInfo(mp);
                    try {
                        mp.start();
                    } catch (Throwable ignored) {
                    }
                    // 系统内核没有 onRenderedFirstFrame，只能靠 MEDIA_INFO_VIDEO_RENDERING_START，
                    // 老固件给得慢，所以宽容期比 Exo 那条链路长一点，免得误判成黑屏。
                    handler.removeCallbacks(firstFrameTimeoutTask);
                    handler.postDelayed(firstFrameTimeoutTask, FIRST_FRAME_TIMEOUT_SYS);
                }
            });
            sysPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                    if (width > 0 && height > 0) {
                        diagWidth = width;
                        diagHeight = height;
                    }
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
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                            // 系统内核真正上屏的信号
                            firstFrameRendered = true;
                            handler.removeCallbacks(firstFrameTimeoutTask);
                        }
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

    // ---------------- 画质上限与诊断 ----------------

    /**
     * 按「最高画质」给视频轨道设上限。
     * HLS 多码率源默认会挑最高码率那一档，老盒子挑到 1080p 就是「有声无画」；
     * 限制之后 ExoPlayer 会自动选 720p / 480p 那一路。单码率源降不下来，只能换线路。
     */
    private void applyQualityLimit(ExoPlayer player) {
        try {
            player.setTrackSelectionParameters(qualityParams(player.getTrackSelectionParameters()));
        } catch (Throwable ignored) {
        }
    }

    /** 抽成静态方法，单测可以直接验证画质上限算得对不对。 */
    static TrackSelectionParameters qualityParams(TrackSelectionParameters base) {
        TrackSelectionParameters.Builder builder = base.buildUpon();
        int maxHeight = Prefs.maxVideoHeight();
        if (maxHeight > 0) {
            // 宽度按 2 倍高度放宽，16:9 和 4:3 的源都不会被误伤
            builder.setMaxVideoSize(maxHeight * 2, maxHeight);
            int maxBitrate = Prefs.maxVideoBitrate();
            if (maxBitrate > 0) {
                builder.setMaxVideoBitrate(maxBitrate);
            }
        }
        return builder.build();
    }

    private void resetDiag() {
        diagMime = null;
        diagWidth = 0;
        diagHeight = 0;
        diagBitrate = 0;
        diagFrameRate = 0f;
        diagDecoder = null;
    }

    private void updateDiagFromTracks(Tracks tracks) {
        if (tracks == null) {
            return;
        }
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO || !group.isSelected()
                    || group.length == 0) {
                continue;
            }
            Format format = group.getTrackFormat(0);
            diagMime = format.sampleMimeType;
            diagWidth = format.width;
            diagHeight = format.height;
            diagBitrate = format.bitrate == Format.NO_VALUE ? 0 : format.bitrate;
            diagFrameRate = format.frameRate == Format.NO_VALUE ? 0f : format.frameRate;
            diagDecoder = decoderName(format.sampleMimeType);
            return;
        }
    }

    /** 系统内核拿不到 ExoPlayer 的 Format，只能从 TrackInfo 里抠编码和分辨率。 */
    private void readSystemTrackInfo(MediaPlayer mp) {
        try {
            MediaPlayer.TrackInfo[] infos = mp.getTrackInfo();
            if (infos != null) {
                for (MediaPlayer.TrackInfo info : infos) {
                    if (info == null
                            || info.getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) {
                        continue;
                    }
                    android.media.MediaFormat f = info.getFormat();
                    if (f == null) {
                        continue;
                    }
                    if (f.containsKey("mime")) {
                        diagMime = f.getString("mime");
                    }
                    if (f.containsKey("width")) {
                        diagWidth = f.getInteger("width");
                    }
                    if (f.containsKey("height")) {
                        diagHeight = f.getInteger("height");
                    }
                    if (f.containsKey("bitrate")) {
                        diagBitrate = f.getInteger("bitrate");
                    }
                    diagDecoder = decoderName(diagMime);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        // TrackInfo 不可用时退回 MediaPlayer 自己报的尺寸
        try {
            if (mp.getVideoWidth() > 0) {
                diagWidth = mp.getVideoWidth();
                diagHeight = mp.getVideoHeight();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String decoderName(String mime) {
        if (mime == null) {
            return null;
        }
        try {
            List<MediaCodecInfo> infos =
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false);
            if (!infos.isEmpty()) {
                MediaCodecInfo first = infos.get(0);
                return first.name + (first.softwareOnly ? "（软解）" : "（硬解）");
            }
        } catch (Throwable ignored) {
        }
        return "无可用解码器";
    }

    private static String codecLabel(String mime) {
        if (mime == null) {
            return "未知";
        }
        if (mime.contains("avc")) {
            return "H.264（AVC）";
        }
        if (mime.contains("hevc")) {
            return "H.265（HEVC）";
        }
        if (mime.contains("av01")) {
            return "AV1";
        }
        if (mime.contains("vp9")) {
            return "VP9";
        }
        if (mime.contains("mpeg4") || mime.contains("mp4v")) {
            return "MPEG-4";
        }
        return mime;
    }

    private String qualityLabel() {
        String q = Prefs.maxQuality();
        if ("1080".equals(q)) {
            return "1080p";
        }
        if ("720".equals(q)) {
            return "720p";
        }
        if ("480".equals(q)) {
            return "480p";
        }
        return Prefs.maxVideoHeight() > 0 ? "自动（" + Prefs.maxVideoHeight() + "p 封顶）" : "自动（不限制）";
    }

    /**
     * 播放诊断。老盒子上「为什么这个台黑屏」靠自己猜太费劲，
     * 把编码、分辨率、解码器直接列出来，一眼就能看出是源超了还是渲染出问题。
     */
    String diagText() {
        StringBuilder sb = new StringBuilder();
        sb.append("内核：").append(usingSystemPlayer ? "系统播放器" : "ExoPlayer");
        sb.append("　渲染：")
                .append(renderKind == RENDER_TEXTURE ? "TextureView" : "SurfaceView");
        sb.append('\n');
        if (channel != null && channel.currentLine() != null) {
            sb.append("线路：").append(channel.currentLine().displayName());
            sb.append("（共 ").append(channel.lineCount()).append(" 条）\n");
        }
        sb.append("编码：").append(codecLabel(diagMime)).append('\n');
        if (diagWidth > 0 && diagHeight > 0) {
            sb.append("分辨率：").append(diagWidth).append("×").append(diagHeight);
            if (diagFrameRate > 0) {
                sb.append(" @").append(Math.round(diagFrameRate)).append("fps");
            }
            sb.append('\n');
        } else {
            sb.append("分辨率：尚未取到\n");
        }
        if (diagBitrate > 0) {
            sb.append("码率：").append(diagBitrate / 1000).append(" kbps\n");
        }
        if (diagDecoder != null) {
            sb.append("解码器：").append(diagDecoder).append('\n');
        }
        sb.append("画面：").append(firstFrameRendered ? "已出画" : "未出画（只有声音）").append('\n');
        sb.append("画质上限：").append(qualityLabel());
        return sb.toString();
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
                // READY 只代表能解码出数据，不保证画面真的上了屏
                handler.removeCallbacks(firstFrameTimeoutTask);
                handler.postDelayed(firstFrameTimeoutTask, FIRST_FRAME_TIMEOUT);
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

        @Override
        public void onTracksChanged(Tracks tracks) {
            updateDiagFromTracks(tracks);
        }

        @Override
        public void onRenderedFirstFrame() {
            firstFrameRendered = true;
            handler.removeCallbacks(firstFrameTimeoutTask);
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
            handler.postDelayed(hideBarsTask, care ? BARS_HIDE_DELAY_CARE : BARS_HIDE_DELAY);
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
        handler.postDelayed(hideToastTask, care ? TOAST_DELAY_CARE : TOAST_DELAY);
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

    /**
     * 关怀模式：把「要看清的字」放大，把「可以不要的字」收起来。
     * 面板 380dp→520dp、条目 52dp→80dp / 14sp→24sp，信息条字号整体上一档，
     * 底栏去掉“接下来”只留当前节目，减少屏幕上的信息量。
     */
    private void applyCareMode() {
        care = Prefs.careMode();
        if (panelAdapter != null) {
            panelAdapter.setLarge(care);
        }
        if (!care) {
            return;
        }
        ViewGroup.LayoutParams lp = channelPanel.getLayoutParams();
        if (lp != null) {
            lp.width = Ui.dp(this, 520);
            channelPanel.setLayoutParams(lp);
        }
        tvPanelTitle.setTextSize(24);
        if (tvPanelHint != null) {
            tvPanelHint.setText("上下键选择 · OK 播放 · 返回关闭");
            tvPanelHint.setTextSize(16);
        }
        tvChanNo.setTextSize(17);
        tvChanName.setTextSize(30);
        tvClock.setTextSize(20);
        tvNowTitle.setTextSize(23);
        tvNowTime.setTextSize(18);
        tvNext.setVisibility(View.GONE);
        tvLoading.setTextSize(18);
        tvError.setTextSize(16);
        tvToast.setTextSize(21);
        tvToast.setPadding(Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 22), Ui.dp(this, 12));
    }

    // ---------------- 频道面板 ----------------

    private void openPanel() {
        openPanel(false);
    }

    /**
     * @param withInfo true 表示同时亮出信息条（OK 键走这条）。
     *                 面板占右侧整高，底栏会和它叠在一起，所以只保留顶部信息条。
     */
    private void openPanel(boolean withInfo) {
        if (channel == null) {
            return;
        }
        panelOpen = true;
        handler.removeCallbacks(hideBarsTask);
        if (withInfo) {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.GONE);
            refreshInfo();
        } else {
            hideBars();
        }
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
                // 返回：收起面板，同时把信息条收掉，画面干净
                closePanel();
                handler.removeCallbacks(hideBarsTask);
                hideBars();
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                closePanel();
                showOptions();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                // 右键呼出、再按一次收起，和“回到画面”的直觉一致
                closePanel();
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
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // OK 键：一次同时做两件事——亮出信息条 + 呼出频道面板。
                // 面板打开时 OK 归列表（播放选中的台），见上面的 panelOpen 分支。
                openPanel(true);
                return true;
            case KeyEvent.KEYCODE_INFO:
                // 部分遥控器带“信息/INFO”键，单独用它切换信息条
                toggleBars();
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

    private void toggleBars() {
        if (topBar.getVisibility() == View.VISIBLE) {
            handler.removeCallbacks(hideBarsTask);
            hideBars();
        } else {
            showBars();
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
                "最高画质（" + qualityLabel() + "）",
                "画面比例",
                "播放诊断",
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
                            chooseQuality();
                        } else if (which == 3) {
                            chooseAspect();
                        } else if (which == 4) {
                            showDiag();
                        } else if (which == 5) {
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

    /** 最高画质：发现某个台黑屏时，就地降一档不用跑回设置页。 */
    private void chooseQuality() {
        final String[] values = {"auto", "1080", "720", "480"};
        final String[] items = {"自动" + (Prefs.maxVideoHeight() > 0
                ? "（本机 " + Prefs.maxVideoHeight() + "p 封顶）" : "（不限制）"),
                "1080p", "720p（推荐老盒子）", "480p（最稳）"};
        int checked = 0;
        String current = Prefs.maxQuality();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("最高画质")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setMaxQuality(values[which]);
                        dialog.dismiss();
                        // 换档后重开一次，ExoPlayer 才会按新上限重选轨道
                        startPlayback();
                        showToast("最高画质：" + qualityLabel());
                    }
                })
                .show();
    }

    private void showDiag() {
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("播放诊断")
                .setMessage(diagText())
                .setPositiveButton("知道了", null)
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
