package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 空行除去: the single blank line between web-novel paragraphs goes, one of two stays. (aozora keeps one
 * blank line within three lines of a heading, which the start of a text counts as, so the blanks here
 * come after three lines of narration.)
 */
class EmptyLinesTest {
    static final String TEXT = "　一行目。\n　二行目。\n　三行目。\n\n「一つ目の台詞」\n\n「二つ目の台詞」\n\n\n　場面が変わった。\n";

    static int blankLines(int remove) throws Exception {
        String all = String.join("", new AozoraFormatter("src/main/resources/", remove).toXhtmlSections(TEXT));
        return all.split("<p><br ?/?></p>", -1).length - 1;
    }

    @Test
    void keptAsWrittenByDefault() throws Exception {
        assertEquals(4, blankLines(0));
    }

    @Test
    void oneBlankLineTakenOffEachRun() throws Exception {
        assertEquals(1, blankLines(1));
    }

    @Test
    void theOneArgumentFormatterKeepsThemAsWritten() throws Exception {
        String all = String.join("", new AozoraFormatter("src/main/resources/").toXhtmlSections(TEXT));
        assertEquals(4, all.split("<p><br ?/?></p>", -1).length - 1);
    }

    /** The same through EpisodeFormatter, as the app calls it, on the なろう fixture (blank lines between paragraphs). */
    static int episodeBlankLines(EpisodeFormatter f) throws Exception {
        String html = java.nio.file.Files.readString(java.nio.file.Path.of("src/test/resources/narou-episode-sample.html"));
        String all = String.join("", f.toXhtmlSections(html, "https://ncode.syosetu.com/n0000aa/1/"));
        return all.split("<p><br ?/?></p>", -1).length - 1;
    }

    @Test
    void episodeFormatterPassesTheCountOn() throws Exception {
        java.io.File data = new java.io.File("src/main/resources");
        int kept = episodeBlankLines(new EpisodeFormatter(data)), zero = episodeBlankLines(new EpisodeFormatter(data, 0)),
                packed = episodeBlankLines(new EpisodeFormatter(data, 1));
        assertEquals(zero, kept, "the one-argument EpisodeFormatter keeps blank lines as written");
        org.junit.jupiter.api.Assertions.assertTrue(packed < zero, "packing drops some: " + packed + " < " + zero);
    }

    @Test
    void aSiteWithoutADefinitionIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> new EpisodeFormatter(new java.io.File("src/main/resources")).toXhtmlSections("<p>x</p>", "https://example.com/x"));
    }
}
