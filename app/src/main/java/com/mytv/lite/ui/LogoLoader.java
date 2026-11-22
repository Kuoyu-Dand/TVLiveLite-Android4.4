package com.mytv.lite.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import com.mytv.lite.Prefs;
import com.mytv.lite.data.Channel;
import com.mytv.lite.net.Http;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 台标加载：自己写一个极简的异步图片加载器，避免引入 Glide 之类在老设备上偏重的库。
 * - 内存 LRU 缓存
 * - 3 线程并发，失败静默
 * - 按控件尺寸降采样，省内存
 */
public final class LogoLoader {

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(6 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    private LogoLoader() {
    }

    /**
     * 没有台标字段时，用公开的台标服务兜底（{标准名大写}.png）
     */
    public static String resolveUrl(Channel channel) {
        if (channel == null) {
            return null;
        }
        if (channel.logo != null && channel.logo.trim().length() > 6) {
            return channel.logo.trim();
        }
        String name = channel.standardName;
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String key = name.trim().toUpperCase().replace(" ", "");
        return "https://live.fanmingming.com/tv/" + key + ".png";
    }

    public static void load(ImageView view, Channel channel) {
        if (view == null) {
            return;
        }
        if (!Prefs.showLogo()) {
            view.setVisibility(View.GONE);
            return;
        }
        String url = resolveUrl(channel);
        if (url == null) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setTag(url);
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setVisibility(View.VISIBLE);
            return;
        }
        view.setVisibility(View.INVISIBLE);
        final int target = Ui.dp(view.getContext(), 40);
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = fetch(url, target);
                if (bitmap == null) {
                    return;
                }
                CACHE.put(url, bitmap);
                Ui.runOnUi(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(view.getTag())) {
                            view.setImageBitmap(bitmap);
                            view.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    private static Bitmap fetch(String url, int target) {
        try {
            byte[] data = Http.getBytes(url, null, 8000);
            if (data == null || data.length == 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            int size = Math.max(1, Math.min(opts.outWidth, opts.outHeight));
            int sample = 1;
            while (size / sample > target * 2) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Throwable t) {
            return null;
        }
    }
}
