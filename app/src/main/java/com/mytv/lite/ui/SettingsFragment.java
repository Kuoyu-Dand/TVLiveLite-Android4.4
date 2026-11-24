package com.mytv.lite.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mytv.lite.Prefs;
import com.mytv.lite.R;
import com.mytv.lite.data.ChannelRepository;
import com.mytv.lite.data.EpgRepository;
import com.mytv.lite.data.EpgSource;
import com.mytv.lite.data.IptvSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页。为了在 API 19 上少写一堆 layout，这里用代码构建行。
 */
public class SettingsFragment extends Fragment {

    private RecyclerView rv;
    private RowAdapter adapter;
    private final List<Row> rows = new ArrayList<Row>();

    static class Row {
        String title;
        String value;
        String sub;
        View.OnClickListener action;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rv = view.findViewById(R.id.rvSettings);
        rv.setLayoutManager(new LinearLayoutManager(getActivity()));
        adapter = new RowAdapter();
        rv.setAdapter(adapter);
        buildRows();
    }

    @Override
    public void onResume() {
        super.onResume();
        buildRows();
    }

    private void add(String title, String value, String sub, View.OnClickListener action) {
        Row row = new Row();
        row.title = title;
        row.value = value;
        row.sub = sub;
        row.action = action;
        rows.add(row);
    }

