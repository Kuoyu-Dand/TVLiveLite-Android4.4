package com.mytv.lite.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.mytv.lite.R;
import com.mytv.lite.data.Channel;
import com.mytv.lite.player.PlayerActivity;

/**
 * 单 Activity + Fragment 导航，避免在老设备上频繁创建 Activity 带来的开销。
 */
public class MainActivity extends AppCompatActivity {

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
            }
        }
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
