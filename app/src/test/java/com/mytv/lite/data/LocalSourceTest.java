package com.mytv.lite.data;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.mytv.lite.Prefs;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * 从 U 盘导入的本地源：复制到私有目录后按普通本地文件加载。
 * 这里覆盖两个真实踩过的坑——GBK 编码的 txt 源，以及 .txt.gz 压缩源。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 19, application = com.mytv.lite.App.class)
public class LocalSourceTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.init(context);
        ChannelRepository.clearCache(context);
    }

    @Test
    public void gbkSourceShouldParse() throws Exception {
        File f = new File(importsDir(), "gbk_source.txt");
        String text = "央视频道,#genre#\n"
                + "CCTV1,http://a/1.m3u8\n"
                + "湖南卫视,http://a/2.m3u8\n";
        writeBytes(f, text.getBytes("GB18030"));

        load(f);
        assertNotNull("GBK 文本没解出来，频道会全部丢失",
                ChannelRepository.findByName("湖南卫视"));
        assertNotNull(ChannelRepository.findByName("CCTV1"));
    }

    @Test
    public void gzippedSourceShouldParse() throws Exception {
        File f = new File(importsDir(), "gz_source.txt");
        String text = "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"央视\",CCTV2\n"
                + "http://a/3.m3u8\n";
        GZIPOutputStream gz = new GZIPOutputStream(new FileOutputStream(f));
        gz.write(text.getBytes("UTF-8"));
        gz.close();

        load(f);
        assertNotNull("gz 源没有解压", ChannelRepository.findByName("CCTV2"));
    }

    private void load(File f) throws Exception {
        List<IptvSource> sources = new ArrayList<IptvSource>();
        sources.add(new IptvSource("U盘导入", f.getAbsolutePath(), true, true));
        Prefs.saveIptvSources(sources);
        ChannelRepository.loadSync(context, true);
        assertTrue("一条频道都没解析出来", ChannelRepository.all().size() > 0);
    }

    private File importsDir() throws IOException {
        File dir = new File(context.getFilesDir(), "imports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("mkdir failed");
        }
        return dir;
    }

    private void writeBytes(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
    }
}
