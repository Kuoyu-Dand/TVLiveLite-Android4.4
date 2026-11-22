package com.mytv.lite.ui;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 存储卷枚举。
 * <p>
 * 老机顶盒（4.4）没有 StorageManager.getStorageVolumes（API 24 才有），
 * 所以这里走三条路取并集：StorageManager（24+）→ /proc/mounts → 常见厂商挂载点，
 * 再按规范路径去重。U 盘插上后系统会 vold 挂载到 /mnt/udisk、/mnt/usbhost1、
 * /storage/XXXX-XXXX 之类，逐个探测比瞎猜路径可靠。
 */
public final class StorageVolumes {

    public static class Volume {
        public final String label;
        public final File dir;

        Volume(String label, File dir) {
            this.label = label;
            this.dir = dir;
        }
    }

    /** 这些路径对 App 无意义（系统分区/虚拟挂载），列出来只会让人迷路 */
    private static final String[] EXCLUDE = {
            "/system", "/data", "/cache", "/dev", "/proc", "/sys", "/acct",
            "/mnt/asec", "/mnt/obb", "/mnt/shell", "/mnt/secure", "/mnt/runtime",
            "/mnt/media_rw", "/mnt/vendor", "/mnt/product", "/config", "/vendor",
            "/product", "/apex", "/linkerconfig", "/metadata", "/storage/self",
            "/data_mirror", "/mnt/expand/config", "/oem", "/persist", "/efs",
    };

    /** 可读的块设备文件系统类型（厂商五花八门，能列的尽量列上） */
    private static final String[] FS_INCLUDE = {
            "vfat", "exfat", "texfat", "tfs", "ntfs", "fuseblk", "msdos", "ums",
            "iso9660", "udf", "ext2", "ext3", "ext4", "f2fs", "sdcardfs", "esdfs",
            "fuse", "fuse.sdcardfs", "fuse.exfat", "fuse.ntfs", "fuse.usb",
    };

    /** 厂商自定义挂载点，覆盖国产盒子的常见命名 */
    private static final String[] CANDIDATES = {
            "/mnt/sdcard", "/sdcard", "/storage/sdcard0", "/storage/sdcard1",
            "/storage/emulated/0", "/storage/emulated/legacy",
            "/mnt/extsd", "/mnt/extSdCard", "/mnt/external_sd", "/mnt/sdcard/external_sd",
            "/mnt/sdcard2", "/mnt/sd-ext", "/mnt/sdcard-ext",
            "/mnt/usb", "/mnt/usb1", "/mnt/usb2", "/mnt/usbhost1", "/mnt/usbhost2",
            "/mnt/usbhost3", "/mnt/usbhost4", "/mnt/udisk", "/mnt/udisk1", "/mnt/udisk2",
            "/mnt/usb_storage", "/mnt/usbdisk", "/mnt/UsbDriveA", "/mnt/UsbDriveB",
            "/storage/usbhost1", "/storage/usb0", "/storage/usb1", "/storage/udisk",
            "/udisk", "/usb", "/storage",
    };

    private StorageVolumes() {
    }

    public static List<Volume> list(Context context) {
        Map<String, Volume> map = new LinkedHashMap<String, Volume>();

        File primary = Environment.getExternalStorageDirectory();
        if (primary != null) {
            add(map, primary, "内部存储");
        }

        if (Build.VERSION.SDK_INT >= 24) {
            collectFromStorageManager(context, map);
        }
        collectFromMounts(map);
        for (String p : CANDIDATES) {
            add(map, new File(p), null);
        }

        // 一个 U 盘都没找到时，把 /mnt 放出来让用户自己翻（个别盒子的挂载点没规律）
        if (map.size() <= 1) {
            add(map, new File("/mnt"), "全部挂载点");
        }

        return new ArrayList<Volume>(map.values());
    }

