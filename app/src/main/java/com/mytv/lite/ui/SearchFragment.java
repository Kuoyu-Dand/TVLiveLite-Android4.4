package com.mytv.lite.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import com.mytv.lite.ui.adapter.Adapters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 频道搜索
 */
public class SearchFragment extends Fragment {

    private EditText etSearch;
    private TextView tvCount;
    private RecyclerView rvResults;
    private Adapters.ChannelAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        etSearch = view.findViewById(R.id.etSearch);
        tvCount = view.findViewById(R.id.tvCount);
        rvResults = view.findViewById(R.id.rvResults);

        adapter = new Adapters.ChannelAdapter(new Adapters.OnChannelAction() {
            @Override
            public void onClick(Channel channel) {
                Prefs.putString(Prefs.K_LAST_CHANNEL, channel.standardName);
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).play(channel);
                }
            }

            @Override
            public void onLongClick(Channel channel) {
                Prefs.toggleFavorite(channel.standardName);
                adapter.notifyDataSetChanged();
                Ui.toast(getActivity(), Prefs.isFavorite(channel.standardName)
                        ? "已收藏" : "已取消收藏");
            }
        });
        rvResults.setLayoutManager(new LinearLayoutManager(getActivity()));
        rvResults.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        filter("");
        etSearch.requestFocus();
    }

    private void filter(String query) {
        String q = query.trim().toLowerCase(Locale.getDefault());
        List<Channel> result = new ArrayList<Channel>();
        if (q.isEmpty()) {
            result.addAll(ChannelRepository.all());
        } else {
            for (Channel c : ChannelRepository.all()) {
                String name = c.name == null ? "" : c.name.toLowerCase(Locale.getDefault());
                String std = c.standardName == null ? ""
                        : c.standardName.toLowerCase(Locale.getDefault());
                if (name.contains(q) || std.contains(q)) {
                    result.add(c);
                }
            }
        }
        adapter.submit(result);
        tvCount.setText(q.isEmpty() ? "共 " + result.size() + " 个频道"
                : "找到 " + result.size() + " 个频道");
    }
}
