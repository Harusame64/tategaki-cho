package jp.harusame.aozorareader;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import jp.harusame.aozorareader.core.EpisodeFormatter;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * docs/e2e.md U1: core formats on the phone as its unit tests say it does on a JVM. Android's regex (ICU) reads \d, \s
 * and \w as Unicode classes, a JVM as ASCII, so a pattern that relies on them passes every unit test and does something
 * else here (internal #5; #11 was \w).
 */
@RunWith(AndroidJUnit4.class)
public class CoreOnDeviceTest {
    static String format(String line) throws Exception {
        File dir = new File(E2E.ctx().getCacheDir(), "core-on-device");
        String[] names = {"chuki_alt.txt", "chuki_ivs.txt", "chuki_latin.txt", "chuki_tag.txt", "chuki_tag_suf.txt",
                "chuki_utf.txt", "web/ncode.syosetu.com/extract.txt"};
        for (String n : names) {
            File f = new File(dir, n);
            f.getParentFile().mkdirs();
            try (InputStream in = EpisodeFormatter.class.getResourceAsStream("/" + n);
                 java.io.OutputStream out = new java.io.FileOutputStream(f)) { in.transferTo(out); }
        }
        String html;
        try (InputStream in = E2E.ctx().getAssets().open("narou-episode-sample.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceFirst("</p>", "</p><p>" + line + "</p>");
        }
        String all = String.join("", new EpisodeFormatter(dir, 0, false, false).toXhtmlSections(html, "https://ncode.syosetu.com/n0000aa/1/"));
        int i = all.indexOf(line.substring(0, 2));
        return all.substring(i, all.indexOf("。", i) + 1);
    }

    @Test
    public void fullWidthDigitsAreLeftAsOnAJvm() throws Exception {
        // As core's unit tests format it on a JVM (UprightLettersTest's formatter, 2026-10-29).
        assertEquals("全角は１/２、半角は一/二、日付は２０２６/１０/２９。", format("全角は１/２、半角は1/2、日付は２０２６/１０/２９。"));
    }
}
