package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** A page of a work's 目次: chapters, episodes by number, and how many pages there are. */
class TocTest {
    static final String URL = "https://ncode.syosetu.com/n0000aa/";

    static String sub(int no, String title) {
        return "<div class=\"p-eplist__sublist\">\n<a href=\"/n0000aa/" + no + "/\" class=\"p-eplist__subtitle\">\n" + title
                + "\n</a>\n<div class=\"p-eplist__update\">\n2026/01/01 00:00\n</div>\n</div>\n";
    }

    @Test
    void chaptersAndEpisodesAreRead() {
        String html = "<div class=\"c-pager\"><div class=\"c-pager__pager\"><span class=\"c-pager__item\">最初へ</span>"
                + "<a href=\"/n0000aa/?p=2\" class=\"c-pager__item c-pager__item--next\">次へ</a>"
                + "<a href=\"/n0000aa/?p=4\" class=\"c-pager__item c-pager__item--last\">最後へ</a></div></div>"
                + "<div class=\"p-eplist\"><div class=\"p-eplist__chapter-title\">第1章</div>\n"
                + sub(1, "プロローグ") + sub(2, "第1話　はじまり")
                + "<div class=\"p-eplist__chapter-title\">第2章</div>\n" + sub(3, "第2話　つづき") + "</div>";
        Toc t = Toc.fromNarou(html, URL);
        assertEquals(4, t.pages);
        assertEquals(3, t.entries.size());
        assertEquals("第1章", t.entries.get(0).chapter);
        assertEquals(1, t.entries.get(0).no);
        assertEquals("プロローグ", t.entries.get(0).title);
        assertNull(t.entries.get(1).chapter);
        assertEquals("第1話　はじまり", t.entries.get(1).title);
        assertEquals("第2章", t.entries.get(2).chapter);
        assertEquals(3, t.entries.get(2).no);
    }

    @Test
    void aChapterHeadingAfterThePagesLastEpisodeIsHandedOn() {
        // 目次 pages hold 100 episodes: a chapter can begin right at the end of one, its episodes on the next.
        Toc t = Toc.fromNarou("<div class=\"p-eplist\">" + sub(100, "第百話")
                + "<div class=\"p-eplist__chapter-title\">第五章</div>\n</div>", URL);
        assertEquals(1, t.entries.size());
        assertEquals("第五章", t.trailingChapter);
        assertNull(Toc.fromNarou("<div class=\"p-eplist\"><div class=\"p-eplist__chapter-title\">第五章</div>" + sub(101, "第百一話") + "</div>", URL).trailingChapter);
    }

    @Test
    void aTocWithoutAPagerHasOnePage() {
        Toc t = Toc.fromNarou("<div class=\"p-eplist\">" + sub(1, "第一話") + "</div>", URL);
        assertEquals(1, t.pages);
        assertEquals(1, t.entries.size());
    }

    @Test
    void numbersComeFromTheLinksAndPagesFromTheHighestPagerLink() {
        // Episode numbers past 9 that do not follow on, an entry whose link is not an episode, and a pager whose
        // last link is not its highest page (12).
        String html = "<div class=\"c-pager\"><a href=\"/n0000aa/?p=12\">最後へ</a><a href=\"/n0000aa/?p=2\">次へ</a></div>"
                + "<div class=\"p-eplist\">" + sub(101, "第百一話")
                + "<div class=\"p-eplist__sublist\"><a href=\"/n0000aa/\" class=\"p-eplist__subtitle\">目次へ</a></div>"
                + sub(112, "第百十二話") + "</div>";
        Toc t = Toc.fromNarou(html, URL);
        assertEquals(12, t.pages);
        assertEquals(2, t.entries.size());
        assertEquals(101, t.entries.get(0).no);
        assertEquals(112, t.entries.get(1).no);
        assertEquals("第百十二話", t.entries.get(1).title);
    }
}
