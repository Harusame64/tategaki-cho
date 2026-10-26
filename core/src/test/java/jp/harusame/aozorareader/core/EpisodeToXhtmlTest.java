package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** narou episode HTML → Aozora text → XHTML, all on the JVM, as the app will do it. */
class EpisodeToXhtmlTest {
    @Test
    void theEpisodeBecomesVerticalXhtml() throws Exception {
        // Through EpisodeFormatter, the entry point the app calls.
        String html = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/narou-episode-sample.html"));
        List<String> sections = new EpisodeFormatter(new java.io.File("src/main/resources")).toXhtmlSections(html, "https://ncode.syosetu.com/n0000aa/1/");
        String all = String.join("\n<!-- section -->\n", sections);
        System.out.println("====\n" + all + "\n====");
        assertFalse(sections.isEmpty(), "at least one section");
        assertTrue(all.contains("<ruby>電車<rt>でんしゃ</rt></ruby>"), "ruby markup");
        assertTrue(all.contains("電波の届かない駅"), "the subtitle");
        assertTrue(all.matches("(?s).*<span class=\"(?:tcy|upr)\">(?:<span>)?!\\?(?:</span>)?</span>.*"), "!? itself is set upright or tate-chu-yoko");
    }
}
