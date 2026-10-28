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

    /** すべて縦向き leaves a URL in the text as it is: full-width, it would no longer be a link. */
    @Test
    void uprightLeavesUrlsAlone() throws Exception {
        String all = format("https://example.com/abc を見たKing Fisher。", true);
        assertTrue(all.contains("https://example.com/abc"), all);
        assertTrue(all.contains("Ｋｉｎｇ Ｆｉｓｈｅｒ"), all);
    }

    /** The setting belongs to one formatter: the next one, without it, keeps narou.rb's rule. */
    @Test
    void theSettingDoesNotCarryOver() throws Exception {
        format("King Fisher。", true);
        assertTrue(format("King Fisher。", false).contains("King Fisher。"));
    }

    /**
     * The English-run class is ASCII on Android too. On a JVM \\w is ASCII, so a \\w put back passes every other test
     * here; compiled with UNICODE_CHARACTER_CLASS (what Android's ICU does), it would match kanji and kana.
     */
    @Test
    void theEnglishRunIsAsciiUnderUnicodeRules() {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(com.github.hmdev.web.WebAozoraConverter.ENGLISH_RUN.pattern(),
                java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);
        assertTrue(!p.matcher("漢").find() && !p.matcher("か").find() && !p.matcher("ａ").find(), p.pattern());
        assertTrue(p.matcher("PK").find());
    }
}
