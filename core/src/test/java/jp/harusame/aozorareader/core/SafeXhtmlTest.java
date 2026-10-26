package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** What an episode's author writes cannot become script or an outside fetch in the reader. */
class SafeXhtmlTest {
    static final String URL = "https://ncode.syosetu.com/n0000aa/1/";

    static String format(String extraParagraph) throws Exception {
        String html = Files.readString(Path.of("src/test/resources/narou-episode-sample.html"));
        int i = html.indexOf("<p id=\"L4\">");
        html = html.substring(0, i) + extraParagraph + html.substring(i);
        return String.join("", new EpisodeFormatter(new File("src/main/resources")).toXhtmlSections(html, URL));
    }

    @Test
    void anAuthorsLiteralTagIsNotATagInTheReader() throws Exception {
        // なろう serves the author's typed "<a …>" as text (&lt;a …&gt;); the conversion used to turn it into a
        // real link with its onclick, over the whole page.
        String all = format("<p id=\"L3x\">&lt;a href=\"http.x\" style=\"position:fixed;inset:0\" onclick=\"Android.shelf()\"&gt;TAP&lt;/a&gt;</p>");
        assertFalse(all.contains("onclick"), all);
        assertFalse(all.contains("Android.shelf"), all);
        assertFalse(all.matches("(?s).*<a[\\s>].*"), "no link element: " + all);
        assertFalse(all.contains("position:fixed"), all);
        assertTrue(all.contains("<ruby>電車<rt>でんしゃ</rt></ruby>"), "ruby stays");
    }

    @Test
    void anIllustrationNeitherBreaksTheEpisodeNorIsFetched() throws Exception {
        String all = format("<p id=\"L3y\"><a href=\"https://12345.mitemin.net/i000/\"><img src=\"https://12345.mitemin.net/userpageimage/viewimagebig/icode/i000/\" alt=\"挿絵\"></a></p>");
        assertFalse(all.contains("<img"), all);
        assertTrue(all.contains("電波の届かない駅"), "the rest of the episode is there");
    }

    @Test
    void anAuthorsIdCannotTakeThePagesOwn() throws Exception {
        // id="toc" in the text came before the page's #toc, and its script stopped: nothing turned.
        String all = format("<p id=\"L3z\">&lt;span id=\"toc\"&gt;目次のふり&lt;/span&gt;</p>");
        assertTrue(org.jsoup.Jsoup.parseBodyFragment(all).select("[id]").isEmpty(), "no element with an id: " + all);
        assertFalse(SafeXhtml.clean("<span id=\"toc\">x</span><div id=\"info\">y</div>").contains("id="), "clean drops ids");
    }

    @Test
    void cleanKeepsTheFormattersStructure() {
        String in = "<div class=\"chap2\" id=\"kobo.1.1\">見出し</div><p><span class=\"tcy\"><span>12</span></span><span onclick=\"x()\">字</span></p>";
        String out = SafeXhtml.clean(in);
        assertTrue(out.contains("<div class=\"chap2\">見出し</div>"), "class kept, id dropped: " + out);
        assertTrue(out.contains("<span class=\"tcy\"><span>12</span></span>"), out);
        assertFalse(out.contains("onclick"), out);
    }
}
