package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 英字: narou.rb's rule by default (a short word full-width, an English phrase or a word of 8+ letters half-width);
 * uprightLetters makes every letter full-width (narou.rb's enable_alphabet_force_zenkaku).
 */
class UprightLettersTest {
    static String format(String line, Boolean upright) throws Exception {
        java.io.File data = new java.io.File("src/main/resources");
        String html = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/narou-episode-sample.html"))
                .replaceFirst("</p>", "</p><p>" + line + "</p>");
        String url = "https://ncode.syosetu.com/n0000aa/1/";
        EpisodeFormatter f = upright == null ? new EpisodeFormatter(data, 0, false) : new EpisodeFormatter(data, 0, false, upright);
        return String.join("", f.toXhtmlSections(html, url));
    }

    @Test
    void narouRbByDefault() throws Exception {
        String all = format("帰還のKing FisherとPKとCharacterの話。", false);
        assertTrue(all.contains("帰還のKing FisherとＰＫとCharacterの話。"), all);
        assertEquals(all, format("帰還のKing FisherとPKとCharacterの話。", null), "the three-argument formatter keeps the rule");
    }

    @Test
    void uprightMakesEveryLetterFullWidth() throws Exception {
        String all = format("帰還のKing FisherとPKとCharacterの話。", true);
        assertTrue(all.contains("帰還のＫｉｎｇ ＦｉｓｈｅｒとＰＫとＣｈａｒａｃｔｅｒの話。"), all);
    }
}
