package com.mytv.lite.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.mytv.lite.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 内置文件浏览器。
 * <p>
 * 为什么不用系统选择器：Android 4.4 的机顶盒普遍没有 DocumentsUI，也没有文件管理器
 * 注册 ACTION_GET_CONTENT，点“从文件导入”要么无响应，要么弹出一个空的/只能看图片的
 * 界面，根本进不到 U 盘。这里自己读挂载点列目录，完全不依赖系统组件。
 */
public class FileBrowserActivity extends AppCompatActivity {

    /** iptv / epg */
    public static final String EXTRA_MODE = "mode";
    /** 可选：打开时定位到该路径所在目录 */
    public static final String EXTRA_START = "start_path";
    /** 返回：选中的绝对路径 */
    public static final String EXTRA_PATH = "picked_path";

    private static final String KEY_CURRENT = "current_path";
    private static final String KEY_FILTER = "only_source";

    /** 直播源可用的扩展名 */
    private static final Set<String> IPTV_EXT = new HashSet<String>(Arrays.asList(
            "txt", "m3u", "m3u8", "json", "conf", "ini", "list", "tv", "tpl", "csv", "gz"));

    /** 节目单可用的扩展名 */
    private static final Set<String> EPG_EXT = new HashSet<String>(Arrays.asList(
            "xml", "gz", "xmltv", "txt"));

    private String mode = "iptv";
    private File current;
    private boolean onlySource;
    private final Set<String> roots = new HashSet<String>();
    private final List<Row> rows = new ArrayList<Row>();

    private TextView tvPath;
    private TextView tvCount;
    private TextView tvHint;
    private RecyclerView rv;
    private Adapter adapter;

    private final Comparator<File> FILE_ORDER = new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
            boolean da = a.isDirectory();
            boolean db = b.isDirectory();
            if (da != db) {
                return da ? -1 : 1;
            }
            if (!da) {
                boolean sa = supports(a);
                boolean sb = supports(b);
                if (sa != sb) {
                    return sa ? -1 : 1;
                }
            }
            return a.getName().compareToIgnoreCase(b.getName());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        if ("epg".equals(getIntent().getStringExtra(EXTRA_MODE))) {
            mode = "epg";
        }
        onlySource = false;

        tvPath = (TextView) findViewById(R.id.tvPath);
        tvCount = (TextView) findViewById(R.id.tvCount);
        tvHint = (TextView) findViewById(R.id.tvBrowserHint);
        ((TextView) findViewById(R.id.tvBrowserTitle))
                .setText(mode.equals("epg") ? "选择节目单文件" : "选择直播源文件");
        tvHint.setText(mode.equals("epg")
                ? "OK 打开文件夹 · 返回键 上一级 · 菜单键 只看源文件"
                : "OK 打开文件夹 · 返回键 上一级 · 菜单键 只看源文件");

