package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** A カクヨム work page's __NEXT_DATA__: the work, its author, and its episodes by chapter. (Made-up data.) */
class KakuyomuWorkTest {
    static final String PAGE = "<html><head><title>試作 - カクヨム</title></head><body>"
            + "<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":{\"__APOLLO_STATE__\":{"
            + "\"Work:100\":{\"__typename\":\"Work\",\"id\":\"100\",\"title\":\"試作の作品\",\"serialStatus\":\"RUNNING\","
            + "\"author\":{\"__ref\":\"UserAccount:7\"},"
            + "\"tableOfContentsV2\":[{\"__ref\":\"TableOfContentsChapter:1\"},{\"__ref\":\"TableOfContentsChapter:2\"}]},"
            + "\"Work:999\":{\"__typename\":\"Work\",\"id\":\"999\",\"title\":\"関連作品\"},"
            + "\"UserAccount:7\":{\"__typename\":\"UserAccount\",\"name\":\"tester\",\"activityName\":\"試作作者\"},"
            + "\"TableOfContentsChapter:1\":{\"chapter\":{\"__ref\":\"Chapter:1\"},\"episodeUnions\":[{\"__ref\":\"Episode:11\"},{\"__ref\":\"Episode:12\"}]},"
            + "\"TableOfContentsChapter:2\":{\"chapter\":{\"__ref\":\"Chapter:2\"},\"episodeUnions\":[{\"__ref\":\"Episode:13\"}]},"
            + "\"Chapter:1\":{\"title\":\"第一章\"},\"Chapter:2\":{\"title\":\"第二章\"},"
            + "\"Episode:11\":{\"__typename\":\"Episode\",\"id\":\"11\",\"title\":\"序\"},"
            + "\"Episode:12\":{\"__typename\":\"Episode\",\"id\":\"12\",\"title\":\"一\"},"
            + "\"Episode:13\":{\"__typename\":\"Episode\",\"id\":\"13\",\"title\":\"二\"}"
            + "}}},\"query\":{\"workId\":\"100\"}}</script></body></html>";

    @Test
    void theWorkAndItsEpisodesAreRead() {
        KakuyomuWork w = KakuyomuWork.fromPage(PAGE);
        assertEquals("100", w.id);
        assertEquals("試作の作品", w.title);
        assertEquals("試作作者", w.author);
        assertEquals("https://kakuyomu.jp/users/tester", w.authorUrl);
        assertFalse(w.finished);
        assertEquals(3, w.episodes.size());
        assertEquals("11", w.episodes.get(0).id);
        assertEquals("第一章", w.episodes.get(0).chapter);
        assertNull(w.episodes.get(1).chapter);
        assertEquals("第二章", w.episodes.get(2).chapter);
        assertEquals("二", w.episodes.get(2).title);
    }

    @Test
    void aPageWithoutDataIsNoWork() {
        assertNull(KakuyomuWork.fromPage("<html><body>なし</body></html>"));
    }

    @Test
    void theRequestedWorkItsEpisodesOnlyAndTheOldTableOfContents() {
        // A related Work after the requested one, a union member that is not an Episode, and only the
        // older tableOfContents field.
        String page = "<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":{\"__APOLLO_STATE__\":{"
                + "\"Work:100\":{\"id\":\"100\",\"title\":\"求めた作品\",\"serialStatus\":\"COMPLETED\","
                + "\"tableOfContents\":[{\"__ref\":\"TableOfContentsChapter:1\"}]},"
                + "\"TableOfContentsChapter:1\":{\"episodeUnions\":[{\"__ref\":\"Episode:11\"},{\"__ref\":\"EpisodeReservation:9\"},{\"__ref\":\"Episode:12\"}]},"
                + "\"Episode:11\":{\"__typename\":\"Episode\",\"id\":\"11\",\"title\":\"一\"},"
                + "\"EpisodeReservation:9\":{\"__typename\":\"EpisodeReservation\",\"id\":\"9\",\"title\":\"予約\"},"
                + "\"Episode:12\":{\"__typename\":\"Episode\",\"id\":\"12\",\"title\":\"二\"},"
                // Related works, several, so whichever order the keys come in only Work:<workId> gives the title.
                + "\"Work:999\":{\"id\":\"999\",\"title\":\"関連作品\"},\"Work:1\":{\"id\":\"1\",\"title\":\"関連作品\"},"
                + "\"Work:5000\":{\"id\":\"5000\",\"title\":\"関連作品\"},\"Work:77\":{\"id\":\"77\",\"title\":\"関連作品\"},"
                + "\"Work:abc\":{\"id\":\"abc\",\"title\":\"関連作品\"},\"Work:z\":{\"id\":\"z\",\"title\":\"関連作品\"}"
                + "}}},\"query\":{\"workId\":\"100\"}}</script>";
        KakuyomuWork w = KakuyomuWork.fromPage(page);
        assertEquals("求めた作品", w.title);
        assertEquals(true, w.finished);
        assertEquals(2, w.episodes.size());
        assertEquals("12", w.episodes.get(1).id);
    }
}
