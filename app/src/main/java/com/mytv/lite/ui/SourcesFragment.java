package com.mytv.lite.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import com.mytv.lite.net.Http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 订阅源管理（直播源 / 节目单源）
 */
public class SourcesFragment extends Fragment {

    public static final int MODE_IPTV = 0;
    public static final int MODE_EPG = 1;
    private static final int REQ_IMPORT = 1001;
    private static final int REQ_PERM = 1002;
    private static final long MAX_IMPORT = 32L * 1024 * 1024;

    private int mode = MODE_IPTV;
    private boolean permissionAsked;
    private boolean pendingBrowser;

    private RecyclerView rv;
    private TextView tvTitle;
    private TextView tvDesc;
    private TextView tvHint;
    private SourceAdapter adapter;
    private final List<IptvSource> iptvList = new ArrayList<IptvSource>();
    private final List<EpgSource> epgList = new ArrayList<EpgSource>();

    public SourcesFragment() {
    }

    public SourcesFragment(int mode) {
        this.mode = mode;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sources, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rv = view.findViewById(R.id.rvSources);
        tvTitle = view.findViewById(R.id.tvTitle);
        tvDesc = view.findViewById(R.id.tvDesc);
        tvHint = view.findViewById(R.id.tvHint);

        rv.setLayoutManager(new LinearLayoutManager(getActivity()));
        adapter = new SourceAdapter();
        rv.setAdapter(adapter);

        boolean epg = mode == MODE_EPG;
        tvTitle.setText(epg ? "节目单源" : "直播源");
        tvDesc.setText(epg
                ? "XMLTV 格式（.xml / .xml.gz），按顺序合并，同名频道以第一个源为准"
                : "支持 m3u（#EXTM3U）与 txt（#genre#），多个源按顺序合并，同名频道会合并线路");
        tvHint.setText("OK 编辑 · 长按删除");

        View importBtn = view.findViewById(R.id.btnImport);
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        view.findViewById(R.id.btnAdd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addSource();
            }
        });
        view.findViewById(R.id.btnClearCache).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.confirm(getActivity(), "清空缓存", "会删除已缓存的订阅内容，下次进入重新拉取", new Runnable() {
                    @Override
                    public void run() {
                        ChannelRepository.clearCache(getActivity());
                        EpgRepository.clearCache(getActivity());
                        Ui.toast(getActivity(), "缓存已清空");
                    }
                });
            }
        });

        reload();
    }

    private void reload() {
        iptvList.clear();
        epgList.clear();
        if (mode == MODE_EPG) {
            epgList.addAll(Prefs.getEpgSources());
        } else {
            iptvList.addAll(Prefs.getIptvSources());
        }
        adapter.notifyDataSetChanged();
    }

    private int size() {
        return mode == MODE_EPG ? epgList.size() : iptvList.size();
    }

    private String nameOf(int index) {
        return mode == MODE_EPG ? epgList.get(index).name : iptvList.get(index).name;
    }

    private String urlOf(int index) {
        return mode == MODE_EPG ? epgList.get(index).url : iptvList.get(index).url;
    }

    private boolean enabledOf(int index) {
        return mode == MODE_EPG ? epgList.get(index).enabled : iptvList.get(index).enabled;
    }

    private void setEnabled(int index, boolean enabled) {
        if (mode == MODE_EPG) {
            epgList.get(index).enabled = enabled;
            Prefs.saveEpgSources(epgList);
        } else {
            iptvList.get(index).enabled = enabled;
            Prefs.saveIptvSources(iptvList);
        }
        invalidate();
    }

    private void saveAll() {
        if (mode == MODE_EPG) {
            Prefs.saveEpgSources(epgList);
        } else {
            Prefs.saveIptvSources(iptvList);
        }
        invalidate();
    }

    private void invalidate() {
        adapter.notifyDataSetChanged();
        if (mode == MODE_EPG) {
            EpgRepository.clearCache(getActivity());
            EpgRepository.loadAsync(getActivity(), true, null);
        } else {
            ChannelRepository.clearCache(getActivity());
            ChannelRepository.loadAsync(getActivity(), true, new ChannelRepository.Callback() {
                @Override
                public void onResult(String error) {
                    if (error != null) {
                        Ui.toast(getActivity(), "刷新失败：" + error);
                    }
                }
            });
        }
    }

    private void addSource() {
        Ui.input(getActivity(), mode == MODE_EPG ? "节目单地址" : "订阅地址",
                "http:// 或 https:// 开头的地址", "", false, new Ui.OnText() {
                    @Override
                    public void onText(String url) {
                        if (url == null || url.trim().isEmpty()) {
                            return;
                        }
                        if (!url.startsWith("http")) {
                            Ui.toast(getActivity(), "地址需要以 http 开头");
                            return;
                        }
                        askName(url);
                    }
                });
    }

    private void askName(final String url) {
        Ui.input(getActivity(), "名称（可留空）", "例如：我的直播源", "", false,
                new Ui.OnText() {
                    @Override
                    public void onText(String name) {
                        String finalName = (name == null || name.trim().isEmpty())
                                ? shortName(url) : name.trim();
                        if (mode == MODE_EPG) {
                            epgList.add(new EpgSource(finalName, url, true));
                        } else {
                            iptvList.add(new IptvSource(finalName, url, true, false));
                        }
                        saveAll();
                        Ui.toast(getActivity(), "已添加，正在刷新…");
                    }
                });
    }

    private String shortName(String url) {
        String s = url.replace("http://", "").replace("https://", "");
        return s.length() > 28 ? s.substring(0, 28) + "…" : s;
    }

    private void editSource(final int index) {
        final String[] items = {
                enabledOf(index) ? "停用" : "启用",
                "修改名称",
                "修改地址",
                "删除",
        };
        new AlertDialog.Builder(getActivity(), R.style.DialogTheme)
                .setTitle(nameOf(index))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            setEnabled(index, !enabledOf(index));
                        } else if (which == 1) {
                            Ui.input(getActivity(), "修改名称", "", nameOf(index), false,
                                    new Ui.OnText() {
                                        @Override
                                        public void onText(String text) {
                                            if (text == null || text.isEmpty()) {
                                                return;
                                            }
                                            if (mode == MODE_EPG) {
                                                epgList.get(index).name = text;
                                            } else {
                                                iptvList.get(index).name = text;
                                            }
                                            saveAll();
                                        }
                                    });
                        } else if (which == 2) {
                            Ui.input(getActivity(), "修改地址", "", urlOf(index), false,
                                    new Ui.OnText() {
                                        @Override
                                        public void onText(String text) {
                                            if (text == null || text.isEmpty()) {
                                                return;
                                            }
                                            if (mode == MODE_EPG) {
                                                epgList.get(index).url = text;
                                            } else {
                                                iptvList.get(index).url = text;
                                                iptvList.get(index).local = false;
                                            }
                                            saveAll();
                                        }
                                    });
                        } else {
                            remove(index);
                        }
                    }
                })
                .show();
    }

    private void remove(final int index) {
        Ui.confirm(getActivity(), "删除订阅源", "确定删除“" + nameOf(index) + "”吗？",
                new Runnable() {
                    @Override
                    public void run() {
                        if (mode == MODE_EPG) {
                            if (index < epgList.size()) {
                                epgList.remove(index);
                            }
                        } else {
                            if (index < iptvList.size()) {
                                iptvList.remove(index);
                            }
                        }
                        saveAll();
                    }
                });
    }

    /**
     * 导入入口。老盒子上的 ACTION_GET_CONTENT 经常没人响应（没有 DocumentsUI，
     * 也没有文件管理器注册它），所以默认走内置浏览器；系统选择器留作备选。
     */
    private void pickFile() {
        final boolean epg = mode == MODE_EPG;
        String[] items = {
                "浏览 U 盘 / 盒子存储（推荐）",
                "系统文件选择器",
                "手动输入文件路径",
        };
        new AlertDialog.Builder(getActivity(), R.style.DialogTheme)
                .setTitle(epg ? "导入节目单文件" : "导入直播源文件")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            launchBrowser();
                        } else if (which == 1) {
                            openSystemPicker();
                        } else {
                            inputPath();
                        }
                    }
                })
                .show();
    }

    private void launchBrowser() {
        if (needStoragePermission()) {
            return;
        }
        Intent intent = new Intent(getActivity(), FileBrowserActivity.class);
        intent.putExtra(FileBrowserActivity.EXTRA_MODE, mode == MODE_EPG ? "epg" : "iptv");
        startActivityForResult(intent, REQ_IMPORT);
    }

    /** API 23+ 读 /storage 下的 U 盘需要运行时权限；4.4 是安装即授权 */
    private boolean needStoragePermission() {
        if (Build.VERSION.SDK_INT < 23 || getActivity() == null) {
            return false;
        }
        boolean granted = getActivity().checkSelfPermission(
                android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        if (granted || permissionAsked) {
            return false;
        }
        permissionAsked = true;
        pendingBrowser = true;
        requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                REQ_PERM);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != REQ_PERM) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Ui.toast(getActivity(), "没有存储权限，U 盘文件可能读不到");
        }
        if (pendingBrowser) {
            pendingBrowser = false;
            launchBrowser();
        }
    }

    private void openSystemPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQ_IMPORT);
        } catch (Throwable t) {
            Ui.toast(getActivity(), "系统没有文件选择器，请用内置浏览器");
        }
    }

    private void inputPath() {
        Ui.input(getActivity(), "文件路径", "例如 /mnt/usb/live.m3u 或 /storage/XXXX-XXXX/tv.txt",
                "", false, new Ui.OnText() {
                    @Override
                    public void onText(String text) {
                        if (text == null || text.trim().isEmpty()) {
                            return;
                        }
                        String path = text.trim();
                        if (path.startsWith("file://")) {
                            path = path.substring("file://".length());
                        }
                        importFromPath(new File(path));
                    }
                });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        String path = data.getStringExtra(FileBrowserActivity.EXTRA_PATH);
        if (path != null && !path.isEmpty()) {
            importFromPath(new File(path));
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            importFromPath(new File(uri.getPath()));
            return;
        }
        importFromUri(uri);
    }

    // ---------------- 导入实现 ----------------

    private interface Opener {
        InputStream open() throws Exception;
    }

    private void importFromPath(final File src) {
        if (src == null) {
            return;
        }
        if (!src.exists()) {
            Ui.toast(getActivity(), "文件不存在：" + src.getAbsolutePath());
            return;
        }
        if (!src.isFile() || !src.canRead()) {
            Ui.toast(getActivity(), "无法读取该文件（权限或路径不对）");
            return;
        }
        if (src.length() <= 0) {
            Ui.toast(getActivity(), "文件是空的");
            return;
        }
        importStream(displayName(src.getName()), new Opener() {
            @Override
            public InputStream open() throws Exception {
                return new FileInputStream(src);
            }
        });
    }

    private void importFromUri(final Uri uri) {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        String name = uri.getLastPathSegment();
        importStream(displayName(name), new Opener() {
            @Override
            public InputStream open() throws Exception {
                return activity.getContentResolver().openInputStream(uri);
            }
        });
    }

    /** 把源文件复制到应用私有目录（U 盘拔掉后仍可用），再校验内容是否像源文件 */
    private void importStream(final String name, final Opener opener) {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog.Builder(activity, R.style.DialogTheme)
                .setMessage("正在导入 " + name + " …")
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                File target = null;
                long bytes = 0;
                InputStream in = null;
                FileOutputStream out = null;
                try {
                    File dir = new File(activity.getFilesDir(), "imports");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IOException("无法创建导入目录");
                    }
                    target = new File(dir, "src_" + System.currentTimeMillis()
                            + (mode == MODE_EPG ? ".xml" : ".txt"));
                    in = opener.open();
                    if (in == null) {
                        throw new IOException("无法打开文件");
                    }
                    out = new FileOutputStream(target);
                    byte[] buf = new byte[16 * 1024];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        bytes += len;
                        if (bytes > MAX_IMPORT) {
                            throw new IOException("文件超过 32MB，请先精简后再导入");
                        }
                        out.write(buf, 0, len);
                    }
                    out.flush();
                    if (bytes == 0) {
                        throw new IOException("文件是空的");
                    }
                } catch (Throwable t) {
                    error = t.getMessage() == null ? "读取失败" : t.getMessage();
                } finally {
                    closeQuietly(in);
                    closeQuietly(out);
                }

                if (error == null && target != null) {
                    try {
                        error = validate(target);
                    } catch (Throwable t) {
                        error = "无法校验文件内容";
                    }
                    if (error != null) {
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                    }
                }

                final String err = error;
                final File saved = target;
                final long size = bytes;
                Ui.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            progress.dismiss();
                        } catch (Throwable ignored) {
                        }
                        if (getActivity() == null) {
                            return;
                        }
                        if (err != null) {
                            Ui.toast(getActivity(), "导入失败：" + err);
                            return;
                        }
                        addLocalSource(name, saved);
                        Ui.toast(getActivity(), "已导入 " + name + "（" + sizeText(size) + "）");
                    }
                });
            }
        }, "src-import").start();
    }

    private void addLocalSource(String name, File file) {
        if (mode == MODE_EPG) {
            EpgSource source = new EpgSource(name, file.getAbsolutePath(), true);
            source.local = true;
            epgList.add(source);
        } else {
            iptvList.add(new IptvSource(name, file.getAbsolutePath(), true, true));
        }
        saveAll();
    }

    /**
     * 简单校验：避免把 U 盘里的视频/图片当源导进来。
     * 同时按 BOM / UTF-8 严格解码 / GB18030 兜底判断文本，老盒子上的 txt 源多是 GBK。
     */
    private String validate(File file) throws IOException {
        String text = decodeHead(file, 64 * 1024);
        String lower = text.toLowerCase(Locale.US);
        if (mode == MODE_EPG) {
            if (lower.contains("<tv") || lower.contains("<?xml")
                    || lower.contains("<!doctype tv") || lower.contains("<programme")) {
                return null;
            }
            return "不是 XMLTV 节目单（.xml / .xml.gz）";
        }
        if (lower.contains("#extm3u") || lower.contains("#genre#") || lower.contains("://")) {
            return null;
        }
        return "不是 m3u / txt 直播源";
    }

    private String decodeHead(File file, int limit) throws IOException {
        byte[] data = readHead(file, limit);
        if (data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            GZIPInputStream gz = null;
            try {
                gz = new GZIPInputStream(new ByteArrayInputStream(data));
                byte[] buf = new byte[8 * 1024];
                int r;
                while ((r = gz.read(buf)) > 0 && bos.size() < 256 * 1024) {
                    bos.write(buf, 0, r);
                }
            } catch (Exception ignored) {
                // 截断的 gz 尾部会抛异常，已解出的头部够判断了
            } finally {
                closeQuietly(gz);
            }
            data = bos.toByteArray();
        }
        int offset = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        try {
            java.nio.charset.CharsetDecoder decoder = java.nio.charset.Charset
                    .forName("UTF-8").newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(data, offset, data.length - offset))
                    .toString();
        } catch (Throwable ignored) {
        }
        try {
            return new String(data, offset, data.length - offset, "GB18030");
        } catch (Throwable ignored) {
            return new String(data, offset, data.length - offset);
        }
    }

    private byte[] readHead(File file, int limit) throws IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] raw = new byte[limit];
            int read = 0;
            int len;
            while (read < limit && (len = in.read(raw, read, limit - read)) > 0) {
                read += len;
            }
            byte[] data = new byte[read];
            System.arraycopy(raw, 0, data, 0, read);
            return data;
        } finally {
            closeQuietly(in);
        }
    }

    private String displayName(String fileName) {
        String name = fileName == null ? "" : fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.trim();
        if (name.isEmpty()) {
            return "本地导入源";
        }
        return name.length() > 30 ? name.substring(0, 30) : name;
    }

    private String sizeText(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f);
    }

    private void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    private class SourceAdapter extends RecyclerView.Adapter<SourceAdapter.VH> {

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
            final int index = position;
            String name = nameOf(index);
            String url = urlOf(index);
            boolean enabled = enabledOf(index);
            holder.title.setText(name == null || name.isEmpty() ? "未命名源" : name);
            holder.value.setText(enabled ? "已启用" : "已停用");
            holder.value.setTextColor(getResources().getColor(
                    enabled ? R.color.accent : R.color.text_tertiary));
            holder.sub.setVisibility(View.VISIBLE);
            holder.sub.setText(url);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editSource(index);
                }
            });
            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    remove(index);
                    return true;
                }
            });
        }

        @Override
        public int getItemCount() {
            return size();
        }
    }
}