    private static void collectFromStorageManager(Context context, Map<String, Volume> map) {
        try {
            StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (sm == null) {
                return;
            }
            List<StorageVolume> volumes = sm.getStorageVolumes();
            if (volumes == null) {
                return;
            }
            for (StorageVolume v : volumes) {
                File dir = dirOf(v);
                if (dir == null) {
                    continue;
                }
                String label = null;
                try {
                    if (Build.VERSION.SDK_INT >= 24) {
                        CharSequence desc = v.getDescription(context);
                        if (desc != null && desc.length() > 0) {
                            label = desc.toString();
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (v.isRemovable() && (label == null || label.isEmpty())) {
                    label = "外接存储";
                }
                add(map, dir, label);
            }
        } catch (Throwable ignored) {
        }
    }

    /** getDirectory 要到 API 30，24~29 只能反射拿隐藏的 getPath */
    private static File dirOf(StorageVolume v) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return v.getDirectory();
            }
            Object path = v.getClass().getMethod("getPath").invoke(v);
            if (path instanceof String) {
                return new File((String) path);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void collectFromMounts(Map<String, Volume> map) {
        BufferedReader reader = null;
        try {
            File mounts = new File("/proc/mounts");
            if (!mounts.exists()) {
                mounts = new File("/proc/self/mounts");
            }
            reader = new BufferedReader(new FileReader(mounts));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split("\\s+");
                if (cols.length < 3) {
                    continue;
                }
                String point = cols[1];
                String fs = cols[2].toLowerCase(Locale.US);
                if (!point.startsWith("/") || isExcluded(point)) {
                    continue;
                }
                boolean fsOk = false;
                for (String s : FS_INCLUDE) {
                    if (fs.startsWith(s)) {
                        fsOk = true;
                        break;
                    }
                }
                String lower = point.toLowerCase(Locale.US);
                boolean nameOk = lower.contains("usb") || lower.contains("udisk")
                        || lower.contains("sdcard") || lower.contains("sd_card")
                        || lower.contains("extsd") || lower.contains("external_sd")
                        || lower.equals("/storage") || lower.equals("/mnt");
                if (!fsOk && !nameOk) {
                    continue;
                }
                add(map, new File(point), null);
            }
        } catch (Throwable ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void add(Map<String, Volume> map, File dir, String label) {
        if (dir == null) {
            return;
        }
        String path;
        try {
            path = dir.getCanonicalPath();
        } catch (Exception e) {
            path = dir.getAbsolutePath();
        }
        if (path == null || path.isEmpty() || isExcluded(path) || map.containsKey(path)) {
            return;
        }
        if (!dir.isDirectory() || !dir.canRead()) {
            return;
        }
        File[] probe = dir.listFiles();
        if (probe == null) {
            return;
        }
        if (label == null || label.trim().isEmpty()) {
            label = autoLabel(dir, path);
        }
        map.put(path, new Volume(label, dir));
    }

    private static boolean isExcluded(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.equals("/")) {
            return false;
        }
        for (String e : EXCLUDE) {
            if (p.equals(e) || p.startsWith(e + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String autoLabel(File dir, String path) {
        String p = path.toLowerCase(Locale.US);
        String n = dir.getName();
        if (n == null || n.isEmpty()) {
            n = path;
        }
        if (p.equals("/storage/emulated/0") || p.equals("/storage/emulated/legacy")
                || p.equals("/sdcard") || p.equals("/mnt/sdcard")) {
            return "内部存储";
        }
        if (p.contains("usb") || p.contains("udisk")) {
            return "U 盘 · " + n;
        }
        if (p.contains("sdcard1") || p.contains("sdcard2") || p.contains("sd-ext")
                || p.contains("extsd") || p.contains("external_sd") || p.contains("sd_card")) {
            return "SD 卡 · " + n;
        }
        if (p.equals("/storage") ) {
            return "全部存储 (/storage)";
        }
        if (p.equals("/mnt")) {
            return "全部挂载点 (/mnt)";
        }
        return "存储 · " + n;
    }
}
