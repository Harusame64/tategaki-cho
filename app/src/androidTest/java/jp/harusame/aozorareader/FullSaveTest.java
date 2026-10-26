package jp.harusame.aozorareader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** docs/e2e.md F1–F12: 全話を保存, and 保存した話を消す that keeps the work. No real site is reached. */
@RunWith(AndroidJUnit4.class)
public class FullSaveTest {
    ActivityScenario<ShelfActivity> shelf;

    @Before
    public void setUp() throws Exception {
        org.junit.Assume.assumeTrue("全話を保存 is in the personal build only", FullSave.ENABLED);
        E2E.reset();
        E2E.seedNarou(3, 5);
        Shelf.update(E2E.ctx(), s -> { Shelf.Work w = s.find(E2E.NCODE); w.episodes = 6; w.seen = 6; });
        for (int n = 4; n <= 6; n++)
            E2E.net.put(Shelf.episodeUrl(E2E.NCODE, n), E2E.narouEpisode(n, 6, "第" + n + "話　試験", 5, "").getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void tearDown() throws Exception {
        if (shelf != null) shelf.close();
        FullSave.STOP.add(E2E.NCODE);
        E2E.waitUntil(FullSave.PROGRESS::isEmpty, "no save left running");
    }

    static File kept(int n) {
        return new File(E2E.ctx().getFilesDir(), "episodes/" + E2E.NCODE + "/" + n + ".html");
    }

    /** F1: the episodes not kept are fetched, one each; the kept ones are not asked for again, nor waited for. */
    @Test
    public void savesEachEpisodeNotKeptOnce() throws Exception {
        FullSave.GAP_MS = 1_500;
        long t0 = android.os.SystemClock.uptimeMillis();
        FullSave.start(E2E.ctx(), E2E.NCODE);
        E2E.waitUntil(() -> FullSave.PROGRESS.isEmpty() && kept(6).isFile(), "all six kept");
        for (int n = 1; n <= 6; n++) assertTrue("episode " + n, kept(n).isFile());
        assertEquals(java.util.Arrays.asList(Shelf.episodeUrl(E2E.NCODE, 4), Shelf.episodeUrl(E2E.NCODE, 5), Shelf.episodeUrl(E2E.NCODE, 6)),
                new java.util.ArrayList<>(Net.asked));
        long took = android.os.SystemClock.uptimeMillis() - t0;
        assertTrue("three gaps, not six: " + took + " ms", took < 8_000);
    }

    /** F2: 止める during the wait ends it before the next fetch. */
    @Test
    public void stoppingEndsItBeforeTheNextFetch() throws Exception {
        FullSave.GAP_MS = 3_000;
        FullSave.start(E2E.ctx(), E2E.NCODE);
        E2E.waitUntil(() -> { int[] p = FullSave.PROGRESS.get(E2E.NCODE); return p != null && p[1] == 6; }, "counted, and waiting before the first fetch");
        android.os.SystemClock.sleep(500);
        FullSave.stop(E2E.NCODE);                                          // during the wait
        E2E.waitUntil(FullSave.PROGRESS::isEmpty, "the save ended");
        assertEquals("nothing fetched after 止める", 0, Net.asked.size());
        assertFalse(kept(4).isFile());
    }

    /** F4: a カクヨム save stopped while queued behind another reaches nothing, not even its list. */
    @Test
    public void aSaveStoppedWhileQueuedReachesNothing() throws Exception {
        KakuyomuTest.addAt(KakuyomuTest.A);
        Net.asked.clear();
        FullSave.GAP_MS = 3_000;
        FullSave.start(E2E.ctx(), E2E.NCODE);                              // runs first, waiting before its fetch
        E2E.waitUntil(() -> { int[] p = FullSave.PROGRESS.get(E2E.NCODE); return p != null && p[1] == 6; }, "the first save counted");
        FullSave.start(E2E.ctx(), KakuyomuTest.KEY);                       // queued behind it
        FullSave.stop(KakuyomuTest.KEY);
        FullSave.stop(E2E.NCODE);
        E2E.waitUntil(FullSave.PROGRESS::isEmpty, "both ended");
        assertEquals("nothing asked of either site: " + Net.asked, 0, Net.asked.size());
    }

    /** F5: 保存容量の整理's 保存した話をすべて消す during a save stops it; nothing cleared is fetched back. */
    @Test
    public void clearingFromStorageStopsASave() throws Exception {
        FullSave.GAP_MS = 3_000;
        FullSave.start(E2E.ctx(), E2E.NCODE);
        E2E.waitUntil(() -> { int[] p = FullSave.PROGRESS.get(E2E.NCODE); return p != null && p[1] == 6; }, "the save counted");
        shelf = ActivityScenario.launch(new Intent(E2E.ctx(), ShelfActivity.class));
        E2E.device().wait(Until.findObject(By.desc("その他")), 5_000).click();
        E2E.device().wait(Until.findObject(By.text("保存容量の整理")), 5_000).click();
        E2E.device().wait(Until.findObject(By.text(E2E.TITLE)), 5_000).click();
        E2E.device().wait(Until.findObject(By.text("保存した話をすべて消す")), 5_000).click();
        E2E.waitUntil(FullSave.PROGRESS::isEmpty, "the save ended");
        for (int n = 1; n <= 6; n++) assertFalse("episode " + n + " not kept", kept(n).isFile());
        // One fetch may have come before the clearing (the menus take a while); none after it.
        assertTrue("at most the one before: " + Net.asked, Net.asked.size() <= 1);
    }

    /** F6: with no screen shown the save waits, fetching nothing, and goes on once one is shown. */
    @Test
    public void aSaveWaitsWhileTheAppIsNotInFront() throws Exception {
        FullSave.ANY_TIME = false;
        try {
            FullSave.start(E2E.ctx(), E2E.NCODE);
            android.os.SystemClock.sleep(2_500);
            assertEquals("nothing fetched with no screen shown: " + Net.asked, 0, Net.asked.size());
            assertTrue("still waiting", FullSave.PROGRESS.containsKey(E2E.NCODE));
            shelf = ActivityScenario.launch(new Intent(E2E.ctx(), ShelfActivity.class));
            E2E.waitUntil(() -> FullSave.PROGRESS.isEmpty() && kept(6).isFile(), "saved once in front");
            assertEquals(3, Net.asked.size());
        } finally {
            FullSave.ANY_TIME = true;
        }
    }

    /** F7: taking the work off the shelf during a save ends it, and nothing is kept for the work afterwards. */
    @Test
    public void removingTheWorkEndsASave() throws Exception {
        FullSave.GAP_MS = 1_500;
        FullSave.start(E2E.ctx(), E2E.NCODE);
        E2E.waitUntil(() -> { int[] p = FullSave.PROGRESS.get(E2E.NCODE); return p != null && p[1] == 6; }, "the save counted");
        Shelf.remove(E2E.ctx(), java.util.Collections.singleton(E2E.NCODE));
        E2E.waitUntil(FullSave.PROGRESS::isEmpty, "the save ended");
        android.os.SystemClock.sleep(2_000);
        assertFalse("the work's folder is gone and stays gone", new File(E2E.ctx().getFilesDir(), "episodes/" + E2E.NCODE).exists());
        assertTrue("at most the fetch in flight: " + Net.asked, Net.asked.size() <= 1);
    }

    /** F8: the long-press menu starts a save, the row shows its count, and the menu stops it. */
    @Test
    public void theMenuStartsShowsAndStopsASave() throws Exception {
        FullSave.GAP_MS = 2_500;
        shelf = ActivityScenario.launch(new Intent(E2E.ctx(), ShelfActivity.class));
        E2E.device().wait(Until.findObject(By.text(E2E.TITLE)), 5_000).longClick();
        E2E.device().wait(Until.findObject(By.text("全話を保存")), 5_000).click();
        E2E.device().wait(Until.findObject(By.text("保存する")), 5_000).click();
        assertNotNull("the row shows the count", E2E.device().wait(Until.findObject(By.textContains("全話を保存中 3 / 6")), 5_000));
        E2E.device().wait(Until.findObject(By.text(E2E.TITLE)), 5_000).longClick();
        E2E.device().wait(Until.findObject(By.text("全話の保存を止める")), 5_000).click();
        E2E.waitUntil(FullSave.PROGRESS::isEmpty, "the save ended");
        assertTrue("stopped before all: " + Net.asked, Net.asked.size() < 3);
    }

    /** F9: a カクヨム save whose turn comes with no screen shown does not even read the work's list. */
    @Test
    public void aKakuyomuSaveReadsNothingOutOfFront() throws Exception {
        KakuyomuTest.addAt(KakuyomuTest.A);
        Net.asked.clear();
        FullSave.ANY_TIME = false;
        try {
            FullSave.start(E2E.ctx(), KakuyomuTest.KEY);
            android.os.SystemClock.sleep(2_500);
            assertEquals("nothing asked with no screen shown: " + Net.asked, 0, Net.asked.size());
            FullSave.stop(KakuyomuTest.KEY);
            E2E.waitUntil(FullSave.PROGRESS::isEmpty, "the save ended");
            assertEquals(0, Net.asked.size());
        } finally {
            FullSave.ANY_TIME = true;
        }
    }

    /** F10: a カクヨム work taken off the shelf while its list is being read leaves no list or folder behind. */
    @Test
    public void aListReadDuringRemovalLeavesNothing() throws Exception {
        KakuyomuTest.addAt(KakuyomuTest.A);
        Net.asked.clear();                                                 // adding read the page once already
        String page = KakuyomuSite.workPage(KakuyomuTest.WORK);
        java.util.function.Function<String, byte[]> plain = Net.fake;
        Net.fake = u -> { if (u.equals(page)) android.os.SystemClock.sleep(2_000); return plain.apply(u); };
        FullSave.start(E2E.ctx(), KakuyomuTest.KEY);
        E2E.waitUntil(() -> Net.asked.contains(page), "the list asked for");
        Shelf.remove(E2E.ctx(), java.util.Collections.singleton(KakuyomuTest.KEY));
        E2E.waitUntil(FullSave.PROGRESS::isEmpty, "the save ended");
        assertFalse("no folder left for the removed work",
                new File(E2E.ctx().getFilesDir(), "episodes/" + KakuyomuTest.KEY).exists());
    }

    /** F11: a fetch waiting its turn behind another request is not sent once the save is stopped. */
    @Test
    public void aFetchWaitingItsTurnIsNotSentAfterStop() throws Exception {
        synchronized (Net.class) {                                         // another request holds the door
            FullSave.start(E2E.ctx(), E2E.NCODE);
            E2E.waitUntil(() -> { int[] p = FullSave.PROGRESS.get(E2E.NCODE); return p != null && p[1] == 6; }, "the save counted");
            android.os.SystemClock.sleep(800);                             // now waiting at the door
            FullSave.stop(E2E.NCODE);
        }
        E2E.waitUntil(FullSave.PROGRESS::isEmpty, "the save ended");
        assertEquals("nothing sent after 止める: " + Net.asked, 0, Net.asked.size());
    }

    /** F12: a fetch waiting its turn is not sent once its work is taken off the shelf. */
    @Test
    public void aFetchWaitingItsTurnIsNotSentAfterRemoval() throws Exception {
        synchronized (Net.class) {                                         // another request holds the door
            FullSave.start(E2E.ctx(), E2E.NCODE);
            E2E.waitUntil(() -> { int[] p = FullSave.PROGRESS.get(E2E.NCODE); return p != null && p[1] == 6; }, "the save counted");
            android.os.SystemClock.sleep(800);                             // now waiting at the door
            Shelf.remove(E2E.ctx(), java.util.Collections.singleton(E2E.NCODE));
        }
        E2E.waitUntil(FullSave.PROGRESS::isEmpty, "the save ended");
        assertEquals("nothing sent after removal: " + Net.asked, 0, Net.asked.size());
    }

    /** F3: 保存した話を消す from the long-press menu empties the cache and leaves the work and its place. */
    @Test
    public void clearingTheKeptEpisodesKeepsTheWork() throws Exception {
        Shelf.update(E2E.ctx(), s -> { Shelf.Work w = s.find(E2E.NCODE); w.lastEpisode = 2; w.lastPage = 3; });
        shelf = ActivityScenario.launch(new Intent(E2E.ctx(), ShelfActivity.class));
        UiObject2 row = E2E.device().wait(Until.findObject(By.text(E2E.TITLE)), 5_000);
        assertNotNull(row);
        row.longClick();
        E2E.device().wait(Until.findObject(By.text("保存した話を消す")), 5_000).click();
        E2E.device().wait(Until.findObject(By.text("消す")), 5_000).click();
        E2E.waitUntil(() -> !kept(1).isFile() && !kept(2).isFile() && !kept(3).isFile(), "the kept episodes gone");
        Shelf.Work w = Shelf.load(E2E.ctx()).find(E2E.NCODE);
        assertNotNull("still on the shelf", w);
        assertEquals(2, w.lastEpisode);
        assertEquals(3, w.lastPage);
        assertEquals(0, Net.asked.size());
    }
}
