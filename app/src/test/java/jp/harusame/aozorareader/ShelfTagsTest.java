package jp.harusame.aozorareader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Tags: kept with the work, offered defaults first, and narrowing the shelf with the words typed. */
class ShelfTagsTest {
    static Shelf.Work work(String title, String... tags) {
        Shelf.Work w = new Shelf.Work();
        w.ncode = "n0001aa";
        w.title = title;
        w.author = "作者";
        w.tags.addAll(Arrays.asList(tags));
        return w;
    }

    @Test
    void tagsSurviveTheJson() throws Exception {
        Shelf.Work w = work("本", "異世界", "読んでる");
        assertEquals(Arrays.asList("異世界", "読んでる"), Shelf.Work.fromJson(new JSONObject(w.toJson().toString())).tags);
    }

    @Test
    void aWorkSavedBeforeTagsHasNone() throws Exception {
        assertTrue(Shelf.Work.fromJson(new JSONObject("{\"ncode\":\"n0001aa\"}")).tags.isEmpty());
    }

    @Test
    void theDefaultsComeFirstThenTheOnesInUse() {
        assertEquals(Arrays.asList("読んでる", "読み終えた", "保留", "異世界", "妻に勧める"),
                Shelf.allTags(Arrays.asList(work("a", "異世界", "読んでる"), work("b", "妻に勧める"))));
    }

    @Test
    void aTagAndWordsNarrowTogether() {
        Shelf.Work a = work("剣と魔法", "異世界"), b = work("駅の話", "読み終えた");
        assertTrue(Shelf.matches(a, "", null));
        assertTrue(Shelf.matches(a, "", "異世界"));
        assertFalse(Shelf.matches(b, "", "異世界"));
        assertTrue(Shelf.matches(a, "魔法", "異世界"));
        assertFalse(Shelf.matches(a, "駅", "異世界"));
        assertTrue(Shelf.matches(b, "読み終え", null), "a typed word finds a tag too");
    }

    @Test
    void aTagNamedLikeTheAllChipIsNotOffered() {
        assertFalse(Shelf.allTags(Arrays.asList(work("a", "すべて", "異世界"))).contains("すべて"));
    }
}
