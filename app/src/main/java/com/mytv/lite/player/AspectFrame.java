package com.mytv.lite.player;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 自己实现的画面比例容器（fit / 拉伸 / 裁剪）。
 * 不用 ExoPlayer 的 AspectRatioFrameLayout，这样系统播放器和 ExoPlayer 能共用同一套渲染视图。
 */
public class AspectFrame extends FrameLayout {

    public static final int MODE_FIT = 0;
    public static final int MODE_FILL = 1;
    public static final int MODE_CROP = 2;

    private int videoWidth;
    private int videoHeight;
    private int mode = MODE_FIT;

    public AspectFrame(Context context) {
        super(context);
    }

    public AspectFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectFrame(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMode(int newMode) {
        if (mode != newMode) {
            mode = newMode;
            requestLayout();
        }
    }

    public void setVideoSize(int width, int height) {
        if (width > 0 && height > 0 && (width != videoWidth || height != videoHeight)) {
            videoWidth = width;
            videoHeight = height;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (videoWidth > 0 && videoHeight > 0 && width > 0 && height > 0
                && mode != MODE_FILL) {
            float videoRatio = (float) videoWidth / (float) videoHeight;
            float viewRatio = (float) width / (float) height;
            int targetW;
            int targetH;
            if (mode == MODE_CROP) {
                if (videoRatio > viewRatio) {
                    targetH = height;
                    targetW = (int) (height * videoRatio + 0.5f);
                } else {
                    targetW = width;
                    targetH = (int) (width / videoRatio + 0.5f);
                }
            } else {
                if (videoRatio > viewRatio) {
                    targetW = width;
                    targetH = (int) (width / videoRatio + 0.5f);
                } else {
                    targetH = height;
                    targetW = (int) (height * videoRatio + 0.5f);
                }
            }
            int cw = MeasureSpec.makeMeasureSpec(targetW, MeasureSpec.EXACTLY);
            int ch = MeasureSpec.makeMeasureSpec(targetH, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(cw, ch);
            }
            setMeasuredDimension(width, height);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int cw = child.getMeasuredWidth();
            int chh = child.getMeasuredHeight();
            int l = (width - cw) / 2;
            int t = (height - chh) / 2;
            child.layout(l, t, l + cw, t + chh);
        }
    }
}
