package jp.harusame.aozorareader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Which work and episode a URL names, for each site, and the app-side episode URLs. */
class ShelfUrlTest {
    @Test
    void narou() {
        assertEquals("n9999zx", Shelf.ncodeOf("見て https://ncode.syosetu.com/N9999ZX/12/ だよ"));
        assertArrayEquals(new Object[]{"n9999zx", 12}, Shelf.episodeOf("https://ncode.syosetu.com/n9999zx/12/"));
        assertEquals("https://ncode.syosetu.com/n9999zx/3/", Shelf.episodeUrl("n9999zx", 3));
        assertEquals("https://ncode.syosetu.com/n9999zx/", Shelf.workUrl("n9999zx"));
    }

    @Test
    void kakuyomu() {
        String w = "https://kakuyomu.jp/works/11054822664184406200/episodes/11054822664188522084";
        assertEquals("kakuyomu-11054822664184406200", Shelf.ncodeOf(w));
        assertNull(Shelf.episodeOf(w), "a real カクヨム episode URL is not numbered; the kept list numbers it");
        assertEquals("kakuyomu://11054822664184406200/4", Shelf.episodeUrl("kakuyomu-11054822664184406200", 4));
        assertArrayEquals(new Object[]{"kakuyomu-11054822664184406200", 4}, Shelf.episodeOf("kakuyomu://11054822664184406200/4"));
        assertEquals("https://kakuyomu.jp/works/11054822664184406200", Shelf.workUrl("kakuyomu-11054822664184406200"));
    }

    @Test
    void kakuyomuEpisodesAreNamedById() {
        String key = "kakuyomu-11054822664184406200";
        String u = Shelf.kkEpisodeUrl(key, "11054822664188522084");
        assertEquals("kakuyomu://11054822664184406200/e/11054822664188522084", u);
        assertArrayEquals(new String[]{key, "11054822664188522084"}, Shelf.kkEpisodeOf(u));
        assertEquals(key, Shelf.ncodeOf(u));
        assertNull(Shelf.episodeOf(u), "not a number: nothing reads the id as an episode number");
        assertNull(Shelf.kkEpisodeOf("kakuyomu://11054822664184406200/4"), "the older numbered form is not an id");
        assertEquals(true, Shelf.sameEpisode(u, Shelf.kkEpisodeUrl(key, "11054822664188522084")));
        assertEquals(false, Shelf.sameEpisode(u, Shelf.kkEpisodeUrl(key, "11054822664188522085")));
        assertEquals(false, Shelf.sameEpisode(u, "kakuyomu://11054822664184406200/4"), "an id and a number are never taken as one");
        assertEquals(true, Shelf.sameEpisode("https://ncode.syosetu.com/n9999zx/3/", Shelf.episodeUrl("n9999zx", 3)));
    }

    @Test
    void aKakuyomuPlaceOpensItsEpisodeById() throws Exception {
        Shelf.Work w = new Shelf.Work();
        w.ncode = "kakuyomu-11054822664184406200";
        w.lastEpisode = 4;
        assertEquals("kakuyomu://11054822664184406200/4", w.startUrl(), "a place from an earlier build: resolved on opening");
        w.lastEpisodeId = "11054822664188522084";
        assertEquals("kakuyomu://11054822664184406200/e/11054822664188522084", w.startUrl());
        Shelf.Work again = Shelf.Work.fromJson(new JSONObject(w.toJson().toString()));
        assertEquals("11054822664188522084", again.lastEpisodeId);
        assertNull(Shelf.Work.fromJson(new JSONObject("{\"ncode\":\"n9999zx\",\"lastEpisode\":3}")).lastEpisodeId);
    }

    @Test
    void aozora() {
        assertEquals("aozora-000773", Shelf.ncodeOf("https://www.aozora.gr.jp/cards/000148/card773.html"));
        assertArrayEquals(new Object[]{"aozora-000773", 2}, Shelf.episodeOf("aozora://000773/2"));
        assertEquals("aozora://000773/2", Shelf.episodeUrl("aozora-000773", 2));
    }

    @Test
    void numbersTooLongAreNotWorksAndDoNotThrow() {
        // A clipboard holding this used to crash the paste dialog (Integer.parseInt overflow).
        assertNull(Shelf.ncodeOf("https://www.aozora.gr.jp/cards/1/card99999999999.html"));
        assertNull(Shelf.episodeOf("https://ncode.syosetu.com/n9999zx/99999999999/"));
        assertNull(Shelf.episodeOf("aozora://000773/99999999999"));
        assertNull(Shelf.ncodeOf("https://example.com/n9999zx/"));
    }

    @Test
    void cachedEpisodeNames() {
        assertEquals(12, Shelf.number("12.html"));
        assertNull(Shelf.number("k11054822664188522084.html"));
        assertNull(Shelf.number("toc.json"));
    }

    @Test
    void aWorkSurvivesItsJson() throws Exception {
        Shelf.Work w = new Shelf.Work();
        w.ncode = "aozora-000773";
        w.title = "こころ";
        w.author = "夏目 漱石";
        w.episodes = 4;
        w.lastEpisode = 2;
        w.lastPage = 30;
        w.lastPages = 177;
        w.lastRead = 1234L;
        w.page = "https://www.aozora.gr.jp/cards/000148/card773.html";
        Shelf.Work back = Shelf.Work.fromJson(new JSONObject(w.toJson().toString()));
        assertEquals(w.toJson().toString(), back.toJson().toString());
        assertEquals(w.page, Shelf.workUrl(back));
    }
}
