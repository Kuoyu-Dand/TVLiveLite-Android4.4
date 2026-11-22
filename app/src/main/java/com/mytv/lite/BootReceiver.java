package com.mytv.lite;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.mytv.lite.ui.MainActivity;

/**
 * 开机自启（需在设置中打开）
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        if (!Prefs.bootAutoStart()) {
            return;
        }
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        } catch (Throwable ignored) {
            // 部分盒子限制后台启动，失败就算了
        }
    }

    @SuppressWarnings("unused")
    private void scheduleAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                new Intent(context, BootReceiver.class), 0);
        am.set(AlarmManager.RTC, System.currentTimeMillis() + 5000, pi);
    }
}
