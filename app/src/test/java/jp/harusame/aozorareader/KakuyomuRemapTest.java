package jp.harusame.aozorareader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** A カクヨム place and its seen count follow their episodes when the work's list changes. */
class KakuyomuRemapTest {
    static JSONObject list(String... ids) throws Exception {
        JSONArray eps = new JSONArray();
        for (String id : ids) eps.put(new JSONObject().put("id", id));
        return new JSONObject().put("eps", eps);
    }

    @Test
    void aPlaceKeptByIdShowsItsNewNumber() throws Exception {
        Shelf.Work w = new Shelf.Work();
        w.lastEpisodeId = "c";
        w.lastEpisode = 3;
        KakuyomuSite.remap(w, list("a", "b", "c"), list("x", "y", "a", "b", "c"));
        assertEquals("c", w.lastEpisodeId, "the place is the episode");
        assertEquals(5, w.lastEpisode, "only the number shown moves");
    }

    @Test
    void aDeletedPlaceKeepsTheSlotWhereItWas() throws Exception {
        // Inserted and deleted in one change: [a,b,c] at b → [x,a,c]; b's slot is after a, before c.
        Shelf.Work w = new Shelf.Work();
        w.lastEpisodeId = "b";
        w.lastEpisode = 2;
        KakuyomuSite.remap(w, list("a", "b", "c"), list("x", "a", "c"));
        assertEquals("b", w.lastEpisodeId);
        assertEquals(3, w.lastEpisode, "the slot after a (2), before c");
    }

    @Test
    void aDeletedPlaceFollowsFurtherChanges() throws Exception {
        // [a,b,c,d] at c → [a,b,d] (slot 3: after b) → [a,d] (slot 2: after a, before d).
        Shelf.Work w = new Shelf.Work();
        w.lastEpisodeId = "c";
        w.lastEpisode = 3;
        KakuyomuSite.remap(w, list("a", "b", "c", "d"), list("a", "b", "d"));
        assertEquals(3, w.lastEpisode);
        KakuyomuSite.remap(w, list("a", "b", "d"), list("a", "d"));
        assertEquals(2, w.lastEpisode);
        KakuyomuSite.remap(w, list("a", "d"), list("d"));
        assertEquals(1, w.lastEpisode, "nothing before it is left: the first slot");
    }

    @Test
    void aDeletedPlacesSlotStaysWhenTheListIsSavedUnchanged() throws Exception {
        Shelf.Work w = new Shelf.Work();
        w.lastEpisodeId = "c";
        w.lastEpisode = 3;
        KakuyomuSite.remap(w, list("a", "b", "c", "d"), list("a", "b", "d"));
        KakuyomuSite.remap(w, list("a", "b", "d"), list("a", "b", "d"));
        KakuyomuSite.remap(w, list("a", "b", "d"), list("a", "b", "d"));
        assertEquals(3, w.lastEpisode);
        Shelf.Work first = new Shelf.Work();
        first.lastEpisodeId = "a";
        first.lastEpisode = 1;
        KakuyomuSite.remap(first, list("a", "b"), list("b"));
        KakuyomuSite.remap(first, list("b"), list("b"));
        assertEquals(1, first.lastEpisode);
    }

    @Test
    void aDeletedPlaceThatComesBackTakesItsNumber() throws Exception {
        Shelf.Work w = new Shelf.Work();
        w.lastEpisodeId = "b";
        w.lastEpisode = 2;
        KakuyomuSite.remap(w, list("a", "b", "c"), list("a", "c"));
        KakuyomuSite.remap(w, list("a", "c"), list("x", "a", "c", "b"));
        assertEquals(4, w.lastEpisode);
    }

    @Test
    void aPlaceDeletedAtTheEndIsFollowedByANewEpisode() throws Exception {
        // [a,b,c] at c → [a,b] (slot 3, past the end) → [a,b,d]: d is next (idAt(3)).
        Shelf.Work w = new Shelf.Work();
        w.lastEpisodeId = "c";
        w.lastEpisode = 3;
        KakuyomuSite.remap(w, list("a", "b", "c"), list("a", "b"));
        assertEquals(3, w.lastEpisode);
        KakuyomuSite.remap(w, list("a", "b"), list("a", "b", "d"));
        assertEquals(3, w.lastEpisode);
        assertEquals("d", KakuyomuSite.idAt(list("a", "b", "d"), w.lastEpisode));
    }

    @Test
    void aPageTurnTakesTheNumberFromTheListAsItIsNow() throws Exception {
        Shelf.Work w = new Shelf.Work();
        w.lastEpisodeId = "b";
        w.lastEpisode = 2;
        // Listed: its number in the list now (an insert moved it).
        assertEquals(true, KakuyomuSite.place(w, "b", list("x", "a", "b")));
        assertEquals(3, w.lastEpisode);
        // Deleted meanwhile (remap set the slot): the slot is not overwritten.
        w.lastEpisode = 2;
        assertEquals(true, KakuyomuSite.place(w, "b", list("a", "c")));
        assertEquals(2, w.lastEpisode);
        assertEquals("b", w.lastEpisodeId);
        // Another episode no longer listed does not take the place.
        assertEquals(false, KakuyomuSite.place(w, "z", list("a", "c")));
        assertEquals("b", w.lastEpisodeId);
        assertEquals(2, w.lastEpisode);
        // Another listed episode does.
        assertEquals(true, KakuyomuSite.place(w, "c", list("a", "c")));
        assertEquals("c", w.lastEpisodeId);
        assertEquals(2, w.lastEpisode);
    }