    private void buildRows() {
        rows.clear();

        add("关怀模式", Prefs.careMode() ? "开" : "关",
                Prefs.careMode()
                        ? "频道面板与信息条已放大，界面从简；关闭后恢复常规字号"
                        : "照顾老年视力：面板字号放大、界面从简，适合长辈使用",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleCareMode();
                    }
                });
        add("打开软件直接播放", Prefs.autoPlayLast() ? "开" : "关",
                "开启后打开软件跳过首页，直接播放上次关闭时的频道",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.setAutoPlayLast(!Prefs.autoPlayLast());
                        buildRows();
                    }
                });

        add("直播源管理", countIptv() + " 个源", "支持 m3u（#EXTM3U）与 txt（#genre#）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        open(new SourcesFragment(SourcesFragment.MODE_IPTV));
                    }
                });
        add("节目单源管理", countEpg() + " 个源", "XMLTV 格式，支持 .xml 与 .xml.gz",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        open(new SourcesFragment(SourcesFragment.MODE_EPG));
                    }
                });
        add("节目单显示", Prefs.epgEnabled() ? "已开启" : "已关闭",
                "关闭后不联网、不解析节目单，老设备更流畅",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.putBool(Prefs.K_EPG_ON, !Prefs.epgEnabled());
                        buildRows();
                    }
                });

        add("播放器内核", "exo".equals(Prefs.kernel()) ? "ExoPlayer（推荐）" : "系统播放器",
                "部分老盒子用系统播放器更稳",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseKernel();
                    }
                });
        add("解码方式", Prefs.softwareDecode() ? "软解优先" : "自动（硬解优先）",
                "花屏 / 卡顿时可尝试软解",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseDecode();
                    }
                });
        add("画面渲染", renderName(),
                "有声音没画面时切到「兼容」试试，仍不行就换系统播放器",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseRender();
                    }
                });
        add("最高画质", qualityName(),
                "有的台只有声音没画面？那多半是源的分辨率/编码超出本机硬解上限，"
                        + "调成 720p 或 480p 再试",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseQuality();
                    }
                });
        add("画面比例", aspectName(), null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseAspect();
            }
        });
        add("请求 UA", Prefs.ua().isEmpty() ? "默认" : "自定义",
                "部分源需要特定 UA 才能播放",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Ui.input(getActivity(), "请求 UA", "留空则使用默认 UA",
                                Prefs.ua(), false, new Ui.OnText() {
                                    @Override
                                    public void onText(String text) {
                                        Prefs.putString(Prefs.K_UA, text);
                                        buildRows();
                                    }
                                });
                    }
                });
        add("网络超时", (Prefs.timeoutMs() / 1000) + " 秒", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseTimeout();
                    }
                });
        add("订阅缓存时长", Prefs.cacheHours() + " 小时",
                "到期后重新拉取订阅源；点“刷新订阅源”可立即刷新",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseCacheHours();
                    }
                });

        add("显示台标", Prefs.showLogo() ? "开" : "关", "台标缺失时会从公开台标库补齐",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.putBool(Prefs.K_SHOW_LOGO, !Prefs.showLogo());
                        buildRows();
                    }
                });
        add("开机自动启动", Prefs.bootAutoStart() ? "开" : "关", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Prefs.putBool(Prefs.K_BOOT, !Prefs.bootAutoStart());
                        buildRows();
                    }
                });

        add("清除缓存", null, "清空订阅源缓存与节目单缓存，下次进入重新拉取",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Ui.confirm(getActivity(), "清除缓存", "确定要清空所有缓存吗？",
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        ChannelRepository.clearCache(getActivity());
                                        EpgRepository.clearCache(getActivity());
                                        Ui.toast(getActivity(), "缓存已清空");
                                    }
                                });
                    }
                });
        add("关于", "v" + versionName(), "电视直播 Lite · 为 Android 4.4 适配",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showAbout();
                    }
                });

        adapter.notifyDataSetChanged();
    }

    private String versionName() {
        try {
            return getActivity().getPackageManager()
                    .getPackageInfo(getActivity().getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0.0";
        }
    }

    private int countIptv() {
        int n = 0;
        for (IptvSource s : Prefs.getIptvSources()) {
            if (s.enabled && s.url != null && !s.url.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private int countEpg() {
        int n = 0;
        for (EpgSource s : Prefs.getEpgSources()) {
            if (s.enabled && s.url != null && !s.url.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private String aspectName() {
        String a = Prefs.aspect();
        if ("fill".equals(a)) {
            return "拉伸铺满";
        }
        if ("zoom".equals(a)) {
            return "裁剪铺满";
        }
        return "适应屏幕";
    }

    private void open(Fragment fragment) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).show(fragment, true);
        }
    }

    /**
     * 关怀模式：一次开关同时把「大字号」和「直进上次频道」都打开，
     * 长辈只要按一下遥控器，不用再单独记第二个开关。
     */
    private void toggleCareMode() {
        boolean on = !Prefs.careMode();
        Prefs.setCareMode(on);
        if (on) {
            boolean autoPlay = Prefs.autoPlayLast();
            Prefs.setAutoPlayLast(true);
            buildRows();
            if (autoPlay) {
                Ui.toast(getActivity(), "关怀模式已开启：字号放大、界面从简");
            } else {
                Ui.toast(getActivity(), "关怀模式已开启，并同时打开了“打开软件直接播放”");
            }
        } else {
            buildRows();
            Ui.toast(getActivity(), "已退出关怀模式");
        }
    }

    private void chooseKernel() {
        final String[] items = {"ExoPlayer（推荐）", "系统播放器"};
        int checked = "sys".equals(Prefs.kernel()) ? 1 : 0;
        new AlertDialog.Builder(getActivity(), R.style.DialogTheme)
                .setTitle("播放器内核")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.putString(Prefs.K_KERNEL, which == 1 ? "sys" : "exo");
                        dialog.dismiss();
                        buildRows();
                    }
                })
                .show();
    }

    private String renderName() {
        String mode = Prefs.renderMode();
        if ("texture".equals(mode)) {
            return "兼容（TextureView）";
        }
        if ("surface".equals(mode)) {
            return "性能优先（SurfaceView）";
        }
        return "自动（黑屏时自己换）";
    }

    private void chooseRender() {
        final String[] items = {"自动（推荐，检测到黑屏自动换）",
                "性能优先（SurfaceView）", "兼容（TextureView，有声音没画面时选它）"};
        String mode = Prefs.renderMode();
        int checked = "surface".equals(mode) ? 1 : "texture".equals(mode) ? 2 : 0;
        new AlertDialog.Builder(getActivity(), R.style.DialogTheme)
                .setTitle("画面渲染")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setRenderMode(which == 1 ? "surface" : which == 2 ? "texture" : "auto");
                        dialog.dismiss();
                        buildRows();
                    }
                })
                .show();
    }

    private void chooseDecode() {
        final String[] items = {"自动（硬解优先）", "软解优先（兼容性更好）"};
        int checked = Prefs.softwareDecode() ? 1 : 0;
        new AlertDialog.Builder(getActivity(), R.style.DialogTheme)
                .setTitle("解码方式")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.putString(Prefs.K_DECODE, which == 1 ? "sw" : "auto");
                        dialog.dismiss();
                        buildRows();
                    }
                })
                .show();
    }

    private String qualityName() {
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
        int cap = Prefs.maxVideoHeight();
        return cap > 0 ? "自动（" + cap + "p 封顶）" : "自动（不限制）";
    }

    private void chooseQuality() {
        final String[] values = {"auto", "1080", "720", "480"};
        final String[] items = {"自动（4.4 以下默认 720p 封顶）", "1080p", "720p（推荐老盒子）",
                "480p（最稳）"};
        int checked = 0;
        String current = Prefs.maxQuality();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(getActivity(), R.style.DialogTheme)
                .setTitle("最高画质")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.setMaxQuality(values[which]);
                        dialog.dismiss();
                        buildRows();
                        Ui.toast(getActivity(), "最高画质：" + qualityName());
                    }
                })
                .show();
    }

    private void chooseAspect() {
        final String[] items = {"适应屏幕（保持比例）", "拉伸铺满", "裁剪铺满"};
        String a = Prefs.aspect();
        int checked = "fill".equals(a) ? 1 : ("zoom".equals(a) ? 2 : 0);
        new AlertDialog.Builder(getActivity(), R.style.DialogTheme)
                .setTitle("画面比例")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.putString(Prefs.K_ASPECT,
                                which == 1 ? "fill" : (which == 2 ? "zoom" : "fit"));
                        dialog.dismiss();
                        buildRows();
                    }
                })
                .show();
    }

    private void chooseTimeout() {
        final int[] values = {5000, 10000, 15000, 30000};
        final String[] items = {"5 秒", "10 秒", "15 秒", "30 秒"};
        int checked = 2;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == Prefs.timeoutMs()) {
                checked = i;
            }
        }
        new AlertDialog.Builder(getActivity(), R.style.DialogTheme)
                .setTitle("网络超时")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.putInt(Prefs.K_TIMEOUT, values[which]);
                        dialog.dismiss();
                        buildRows();
                    }
                })
                .show();
    }

    private void chooseCacheHours() {
        final int[] values = {1, 6, 12, 24, 72};
        final String[] items = {"1 小时", "6 小时", "12 小时", "24 小时", "72 小时"};
        int checked = 3;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == Prefs.cacheHours()) {
                checked = i;
            }
        }
        new AlertDialog.Builder(getActivity(), R.style.DialogTheme)
                .setTitle("订阅缓存时长")
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Prefs.putInt(Prefs.K_CACHE_HOURS, values[which]);
                        dialog.dismiss();
                        buildRows();
                    }
                })
                .show();
    }

    private void showAbout() {
        String message = "电视直播 Lite v" + versionName()
                + " · 最低支持 Android 4.4 (API 19)\n\n"
                + "参考 mytv-android（天光云影）改造。为了让老设备跑得动，界面用原生 View "
                + "重写（原版是 Compose），播放器使用 ExoPlayer 2.18；"
                + "去掉了多屏同播、JS 转换源、WebView 源等重功能。\n\n"
                + "本软件不提供任何直播源，请自行添加合法订阅源。";

        View view = LayoutInflater.from(getActivity()).inflate(R.layout.dialog_about, null);
        TextView body = view.findViewById(R.id.tvAboutBody);
        body.setText(message);
        View qr = view.findViewById(R.id.ivQr);
        bindFeedback(view);

        // 540p / 480p 的盒子高度吃紧：先按内容量一遍，超了就缩二维码、精简正文，
        // 免得“知道了”被挤出对话框。
        int width = Ui.dp(getActivity(), 440);
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.9f);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (view.getMeasuredHeight() > maxHeight) {
            body.setText("电视直播 Lite v" + versionName() + " · 最低支持 Android 4.4\n\n"
                    + "参考 mytv-android（天光云影）改造，界面用原生 View 重写，"
                    + "播放器使用 ExoPlayer 2.18。\n\n"
                    + "本软件不提供任何直播源。");
            int small = Ui.dp(getActivity(), 84);
            ViewGroup.LayoutParams lp = qr.getLayoutParams();
            lp.width = small;
            lp.height = small;
            qr.setLayoutParams(lp);
            view.requestLayout();
        }

        final AlertDialog dialog = new AlertDialog.Builder(getActivity(), R.style.DialogTheme)
                .setView(view)
                .create();
        dialog.show();
        view.findViewById(R.id.btnAboutOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
    }

    /**
     * 反馈邮箱：地址单独上强调色，触屏/鼠标设备点一下就能复制——
     * 电视上用遥控打不了字，能看到就够，复制是顺手的加分项。
     */
    private void bindFeedback(View view) {
        final TextView feedback = view.findViewById(R.id.tvFeedback);
        if (feedback == null) {
            return;
        }
        final String mail = "kuoyu@163.com";
        String text = "问题反馈  " + mail;
        SpannableString span = new SpannableString(text);
        int start = text.indexOf(mail);
        if (start >= 0) {
            span.setSpan(new ForegroundColorSpan(
                            getResources().getColor(R.color.accent)), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        feedback.setText(span);
        feedback.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.app.Activity activity = getActivity();
                if (activity == null) {
                    return;
                }
                try {
                    ClipboardManager cm = (ClipboardManager)
                            activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("邮箱", mail));
                    Ui.toast(activity, "已复制 " + mail);
                } catch (Throwable t) {
                    Ui.toast(activity, mail);
                }
            }
        });
    }

    private class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView value;
            final TextView sub;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.tvTitle);
                value = v.findViewById(R.id.tvValue);
                sub = v.findViewById(R.id.tvSub);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            final Row row = rows.get(position);
            boolean care = Prefs.careMode();
            holder.title.setTextSize(care ? 21 : 16);
            holder.value.setTextSize(care ? 19 : 14);
            holder.sub.setTextSize(care ? 16 : 12);
            holder.title.setText(row.title);
            if (row.value == null || row.value.isEmpty()) {
                holder.value.setVisibility(View.GONE);
            } else {
                holder.value.setVisibility(View.VISIBLE);
                holder.value.setText(row.value);
            }
            if (row.sub == null || row.sub.isEmpty()) {
                holder.sub.setVisibility(View.GONE);
            } else {
                holder.sub.setVisibility(View.VISIBLE);
                holder.sub.setText(row.sub);
            }
            holder.itemView.setOnClickListener(row.action);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
