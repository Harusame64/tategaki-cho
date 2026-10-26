package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.github.hmdev.web.WebAozoraConverter;

/** One episode's HTML (narou's current structure, made up text) to Aozora text, with no network. */
class EpisodeToAozoraTextTest {
    static final String URL = "https://ncode.syosetu.com/n0000aa/1/";

    static String convert() throws Exception {
        File web = new File("src/main/resources/web");
        WebAozoraConverter conv = WebAozoraConverter.createWebAozoraConverter(URL, web);
        assertNotNull(conv, "the narou site definition loads from resources/web");
        String html;
        try (InputStream in = EpisodeToAozoraTextTest.class.getResourceAsStream("/narou-episode-sample.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Document doc = Jsoup.parse(html, URL);
        StringWriter out = new StringWriter();
        conv.episodeToAozoraText(out, doc);
        return out.toString();
    }

    @Test
    void theEpisodeBecomesAozoraText() throws Exception {
        String text = convert();
        System.out.println("----\n" + text + "\n----");
        assertTrue(text.contains("［＃中見出し］"), "the subtitle is a heading");
        assertTrue(text.contains("電波の届かない駅"), "the subtitle text");
        assertTrue(text.contains("《でんしゃ》"), "ruby becomes 《》");
        assertTrue(text.contains("地下へ潜った"), "the body");
        assertTrue(!text.startsWith("\n［＃改ページ］"), "no leading page break for a single episode");
    }
}