    @Test
    void aNumberedPlaceFromAnEarlierBuildGetsItsId() throws Exception {
        Shelf.Work w = new Shelf.Work();
        w.lastEpisode = 2;                                              // "b" in the list it was kept against
        KakuyomuSite.remap(w, list("a", "b", "c"), list("a", "x", "b", "c"));
        assertEquals("b", w.lastEpisodeId);
        assertEquals(3, w.lastEpisode);
    }

    @Test
    void anEpisodeInsertedBeforeThePlace() throws Exception {
        Shelf.Work w = new Shelf.Work();
        w.lastEpisode = 3;                                              // "c"
        w.seen = 4;                                                     // a..d
        KakuyomuSite.remap(w, list("a", "b", "c", "d"), list("a", "閑話", "b", "c", "d", "e"));
        assertEquals(4, w.lastEpisode, "still c");
        assertEquals(4, w.seen, "a, b, c, d seen: 閑話 and e are new");
    }

    @Test
    void anEpisodeRemovedBeforeThePlace() throws Exception {
        Shelf.Work w = new Shelf.Work();
        w.lastEpisode = 3;
        w.seen = 3;
        KakuyomuSite.remap(w, list("a", "b", "c"), list("a", "c", "d"));
        assertEquals(2, w.lastEpisode, "still c");
        assertEquals(2, w.seen, "a and c were seen; d is new");
    }

    @Test
    void thePlacesEpisodeGoneLeavesThePlace() throws Exception {
        Shelf.Work w = new Shelf.Work();
        w.lastEpisode = 2;
        w.seen = 2;
        KakuyomuSite.remap(w, list("a", "b"), list("a", "x"));
        assertEquals(2, w.lastEpisode);
        assertEquals(1, w.seen);
    }

    @Test
    void twoChangesInARowKeepTheNewOneNew() throws Exception {
        // [a,b,c] all seen; x inserted; then c removed: x was never seen and must stay new.
        Shelf.Work w = new Shelf.Work();
        w.lastEpisode = 1;
        w.seen = 3;
        KakuyomuSite.remap(w, list("a", "b", "c"), list("a", "x", "b", "c"));
        assertEquals(3, w.seen);
        KakuyomuSite.remap(w, list("a", "x", "b", "c"), list("a", "x", "b"));
        assertEquals(2, w.seen, "a and b; x is still new");
        assertEquals("x", w.unseenIds, "only the unseen are kept");
    }

    @Test
    void openingMarksAllSeenAndNewOnesComeAfter() throws Exception {
        Shelf.Work w = new Shelf.Work();
        w.seen = 0;
        KakuyomuSite.seeAll(null, w);
        w.seen = 3;
        KakuyomuSite.remap(w, list("a", "b", "c"), list("a", "b", "c", "d", "e"));
        assertEquals(3, w.seen);
        assertEquals("d,e", w.unseenIds);
    }

    @Test
    void seenIdsFromAnEarlierBuildAreHonoured() throws Exception {
        // Saved as seenIds "a,b,c" over [a,x,b,c] with seen=3: x was never seen, even after c goes.
        Shelf.Work w = Shelf.Work.fromJson(new JSONObject("{\"ncode\":\"kakuyomu-1\",\"seen\":3,\"seenIds\":\"a,b,c\"}"));
        KakuyomuSite.remap(w, list("a", "x", "b", "c"), list("a", "x", "b"));
        assertEquals(2, w.seen);
        assertEquals("x", w.unseenIds);
    }

    @Test
    void legacyIdsSurviveASaveBeforeTheirRemap() throws Exception {
        // A page turn saves the shelf before any remap: the old ids must still be there afterwards.
        Shelf.Work w = Shelf.Work.fromJson(new JSONObject("{\"ncode\":\"kakuyomu-1\",\"seen\":3,\"seenIds\":\"a,b,c\"}"));
        Shelf.Work again = Shelf.Work.fromJson(new JSONObject(w.toJson().toString()));
        KakuyomuSite.remap(again, list("a", "x", "b", "c"), list("a", "x", "b"));
        assertEquals("x", again.unseenIds);
        // and once converted, they are not written again
        assertEquals(false, again.toJson().has("seenIds"));
    }

    @Test
    void anEmptyLegacyValueMeansNoIdsWereKept() throws Exception {
        // That build saved "" by default: it is not "nothing seen" (which would make every episode new).
        Shelf.Work w = Shelf.Work.fromJson(new JSONObject("{\"ncode\":\"kakuyomu-1\",\"seen\":3,\"seenIds\":\"\"}"));
        KakuyomuSite.remap(w, list("a", "b", "c"), list("a", "b", "c", "d"));
        assertEquals(3, w.seen);
        assertEquals("d", w.unseenIds);
    }
}
