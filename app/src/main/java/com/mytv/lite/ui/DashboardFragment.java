package com.mytv.lite.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mytv.lite.Prefs;
import com.mytv.lite.R;
import com.mytv.lite.data.Channel;
import com.mytv.lite.data.ChannelRepository;
import com.mytv.lite.data.EpgRepository;
import com.mytv.lite.data.IptvSource;
import com.mytv.lite.ui.adapter.Adapters;

import java.util.List;

/**
 * 首页：当前源、时间、直播入口、导航、最近观看
 */
public class DashboardFragment extends Fragment {

    private TextView tvSourceName;
    private TextView tvClock;
    private TextView tvDate;
    private TextView tvHeroTitle;
    private TextView tvHeroSub;
    private TextView tvRecentLabel;
    private LinearLayout navContainer;
    private RecyclerView rvRecent;

    private Adapters.ChannelAdapter recentAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean care;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 1000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tvSourceName = view.findViewById(R.id.tvSourceName);
        tvClock = view.findViewById(R.id.tvClock);
        tvDate = view.findViewById(R.id.tvDate);
        tvHeroTitle = view.findViewById(R.id.tvHeroTitle);
        tvHeroSub = view.findViewById(R.id.tvHeroSub);
        tvRecentLabel = view.findViewById(R.id.tvRecentLabel);
        navContainer = view.findViewById(R.id.navContainer);
        rvRecent = view.findViewById(R.id.rvRecent);

