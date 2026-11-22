package com.mytv.lite;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃日志：写文件 + 打开一个简单的错误页，方便老设备上排查问题。
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static Thread.UncaughtExceptionHandler sDefault;
    private static Context sContext;

    public static void install(Context context) {
        sContext = context.getApplicationContext();
        sDefault = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler());
    }

    public static File logFile(Context context) {
        return new File(context.getFilesDir(), "last_crash.txt");
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        String text = buildReport(ex);
        try {
            File f = logFile(sContext);
            FileOutputStream out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }

        try {
            Intent intent = new Intent(sContext, com.mytv.lite.ui.CrashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra("text", text);
            sContext.startActivity(intent);
        } catch (Throwable ignored) {
        }

        if (sDefault != null) {
            sDefault.uncaughtException(thread, ex);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private String buildReport(Throwable ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()));
        pw.println("版本: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        pw.println("机型: " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("系统: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println();
        ex.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }
}
