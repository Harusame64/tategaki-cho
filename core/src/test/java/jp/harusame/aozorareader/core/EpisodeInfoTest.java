package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The credit the reader shows: work, author and links, read from the episode page. */
class EpisodeInfoTest {
    static final String URL = "https://ncode.syosetu.com/n0000aa/1/";

    @Test
    void theWorkAndTheAuthorAreRead() throws Exception {
        String html = Files.readString(Path.of("src/test/resources/narou-episode-sample.html"), StandardCharsets.UTF_8);
        EpisodeInfo i = EpisodeInfo.fromNarou(html, URL);
        assertEquals("試作作品", i.workTitle);
        assertEquals("https://ncode.syosetu.com/n0000aa/", i.workUrl);
        assertEquals("試作作者", i.author);
        assertEquals("https://mypage.syosetu.com/000000/?ref=ncodeannounce", i.authorUrl);
        assertEquals("1/3", i.number);
        assertEquals("https://ncode.syosetu.com/n0000aa/2/", i.nextUrl);
        assertNull(i.prevUrl);
    }

    @Test
    void anAuthorWithoutAPageIsReadFromTheText() {
        String html = "<div class=\"c-announce\"><a href=\"/n0000aa/\">試作作品</a>&nbsp;&nbsp;作者：名無し <br><span>第一章</span></div>"
                + "<div class=\"c-pager\"><a href=\"/n0000aa/1/\">前へ</a><a href=\"/n0000aa/3/\">次へ</a></div>";
        EpisodeInfo i = EpisodeInfo.fromNarou(html, "https://ncode.syosetu.com/n0000aa/2/");
        assertEquals("名無し", i.author);
        assertNull(i.authorUrl);
        assertEquals("https://ncode.syosetu.com/n0000aa/1/", i.prevUrl);
        assertEquals("https://ncode.syosetu.com/n0000aa/3/", i.nextUrl);
    }

    @Test
    void theFirstLinksAndTheFirstPagerWin() {
        // Two work links, two author pages and two pagers (top and bottom, different hrefs): the first of each.
        String html = "<div class=\"c-announce\"><a href=\"/n0000aa/\">本の題</a><a href=\"/n9999zz/\">別作品</a>"
                + "<a href=\"https://mypage.syosetu.com/1/\">作者一</a><a href=\"https://mypage.syosetu.com/2/\">作者二</a></div>"
                + "<div class=\"c-pager\"><a href=\"/n0000aa/3/\">次へ</a></div>"
                + "<div class=\"c-pager\"><a href=\"/n0000aa/9/\">次へ</a></div>";
        EpisodeInfo i = EpisodeInfo.fromNarou(html, "https://ncode.syosetu.com/n0000aa/2/");
        assertEquals("https://ncode.syosetu.com/n0000aa/", i.workUrl);
        assertEquals("本の題", i.workTitle);
        assertEquals("https://mypage.syosetu.com/1/", i.authorUrl);
        assertEquals("作者一", i.author);
        assertEquals("https://ncode.syosetu.com/n0000aa/3/", i.nextUrl);
    }

    @Test
    void aPlainTextAuthorIsKeptWholeUpToTheNextElement() {
        // A name with a space, then other text after a <br>: the credit is the whole name and nothing more.
        String html = "<div class=\"c-announce\"><a href=\"/n0000aa/\">試作作品</a>&nbsp;&nbsp;作者：山田 太郎 <br><span>第一章</span> 更新あり</div>";
        assertEquals("山田 太郎", EpisodeInfo.fromNarou(html, "https://ncode.syosetu.com/n0000aa/1/").author);
    }
}
