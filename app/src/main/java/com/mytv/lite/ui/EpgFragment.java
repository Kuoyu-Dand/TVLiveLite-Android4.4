package com.mytv.lite.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mytv.lite.Prefs;
import com.mytv.lite.R;
import com.mytv.lite.data.Channel;
import com.mytv.lite.data.ChannelRepository;
import com.mytv.lite.data.EpgRepository;
import com.mytv.lite.ui.adapter.Adapters;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 节目单：左侧频道，右侧当天节目
 */
public class EpgFragment extends Fragment {

    private RecyclerView rvChannels;
    private RecyclerView rvProgrammes;
    private TextView tvTitle;
    private TextView tvDate;
    private Adapters.PanelChannelAdapter channelAdapter;
    private Adapters.ProgrammeAdapter programmeAdapter;
    private String currentName;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_epg, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rvChannels = view.findViewById(R.id.rvChannels);
        rvProgrammes = view.findViewById(R.id.rvProgrammes);
        tvTitle = view.findViewById(R.id.tvTitle);
        tvDate = view.findViewById(R.id.tvDate);

        channelAdapter = new Adapters.PanelChannelAdapter(new Adapters.OnChannelAction() {
            @Override
            public void onClick(Channel channel) {
                currentName = channel.name;
                showProgrammes(channel);
            }

            @Override
            public void onLongClick(Channel channel) {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).play(channel);
                }
            }
        });
        rvChannels.setLayoutManager(new LinearLayoutManager(getActivity()));
        rvChannels.setAdapter(channelAdapter);

        programmeAdapter = new Adapters.ProgrammeAdapter();
        rvProgrammes.setLayoutManager(new LinearLayoutManager(getActivity()));
        rvProgrammes.setAdapter(programmeAdapter);

        tvDate.setText(new SimpleDateFormat("MM月dd日", Locale.getDefault()).format(new Date()));

        if (getArguments() != null) {
            currentName = getArguments().getString("channel");
        }
        if (currentName == null) {
            Channel last = ChannelRepository.findByName(Prefs.getString(
                    Prefs.K_LAST_CHANNEL, ""));
            if (last != null) {
                currentName = last.name;
            }
        }

        List<Channel> all = ChannelRepository.all();
        channelAdapter.submit(all, currentName == null ? null
                : com.mytv.lite.data.ChannelAlias.key(currentName));

        Channel selected = currentName == null ? null : ChannelRepository.findByName(currentName);
        if (selected == null && !all.isEmpty()) {
            selected = all.get(0);
        }
        if (selected != null) {
            showProgrammes(selected);
        }

        if (Prefs.epgEnabled() && !EpgRepository.hasData()) {
            EpgRepository.loadAsync(getActivity(), false, new EpgRepository.Callback() {
                @Override
                public void onResult(String error) {
                    if (getView() == null) {
                        return;
                    }
                    if (error != null) {
                        Ui.toast(getActivity(), "节目单加载失败：" + error);
                    }
                    Channel c = currentName == null ? null
                            : ChannelRepository.findByName(currentName);
                    if (c != null) {
                        showProgrammes(c);
                    }
                }
            });
        }
    }

    private void showProgrammes(Channel channel) {
        tvTitle.setText(channel.name);
        List<EpgRepository.Programme> list = EpgRepository.programmes(channel);
        programmeAdapter.submit(list);
        if (list.isEmpty()) {
            tvDate.setText(Prefs.epgEnabled() ? "暂无节目信息" : "节目单已关闭");
        } else {
            tvDate.setText(new SimpleDateFormat("MM月dd日", Locale.getDefault())
                    .format(new Date()));
        }
    }
}
