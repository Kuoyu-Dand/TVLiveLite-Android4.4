package com.mytv.lite.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.mytv.lite.R;

/**
 * 崩溃时显示堆栈，方便在电视上直接看到原因（老设备一般没有 adb）。
 */
public class CrashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String text = getIntent() == null ? null : getIntent().getStringExtra("text");
        if (text == null) {
            text = "未知错误";
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getResources().getColor(R.color.bg));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 32), Ui.dp(this, 28), Ui.dp(this, 32), Ui.dp(this, 28));

        TextView title = new TextView(this);
        title.setText("应用出错了");
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setTextSize(22);
        box.addView(title);

        TextView desc = new TextView(this);
        desc.setText("可以把下面的信息反馈给开发者。按返回键退出。");
        desc.setTextColor(getResources().getColor(R.color.text_secondary));
        desc.setTextSize(13);
        desc.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 16));
        box.addView(desc);

        TextView content = new TextView(this);
        content.setText(text);
        content.setTextColor(getResources().getColor(R.color.text_secondary));
        content.setTextSize(11);
        content.setGravity(Gravity.LEFT);
        box.addView(content);

        scroll.addView(box);
        setContentView(scroll);
    }
}
