package com.mytv.lite.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mytv.lite.Prefs;
import com.mytv.lite.R;
import com.mytv.lite.data.Channel;
import com.mytv.lite.data.ChannelGroup;
import com.mytv.lite.data.ChannelRepository;
import com.mytv.lite.data.EpgRepository;
import com.mytv.lite.ui.adapter.Adapters;

import java.util.ArrayList;
import java.util.List;

/**
 * 全部频道 / 收藏：左侧分组，右侧频道网格
 */
public class ChannelsFragment extends Fragment {

    public static final int MODE_ALL = 0;
    public static final int MODE_FAVORITE = 1;

    private static final String ARG_MODE = "mode";
    private static final String GROUP_ALL = "全部频道";
    private static final String GROUP_FAV = "收藏";

    private int mode = MODE_ALL;
    private int selectedGroup = 0;
    private String selectedGroupName = GROUP_ALL;

    private RecyclerView rvGroups;
    private RecyclerView rvChannels;
    private TextView tvTitle;
    private TextView tvCount;
    private Adapters.GroupAdapter groupAdapter;
    private Adapters.ChannelAdapter channelAdapter;
    private final List<Adapters.GroupAdapter.Item> groupItems =
            new ArrayList<Adapters.GroupAdapter.Item>();

    public static ChannelsFragment newInstance(int mode) {
        ChannelsFragment f = new ChannelsFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_MODE, mode);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mode = getArguments().getInt(ARG_MODE, MODE_ALL);
            if (mode == MODE_FAVORITE) {
                selectedGroupName = GROUP_FAV;
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_channels, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvTitle = view.findViewById(R.id.tvTitle);
        tvCount = view.findViewById(R.id.tvCount);
        rvGroups = view.findViewById(R.id.rvGroups);
        rvChannels = view.findViewById(R.id.rvChannels);

        groupAdapter = new Adapters.GroupAdapter(new Adapters.GroupAdapter.OnGroupClick() {
            @Override
            public void onGroupClick(int position) {
                if (position < 0 || position >= groupItems.size()) {
                    return;
                }
                selectedGroup = position;
                selectedGroupName = groupItems.get(position).name;
                groupAdapter.setSelected(position);
                applyGroup();
            }
        });
        rvGroups.setLayoutManager(new LinearLayoutManager(getActivity()));
        rvGroups.setAdapter(groupAdapter);

        channelAdapter = new Adapters.ChannelAdapter(new Adapters.OnChannelAction() {
            @Override
            public void onClick(Channel channel) {
                play(channel);
            }

            @Override
            public void onLongClick(Channel channel) {
                showMenu(channel);
            }
        });
        rvChannels.setLayoutManager(new GridLayoutManager(getActivity(), columns()));
        rvChannels.setAdapter(channelAdapter);

        view.findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.toast(getActivity(), "正在刷新订阅源…");
                reload(true);
            }
        });

        rebuild();
        reload(false);
    }

    private int columns() {
        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        int col = (int) ((widthDp - 260f) / 185f);
        return Math.max(3, Math.min(6, col));
    }

    private void play(Channel channel) {
        Prefs.putString(Prefs.K_LAST_CHANNEL, channel.standardName);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).play(channel);
        }
    }

    private void rebuild() {
        groupItems.clear();
        List<Channel> all = ChannelRepository.all();
        List<Channel> fav = ChannelRepository.favorites();

        if (mode == MODE_FAVORITE) {
            add(GROUP_FAV, fav.size());
        } else {
            add(GROUP_ALL, all.size());
            add(GROUP_FAV, fav.size());
            for (ChannelGroup g : ChannelRepository.groups()) {
                add(g.name, g.channels.size());
            }
        }

        int index = 0;
        for (int i = 0; i < groupItems.size(); i++) {
            if (groupItems.get(i).name.equals(selectedGroupName)) {
                index = i;
                break;
            }
        }
        selectedGroup = index;
        selectedGroupName = groupItems.get(index).name;
        groupAdapter.submit(groupItems, index);
        applyGroup();
    }

    private void add(String name, int count) {
        Adapters.GroupAdapter.Item item = new Adapters.GroupAdapter.Item();
        item.name = name;
        item.count = count;
        groupItems.add(item);
    }

    private void applyGroup() {
        List<Channel> out = new ArrayList<Channel>();
        if (GROUP_ALL.equals(selectedGroupName)) {
            out.addAll(ChannelRepository.all());
        } else if (GROUP_FAV.equals(selectedGroupName)) {
            out.addAll(ChannelRepository.favorites());
        } else {
            for (ChannelGroup g : ChannelRepository.groups()) {
                if (g.name.equals(selectedGroupName)) {
                    out.addAll(g.channels);
                    break;
                }
            }
        }
        channelAdapter.submit(out);
        tvTitle.setText(selectedGroupName);
        tvCount.setText(out.size() + " 个频道");
        if (out.isEmpty() && GROUP_FAV.equals(selectedGroupName)) {
            tvCount.setText("还没有收藏，长按频道即可收藏");
        }
    }

    private void reload(final boolean force) {
        if (!ChannelRepository.all().isEmpty() && !force) {
            rebuild();
        }
        if (ChannelRepository.isLoading()) {
            return;
        }
        ChannelRepository.loadAsync(getActivity(), force, new ChannelRepository.Callback() {
            @Override
            public void onResult(String error) {
                if (getView() == null) {
                    return;
                }
                rebuild();
                if (error != null) {
                    Ui.toast(getActivity(), "加载失败：" + error);
                }
                if (Prefs.epgEnabled() && !EpgRepository.hasData()) {
                    EpgRepository.loadAsync(getActivity(), false, new EpgRepository.Callback() {
                        @Override
                        public void onResult(String e) {
                            if (channelAdapter != null) {
                                channelAdapter.notifyDataSetChanged();
                            }
                        }
                    });
                }
            }
        });
    }

    private void showMenu(final Channel channel) {
        if (getActivity() == null) {
            return;
        }
        final boolean fav = Prefs.isFavorite(channel.standardName);
        String[] items = {"播放", fav ? "取消收藏" : "收藏", "复制播放地址", "查看节目单"};
        new AlertDialog.Builder(getActivity(), R.style.DialogTheme)
                .setTitle(channel.name + "  (" + channel.lineCount() + " 条线路)")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            play(channel);
                        } else if (which == 1) {
                            Prefs.toggleFavorite(channel.standardName);
                            channelAdapter.notifyDataSetChanged();
                            Ui.toast(getActivity(), fav ? "已取消收藏" : "已收藏");
                        } else if (which == 2) {
                            ClipboardManager cm = (ClipboardManager) getActivity()
                                    .getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null && channel.currentLine() != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("url",
                                        channel.currentLine().playableUrl()));
                                Ui.toast(getActivity(), "地址已复制");
                            }
                        } else {
                            EpgFragment f = new EpgFragment();
                            Bundle b = new Bundle();
                            b.putString("channel", channel.name);
                            f.setArguments(b);
                            if (getActivity() instanceof MainActivity) {
                                ((MainActivity) getActivity()).show(f, true);
                            }
                        }
                    }
                })
                .show();
    }
}
