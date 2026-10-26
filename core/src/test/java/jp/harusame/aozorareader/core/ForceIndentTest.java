package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 強制字下げ: a paragraph written flush gets a full-width space; dialogue and indented lines stay as written. */
class ForceIndentTest {
    static final String TEXT = "　一行目。\n　二行目。\n　三行目。\n地の文が字下げなしで始まる。\n「台詞」\n　字下げ済みの段落。\n";

    static String format(boolean indent) throws Exception {
        return String.join("", new AozoraFormatter("src/main/resources/", 0, indent).toXhtmlSections(TEXT));
    }

    @Test
    void aFlushParagraphIsIndented() throws Exception {
        String all = format(true);
        assertTrue(all.contains("<p>　地の文が"), all);
        assertTrue(all.contains("<p>「台詞」"), "dialogue is not indented: " + all);
        assertTrue(all.contains("<p>　字下げ済み") && !all.contains("　　字下げ済み"), "an indented line gets no second space: " + all);
    }

    @Test
    void offKeepsTheTextAsWritten() throws Exception {
        assertTrue(format(false).contains("<p>地の文が"));
        String two = String.join("", new AozoraFormatter("src/main/resources/", 0).toXhtmlSections(TEXT));
        assertEquals(format(false), two, "the two-argument formatter does not indent");
    }

    /** Through EpisodeFormatter, as the app calls it: the なろう fixture gains indents only when asked. */
    @Test
    void episodeFormatterPassesItOn() throws Exception {
        java.io.File data = new java.io.File("src/main/resources");
        String html = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/narou-episode-sample.html"));
        String url = "https://ncode.syosetu.com/n0000aa/1/";
        String off = String.join("", new EpisodeFormatter(data, 0).toXhtmlSections(html, url));
        String on = String.join("", new EpisodeFormatter(data, 0, true).toXhtmlSections(html, url));
        assertTrue(count(on, "<p>　") > count(off, "<p>　"), count(on, "<p>　") + " > " + count(off, "<p>　"));
    }

    static int count(String s, String what) {
        return s.split(java.util.regex.Pattern.quote(what), -1).length - 1;
    }
}
