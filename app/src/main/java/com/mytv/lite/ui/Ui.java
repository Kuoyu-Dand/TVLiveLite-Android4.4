package com.mytv.lite.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.mytv.lite.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 界面小工具集合
 */
public final class Ui {

    public static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            MAIN.post(r);
        }
    }

    public static void toast(final Activity activity, final String msg) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public static String time(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    public static String dateTime(long millis) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    public static String clock() {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    public static String dateLine() {
        String[] weeks = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        Date d = new Date();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTime(d);
        int w = c.get(java.util.Calendar.DAY_OF_WEEK) - 1;
        String week = weeks[(w < 0 || w > 6) ? 0 : w];
        return new SimpleDateFormat("MM月dd日", Locale.getDefault()).format(d) + week;
    }

    /**
     * 单选列表对话框（设置项用）
     */
    public static AlertDialog listDialog(Context context, String title, String[] items,
                                         int checkedIndex,
                                         final DialogInterface.OnClickListener onPick) {
        AlertDialog.Builder b = new AlertDialog.Builder(context, R.style.DialogTheme);
        if (title != null && !title.isEmpty()) {
            b.setTitle(title);
        }
        b.setSingleChoiceItems(items, checkedIndex, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                if (onPick != null) {
                    onPick.onClick(dialog, which);
                }
            }
        });
        b.setNegativeButton("取消", null);
        AlertDialog d = b.create();
        d.show();
        return d;
    }

    public static AlertDialog confirm(Context context, String title, String message,
                                      final Runnable onOk) {
        AlertDialog.Builder b = new AlertDialog.Builder(context, R.style.DialogTheme);
        b.setTitle(title);
        b.setMessage(message);
        b.setNegativeButton("取消", null);
        b.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (onOk != null) {
                    onOk.run();
                }
            }
        });
        AlertDialog d = b.create();
        d.show();
        return d;
    }

    public interface OnText {
        void onText(String text);
    }

    /**
     * 单行输入对话框（订阅源地址、UA 等）
     */
    public static AlertDialog input(Context context, String title, String hint, String value,
                                    boolean multiline, final OnText onOk) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_input, null);
        TextView tvTitle = (TextView) view.findViewById(R.id.tvDialogTitle);
        final EditText et = (EditText) view.findViewById(R.id.etInput);
        tvTitle.setText(title);
        et.setHint(hint);
        et.setText(value == null ? "" : value);
        if (multiline) {
            et.setSingleLine(false);
            et.setMinLines(3);
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        if (value != null && !value.isEmpty()) {
            et.setSelection(value.length());
        }

        final AlertDialog d = new AlertDialog.Builder(context, R.style.DialogTheme)
                .setView(view)
                .create();
        d.show();

        view.findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        view.findViewById(R.id.btnOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = et.getText().toString().trim();
                d.dismiss();
                if (onOk != null) {
                    onOk.onText(text);
                }
            }
        });
        et.requestFocus();
        return d;
    }
}
