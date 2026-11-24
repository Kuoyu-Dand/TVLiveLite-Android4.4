package com.mytv.lite.player;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import com.mytv.lite.App;
import com.mytv.lite.Prefs;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import com.google.android.exoplayer2.trackselection.TrackSelectionParameters;

/**
 * 1.0.8：1080p 的源在老盒子上「有声无画」。
 * 解决办法是在轨道选择这一层给视频设上限，HLS 多码率源会自动落到 720p / 480p 那一路。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = App.class, qualifiers = "w1280dp-h720dp-mdpi")
public class QualityLimitTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.init(context);
        Prefs.setMaxQuality("auto");
    }

    @Test
    public void lowApiShouldCapAt720ByDefault() {
        // 4.1~4.3（API 16~18）默认封顶 720p
        assertEquals(720, Prefs.maxVideoHeightFor("auto", 16));
        assertEquals(720, Prefs.maxVideoHeightFor("auto", 18));
        assertEquals(720, Prefs.maxVideoHeightFor(null, 17));
        // 4.4 及以上交还给系统判断，不主动限制
        assertEquals(0, Prefs.maxVideoHeightFor("auto", 19));
        assertEquals(0, Prefs.maxVideoHeightFor("auto", 34));
    }

    @Test
    public void explicitChoiceAlwaysWins() {
        assertEquals(1080, Prefs.maxVideoHeightFor("1080", 17));
        assertEquals(720, Prefs.maxVideoHeightFor("720", 19));
        assertEquals(480, Prefs.maxVideoHeightFor("480", 34));
    }

    @Test
    public void illegalQualityFallsBackToAuto() {
        Prefs.putString(Prefs.K_MAX_QUALITY, "4k");
        assertEquals("auto", Prefs.maxQuality());
        Prefs.setMaxQuality("720p");
        assertEquals("非法档位应回落到 auto", "auto", Prefs.maxQuality());
    }

    @Test
    public void qualityParamsShouldLimitVideoTrack() {
        Prefs.setMaxQuality("720");
        TrackSelectionParameters params =
                PlayerActivity.qualityParams(TrackSelectionParameters.DEFAULT);
        assertEquals(720, params.maxVideoHeight);
        assertEquals(1440, params.maxVideoWidth);
        assertEquals(4_000_000, params.maxVideoBitrate);

        Prefs.setMaxQuality("480");
        params = PlayerActivity.qualityParams(TrackSelectionParameters.DEFAULT);
        assertEquals(480, params.maxVideoHeight);
        assertEquals(2_000_000, params.maxVideoBitrate);
    }

    @Test
    public void unlimitedQualityShouldKeepParametersUntouched() {
        // SDK 19 + auto → 不限制，参数应原样保留（DEFAULT 的上限是 Integer.MAX_VALUE）
        TrackSelectionParameters params =
                PlayerActivity.qualityParams(TrackSelectionParameters.DEFAULT);
        assertEquals(TrackSelectionParameters.DEFAULT.maxVideoHeight, params.maxVideoHeight);
        assertEquals(TrackSelectionParameters.DEFAULT.maxVideoWidth, params.maxVideoWidth);
        assertEquals(TrackSelectionParameters.DEFAULT.maxVideoBitrate, params.maxVideoBitrate);
        assertEquals(0, Prefs.maxVideoHeight());
    }
}
