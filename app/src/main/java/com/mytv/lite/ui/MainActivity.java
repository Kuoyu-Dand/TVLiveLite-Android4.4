package com.mytv.lite.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.mytv.lite.Prefs;
import com.mytv.lite.R;
import com.mytv.lite.data.Channel;
import com.mytv.lite.player.PlayerActivity;

/**
 * 单 Activity + Fragment 导航，避免在老设备上频繁创建 Activity 带来的开销。
 */
public class MainActivity extends AppCompatActivity {

    /** 防止首页一帧内被多次触发直进播放 */
    private boolean autoPlayFired;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            Intent intent = getIntent();
            boolean openSettings = intent != null && intent.getBooleanExtra("open_settings", false);
            if (openSettings) {
                show(new SettingsFragment(), false);
            } else {
                show(new DashboardFragment(), false);
                maybeAutoPlay();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // singleTask：从桌面再次点图标走这里，同样直进上次频道
        if (intent != null && intent.getBooleanExtra("open_settings", false)) {
            return;
        }
        if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
            maybeAutoPlay();
        }
    }

    /**
     * 「开机直进直播」：有上次频道就直接盖一层播放页，首页留在底下，
     * 这样播放页按返回还能回到首页，而不是把整个 App 关掉。
     */
    private void maybeAutoPlay() {
        if (autoPlayFired || !Prefs.autoPlayLast()) {
            return;
        }
        final String last = Prefs.lastChannel();
        if (last.isEmpty() || !Prefs.hasEnabledIptvSource()) {
            return;
        }
        autoPlayFired = true;
        // 等首页完成首次布局再起播放页，返回时不会看到一帧白屏
        getWindow().getDecorView().post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) {
                    PlayerActivity.start(MainActivity.this, last);
                }
            }
        });
    }

    public void show(Fragment fragment, boolean addToBackStack) {
        FragmentTransaction t = getSupportFragmentManager().beginTransaction();
        t.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);
        t.replace(R.id.container, fragment);
        if (addToBackStack) {
            t.addToBackStack(null);
        }
        t.commitAllowingStateLoss();
    }

    public void play(Channel channel) {
        if (channel != null) {
            PlayerActivity.start(this, channel.name);
        }
    }

    public void play(String channelName) {
        PlayerActivity.start(this, channelName);
    }

    public void openChannels(int mode) {
        show(ChannelsFragment.newInstance(mode), true);
    }

    public void openSettings() {
        show(new SettingsFragment(), true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU
                && getSupportFragmentManager().getBackStackEntryCount() == 0) {
            show(new SettingsFragment(), true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            return;
        }
        super.onBackPressed();
    }
}
