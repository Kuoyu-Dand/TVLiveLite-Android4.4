package com.mytv.lite.ui.adapter;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mytv.lite.Prefs;
import com.mytv.lite.R;
import com.mytv.lite.data.Channel;
import com.mytv.lite.data.EpgRepository;
import com.mytv.lite.ui.LogoLoader;
import com.mytv.lite.ui.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表适配器集合，放在一起减少类文件数量（老设备 dex 也小一点）。
 */
public final class Adapters {

    private Adapters() {
    }

    public interface OnChannelAction {
        void onClick(Channel channel);

        void onLongClick(Channel channel);
    }

    // ---------------- 频道网格 ----------------

    public static class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {

        private final List<Channel> items = new ArrayList<Channel>();
        private final OnChannelAction action;

        public ChannelAdapter(OnChannelAction action) {
            this.action = action;
        }

        public void submit(List<Channel> list) {
            items.clear();
            if (list != null) {
                items.addAll(list);
            }
            notifyDataSetChanged();
        }

        public List<Channel> items() {
            return items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull final VH holder, int position) {
            final Channel c = items.get(position);
            holder.tvNo.setText(c.no());
            holder.tvName.setText(c.name);
            holder.tvFav.setVisibility(Prefs.isFavorite(c.standardName)
                    ? View.VISIBLE : View.GONE);

            EpgRepository.Programme now = EpgRepository.current(c);
            if (now != null) {
                holder.tvEpg.setText(now.title);
                holder.tvEpg.setVisibility(View.VISIBLE);
            } else {
                holder.tvEpg.setText("");
            }

            LogoLoader.load(holder.ivLogo, c);

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (action != null) {
                        action.onClick(c);
                    }
                }
            });
            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (action != null) {
                        action.onLongClick(c);
                    }
                    return true;
                }
            });
            holder.itemView.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN
                            && (keyCode == KeyEvent.KEYCODE_MENU
                            || keyCode == KeyEvent.KEYCODE_INFO)) {
                        if (action != null) {
                            action.onLongClick(c);
                        }
                        return true;
                    }
                    return false;
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvNo;
            final TextView tvName;
            final TextView tvEpg;
            final TextView tvFav;
            final android.widget.ImageView ivLogo;

            VH(View v) {
                super(v);
                tvNo = (TextView) v.findViewById(R.id.tvNo);
                tvName = (TextView) v.findViewById(R.id.tvName);
                tvEpg = (TextView) v.findViewById(R.id.tvEpg);
                tvFav = (TextView) v.findViewById(R.id.tvFav);
                ivLogo = (android.widget.ImageView) v.findViewById(R.id.ivLogo);
            }
        }
    }

    // ---------------- 分组列表 ----------------

    public static class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {

        public static class Item {
            public String name = "";
            public int count;
        }

        public interface OnGroupClick {
            void onGroupClick(int position);
        }

        private final List<Item> items = new ArrayList<Item>();
        private final OnGroupClick callback;
        private int selected;

        public GroupAdapter(OnGroupClick callback) {
            this.callback = callback;
        }

        public void submit(List<Item> list, int selected) {
            items.clear();
            if (list != null) {
                items.addAll(list);
            }
            this.selected = selected;
            notifyDataSetChanged();
        }

        public void setSelected(int index) {
            this.selected = index;
            notifyDataSetChanged();
        }

        public int selected() {
            return selected;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_group, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull final VH holder, final int position) {
            Item item = items.get(position);
            String text = item.name;
            if (item.count > 0) {
                text = text + "  " + item.count;
            }
            holder.tv.setText(text);
            holder.tv.setSelected(position == selected);
            holder.tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callback != null) {
                        callback.onGroupClick(holder.getAdapterPosition());
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tv;

            VH(View v) {
                super(v);
                tv = (TextView) v;
            }
        }
    }

    // ---------------- 节目单 ----------------

    public static class ProgrammeAdapter extends RecyclerView.Adapter<ProgrammeAdapter.VH> {

        private final List<EpgRepository.Programme> items =
                new ArrayList<EpgRepository.Programme>();

        public void submit(List<EpgRepository.Programme> list) {
            items.clear();
            if (list != null) {
                items.addAll(list);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_programme, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            EpgRepository.Programme p = items.get(position);
            holder.tvTime.setText(Ui.time(p.start) + " - " + Ui.time(p.end));
            holder.tvTitle.setText(p.title);
            long now = System.currentTimeMillis();
            holder.tvLive.setVisibility(p.start <= now && p.end > now
                    ? View.VISIBLE : View.GONE);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvTime;
            final TextView tvTitle;
            final TextView tvLive;

            VH(View v) {
                super(v);
                tvTime = (TextView) v.findViewById(R.id.tvTime);
                tvTitle = (TextView) v.findViewById(R.id.tvTitle);
                tvLive = (TextView) v.findViewById(R.id.tvLive);
            }
        }
    }

    // ---------------- 播放器内频道列表 ----------------

    public static class PanelChannelAdapter extends RecyclerView.Adapter<PanelChannelAdapter.VH> {

        private final List<Channel> items = new ArrayList<Channel>();
        private final OnChannelAction action;
        private String currentKey;

        public PanelChannelAdapter(OnChannelAction action) {
            this.action = action;
        }

        public void submit(List<Channel> list, String currentKey) {
            items.clear();
            if (list != null) {
                items.addAll(list);
            }
            this.currentKey = currentKey;
            notifyDataSetChanged();
        }

        public int indexOf(String key) {
            for (int i = 0; i < items.size(); i++) {
                if (com.mytv.lite.data.ChannelAlias.key(items.get(i).standardName)
                        .equals(key)) {
                    return i;
                }
            }
            return 0;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_panel_channel, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull final VH holder, int position) {
            final Channel c = items.get(position);
            boolean isCurrent = currentKey != null
                    && currentKey.equals(com.mytv.lite.data.ChannelAlias.key(c.standardName));
            holder.tvNo.setText(c.no());
            holder.tvName.setText(isCurrent ? "▶ " + c.name : c.name);
            holder.tvFav.setVisibility(Prefs.isFavorite(c.standardName)
                    ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (action != null) {
                        action.onClick(c);
                    }
                }
            });
            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (action != null) {
                        action.onLongClick(c);
                    }
                    return true;
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvNo;
            final TextView tvName;
            final TextView tvFav;

            VH(View v) {
                super(v);
                tvNo = (TextView) v.findViewById(R.id.tvNo);
                tvName = (TextView) v.findViewById(R.id.tvName);
                tvFav = (TextView) v.findViewById(R.id.tvFav);
            }
        }
    }
}