        rv = (RecyclerView) findViewById(R.id.rvFiles);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                focusUp();
            }
        });

        buildVolumeChips();

        File start = null;
        if (savedInstanceState != null) {
            String p = savedInstanceState.getString(KEY_CURRENT);
            if (p != null) {
                start = new File(p);
            }
            onlySource = savedInstanceState.getBoolean(KEY_FILTER, false);
        }
        if (start == null) {
            String p = getIntent().getStringExtra(EXTRA_START);
            if (!TextUtils.isEmpty(p)) {
                File f = new File(p);
                start = f.isDirectory() ? f : f.getParentFile();
            }
        }
        if (start == null || !start.isDirectory() || !start.canRead()) {
            start = firstVolume();
        }
        open(start);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (current != null) {
            outState.putString(KEY_CURRENT, current.getAbsolutePath());
        }
        outState.putBoolean(KEY_FILTER, onlySource);
    }

    // ---------------- 存储卷 ----------------

    private void buildVolumeChips() {
        LinearLayout bar = (LinearLayout) findViewById(R.id.llVolumes);
        bar.removeAllViews();
        roots.clear();
        List<StorageVolumes.Volume> volumes = StorageVolumes.list(this);
        int margin = Ui.dp(this, 8);
        for (int i = 0; i < volumes.size(); i++) {
            final StorageVolumes.Volume vol = volumes.get(i);
            TextView chip = new TextView(this);
            chip.setText(vol.label);
            chip.setTextSize(13);
            chip.setSingleLine(true);
            chip.setBackgroundResource(R.drawable.bg_pill);
            chip.setTextColor(getResources().getColorStateList(R.color.card_text));
            chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
            chip.setClickable(true);
            chip.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = margin;
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(vol.dir);
                }
            });
            bar.addView(chip);
            try {
                roots.add(vol.dir.getCanonicalPath());
            } catch (Exception e) {
                roots.add(vol.dir.getAbsolutePath());
            }
        }
        if (volumes.isEmpty()) {
            bar.setVisibility(View.GONE);
        }
    }

    private File firstVolume() {
        List<StorageVolumes.Volume> volumes = StorageVolumes.list(this);
        if (!volumes.isEmpty()) {
            return volumes.get(0).dir;
        }
        File ext = android.os.Environment.getExternalStorageDirectory();
        if (ext != null && ext.canRead()) {
            return ext;
        }
        return new File("/");
    }

    private boolean atRoot() {
        if (current == null) {
            return true;
        }
        String path;
        try {
            path = current.getCanonicalPath();
        } catch (Exception e) {
            path = current.getAbsolutePath();
        }
        return roots.contains(path) || current.getParentFile() == null;
    }

    // ---------------- 目录浏览 ----------------

    private void open(File dir) {
        if (dir == null) {
            return;
        }
        if (!dir.isDirectory()) {
            toast("不是文件夹：" + dir.getName());
            return;
        }
        if (!dir.canRead()) {
            toast("无权访问：" + dir.getAbsolutePath());
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            toast("目录读取失败，可能没有存储权限");
            return;
        }
        current = dir;

        rows.clear();
        if (!atRoot()) {
            rows.add(Row.up(dir.getParentFile()));
        }
        List<File> list = new ArrayList<File>(Arrays.asList(files));
        try {
            Collections.sort(list, FILE_ORDER);
        } catch (Throwable ignored) {
        }
        int skipped = 0;
        for (File f : list) {
            String name = f.getName();
            if (name.startsWith(".")) {
                skipped++;
                continue; // 隐藏文件不看
            }
            if (f.isDirectory()) {
                rows.add(Row.dir(f));
                continue;
            }
            boolean ok = supports(f);
            if (!ok && onlySource) {
                skipped++;
                continue;
            }
            rows.add(Row.file(f, ok));
        }

        adapter.notifyDataSetChanged();
        tvPath.setText(current.getAbsolutePath());
        if (rows.isEmpty() || (rows.size() == 1 && rows.get(0).up)) {
            tvCount.setText(supportsHint() + " · 空文件夹");
        } else {
            tvCount.setText(rows.size() + " 项"
                    + (skipped > 0 && onlySource ? "（已过滤 " + skipped + "）" : ""));
        }
        rv.scrollToPosition(0);
        rv.post(new Runnable() {
            @Override
            public void run() {
                focusFirst();
            }
        });
    }

    private void focusFirst() {
        if (rv.getChildCount() > 0) {
            rv.getChildAt(0).requestFocus();
        }
    }

    private void focusUp() {
        if (atRoot()) {
            finish();
            return;
        }
        open(current.getParentFile());
    }

    private String supportsHint() {
        return mode.equals("epg") ? "支持 .xml / .xml.gz" : "支持 .m3u / .txt";
    }

    private boolean supports(File f) {
        String name = f.getName().toLowerCase(Locale.US);
        if (name.endsWith(".xml.gz") || name.endsWith(".m3u.gz") || name.endsWith(".txt.gz")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return mode.equals("iptv"); // 无扩展名的纯文本源（txt 源很常见）
        }
        String ext = name.substring(dot + 1);
        return mode.equals("epg") ? EPG_EXT.contains(ext) : IPTV_EXT.contains(ext);
    }

    private void pick(File f) {
        if (!f.isFile()) {
            return;
        }
        if (!f.canRead()) {
            toast("无法读取该文件，检查存储权限");
            return;
        }
        if (f.length() <= 0) {
            toast("文件是空的");
            return;
        }
        Intent data = new Intent();
        data.putExtra(EXTRA_PATH, f.getAbsolutePath());
        setResult(RESULT_OK, data);
        finish();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (!atRoot() && current != null && current.getParentFile() != null) {
            open(current.getParentFile());
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            onlySource = !onlySource;
            toast(onlySource ? "只显示源文件" : "显示全部文件");
            open(current);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------- 列表 ----------------

    private static class Row {
        File file;
        String title;
        String value;
        boolean up;
        boolean supported;

        static Row up(File parent) {
            Row r = new Row();
            r.file = parent;
            r.title = "‹ 上一级";
            r.value = "";
            r.up = true;
            r.supported = true;
            return r;
        }

        static Row dir(File f) {
            Row r = new Row();
            r.file = f;
            r.title = f.getName();
            r.value = "文件夹 ›";
            r.supported = true;
            return r;
        }

        static Row file(File f, boolean supported) {
            Row r = new Row();
            r.file = f;
            r.title = f.getName();
            r.value = size(f.length());
            r.supported = supported;
            return r;
        }

        static String size(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return (bytes / 1024) + " KB";
            }
            if (bytes < 1024L * 1024 * 1024) {
                return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f);
            }
            return String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f);
        }
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView value;
            final TextView sub;

            VH(View v) {
                super(v);
                title = (TextView) v.findViewById(R.id.tvTitle);
                value = (TextView) v.findViewById(R.id.tvValue);
                sub = (TextView) v.findViewById(R.id.tvSub);
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
            holder.title.setText(row.title);
            holder.value.setText(row.value);
            holder.sub.setVisibility(View.GONE);
            holder.title.setTextColor(getResources().getColorStateList(
                    row.supported ? R.color.file_text_ok : R.color.file_text_dim));
            holder.value.setTextColor(getResources().getColorStateList(R.color.row_text_sub));
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (row.up) {
                        focusUp();
                        return;
                    }
                    if (row.file.isDirectory()) {
                        open(row.file);
                        return;
                    }
                    if (!row.supported) {
                        toast("不是" + (mode.equals("epg") ? "节目单" : "直播源")
                                + "文件（" + supportsHint() + "），长按菜单键可显示全部");
                        return;
                    }
                    pick(row.file);
                }
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