        view.findViewById(R.id.btnEnterLive).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enterLive();
            }
        });
        tvSourceName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new SourcesFragment(SourcesFragment.MODE_IPTV));
            }
        });

        rvRecent.setLayoutManager(new LinearLayoutManager(getActivity(),
                LinearLayoutManager.HORIZONTAL, false));
        recentAdapter = new Adapters.ChannelAdapter(new Adapters.OnChannelAction() {
            @Override
            public void onClick(Channel channel) {
                play(channel);
            }

            @Override
            public void onLongClick(Channel channel) {
                play(channel);
            }
        });
        rvRecent.setAdapter(recentAdapter);

        applyCareMode();
        buildNav();
        updateClock();
        refresh();
        loadChannels(false);
    }

    /** 关怀模式：首页也放大字号、图标，远看电视不费眼。 */
    private void applyCareMode() {
        care = Prefs.careMode();
        if (!care) {
            return;
        }
        tvSourceName.setTextSize(18);
        tvDate.setTextSize(17);
        tvClock.setTextSize(25);
        tvHeroTitle.setTextSize(32);
        tvHeroSub.setTextSize(18);
        tvRecentLabel.setTextSize(17);
    }

    private void open(Fragment fragment) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).show(fragment, true);
        }
    }

    private void play(Channel channel) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).play(channel);
        }
    }

    private void buildNav() {
        if (navContainer.getChildCount() > 0) {
            return;
        }
        final String[][] navs = {
                {"直播", "live"},
                {"全部频道", "all"},
                {"收藏", "fav"},
                {"节目单", "epg"},
                {"搜索", "search"},
                {"设置", "settings"},
        };
        final int[] icons = {
                R.drawable.ic_live,
                R.drawable.ic_grid,
                R.drawable.ic_fav,
                R.drawable.ic_epg,
                R.drawable.ic_search,
                R.drawable.ic_settings,
        };

        LayoutInflater inflater = LayoutInflater.from(getActivity());
        // 2 列 × 3 行，卡片接近 3:2，看起来更像原版的宫格
        for (int row = 0; row < 3; row++) {
            LinearLayout rowView = new LinearLayout(getActivity());
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            if (row < 2) {
                rowLp.bottomMargin = Ui.dp(getActivity(), 10);
            }
            rowView.setLayoutParams(rowLp);

            for (int col = 0; col < 2; col++) {
                final int index = row * 2 + col;
                View card = inflater.inflate(R.layout.item_nav, rowView, false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                if (col == 0) {
                    lp.rightMargin = Ui.dp(getActivity(), 10);
                }
                card.setLayoutParams(lp);

                TextView label = card.findViewById(R.id.tvLabel);
                label.setText(navs[index][0]);
                label.setTextSize(care ? 19 : 13);
                ImageView icon = card.findViewById(R.id.ivIcon);
                int iconSize = Ui.dp(getActivity(), care ? 36 : 26);
                ViewGroup.LayoutParams iconLp = icon.getLayoutParams();
                if (iconLp != null) {
                    iconLp.width = iconSize;
                    iconLp.height = iconSize;
                    icon.setLayoutParams(iconLp);
                }
                try {
                    icon.setImageDrawable(AppCompatResources.getDrawable(getActivity(),
                            icons[index]));
                } catch (Throwable ignored) {
                }

                final String action = navs[index][1];
                card.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onNav(action);
                    }
                });
                rowView.addView(card);
            }
            navContainer.addView(rowView);
        }
    }

    private void onNav(String action) {
        if ("live".equals(action)) {
            enterLive();
        } else if ("all".equals(action)) {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openChannels(ChannelsFragment.MODE_ALL);
            }
        } else if ("fav".equals(action)) {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openChannels(ChannelsFragment.MODE_FAVORITE);
            }
        } else if ("epg".equals(action)) {
            open(new EpgFragment());
        } else if ("search".equals(action)) {
            open(new SearchFragment());
        } else if ("settings".equals(action)) {
            open(new SettingsFragment());
        }
    }

    private void enterLive() {
        Channel last = ChannelRepository.findByName(Prefs.getString(Prefs.K_LAST_CHANNEL, ""));
        if (last == null) {
            last = ChannelRepository.first();
        }
        if (last != null) {
            play(last);
        } else {
            Ui.toast(getActivity(), "还没有可用频道，请先在“订阅源”里添加直播源");
            open(new SourcesFragment(SourcesFragment.MODE_IPTV));
        }
    }

    private void updateClock() {
        if (tvClock == null) {
            return;
        }
        tvClock.setText(Ui.clock());
        tvDate.setText(Ui.dateLine());
    }

    private void refresh() {
        if (getView() == null) {
            return;
        }
        List<IptvSource> sources = Prefs.getIptvSources();
        int enabled = 0;
        String firstName = null;
        for (IptvSource s : sources) {
            if (s.enabled && s.url != null && !s.url.trim().isEmpty()) {
                enabled++;
                if (firstName == null) {
                    firstName = s.displayName();
                }
            }
        }
        if (enabled == 0) {
            tvSourceName.setText("未配置订阅源");
        } else if (enabled == 1 && firstName != null) {
            tvSourceName.setText(firstName);
        } else {
            tvSourceName.setText(enabled + " 个订阅源");
        }

        List<Channel> all = ChannelRepository.all();
        Channel last = ChannelRepository.findByName(Prefs.getString(Prefs.K_LAST_CHANNEL, ""));
        if (last != null) {
            tvHeroTitle.setText(last.name);
            tvHeroSub.setText("继续观看 · 共 " + all.size() + " 个频道");
        } else if (!all.isEmpty()) {
            tvHeroTitle.setText(ChannelRepository.first().name);
            tvHeroSub.setText("共 " + all.size() + " 个频道，按 OK 开始");
        } else {
            tvHeroTitle.setText("从直播开始");
            tvHeroSub.setText("还没有频道，先去添加一个订阅源");
        }

        List<Channel> history = ChannelRepository.history();
        if (history.isEmpty()) {
            tvRecentLabel.setVisibility(View.GONE);
            rvRecent.setVisibility(View.GONE);
        } else {
            tvRecentLabel.setVisibility(View.VISIBLE);
            rvRecent.setVisibility(View.VISIBLE);
            recentAdapter.submit(history);
        }
    }

    private void loadChannels(boolean force) {
        if (ChannelRepository.isLoading()) {
            return;
        }
        ChannelRepository.loadAsync(getActivity(), force, new ChannelRepository.Callback() {
            @Override
            public void onResult(String error) {
                refresh();
                if (error != null) {
                    Ui.toast(getActivity(), "订阅源加载失败：" + error);
                } else if (Prefs.epgEnabled() && !EpgRepository.hasData()) {
                    EpgRepository.loadAsync(getActivity(), false, null);
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(ticker);
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }
}
