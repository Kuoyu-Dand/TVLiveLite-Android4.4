package com.mytv.lite;

import android.content.Context;

import androidx.multidex.MultiDexApplication;

import com.mytv.lite.data.ChannelAlias;

/**
 * 应用入口。老设备上尽量少做启动期工作，频道/节目单都在后台线程懒加载。
 * 继承 MultiDexApplication：Android 4.4 需要 legacy multidex 才能拆包。
 */
public class App extends MultiDexApplication {

    private static App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Prefs.init(this);
        CrashHandler.install(this);
        // 频道名映射表在首次使用时加载（见 ChannelAlias.init）
        ChannelAlias.init(this);
    }

    public static Context ctx() {
        return instance;
    }
}
