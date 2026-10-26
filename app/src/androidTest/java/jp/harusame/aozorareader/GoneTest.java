package jp.harusame.aozorareader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** docs/e2e.md D1–D5: the two builds — a work gone from its site, and 全話を保存 in the menu. */
@RunWith(AndroidJUnit4.class)
public class GoneTest {
    static final String API = "https://api.syosetu.com/novelapi/api/?out=json&of=n-t-w-ga-nt-e&lim=500&ncode=" + E2E.NCODE;
    ActivityScenario<ShelfActivity> shelf;

    @Before
    public void setUp() throws Exception {
        E2E.reset();
        E2E.seedNarou(3, 5);
        Shelf.update(E2E.ctx(), s -> { Shelf.Work w = s.find(E2E.NCODE); w.lastEpisode = 2; w.lastPage = 3; });
    }

    @After
    public void tearDown() {
        if (shelf != null) shelf.close();
    }

    static File kept(int n) {
        return new File(E2E.ctx().getFilesDir(), "episodes/" + E2E.NCODE + "/" + n + ".html");
    }

    static java.util.List<Shelf.Work> check() throws Exception {
        Shelf s = Shelf.load(E2E.ctx());
        return s.refresh(new java.util.ArrayList<>(s.works), E2E.ctx());
    }

    /** As if the next check came a day and a minute after the last one. */
    static void aDayLater() throws Exception {
        long d = Shelf.GONE_CONFIRM_MS + 60_000;
        Shelf.update(E2E.ctx(), s -> { Shelf.Work w = s.find(E2E.NCODE); if (w.missingSince > 0) w.missingSince -= d; if (w.pageCheckedAt > 0) w.pageCheckedAt -= d; });
    }

    static Shelf.Work work() {
        return Shelf.load(E2E.ctx()).find(E2E.NCODE);
    }

    /**
     * D1: a work the API no longer has is marked at the first check and keeps its text; at a check a day later
     * the store build drops the text (the work and its place stay), the personal build keeps it.
     */
    @Test
    public void aWorkGoneADayLosesItsTextOnlyInTheStoreBuild() throws Exception {
        E2E.net.put(API, "[{\"allcount\":0}]".getBytes(StandardCharsets.UTF_8));   // and its page answers 404 (no fixture)
        check();
        assertTrue("marked at the first check", work().missingSince > 0);
        assertTrue("kept after one answer", kept(1).isFile() && kept(3).isFile());
        aDayLater();
        check();
        Shelf.Work w = work();
        assertNotNull("still on the shelf", w);
        assertEquals(2, w.lastEpisode);
        assertEquals(3, w.lastPage);
        boolean any = kept(1).isFile() || kept(2).isFile() || kept(3).isFile();
        assertEquals(BuildConfig.DROP_GONE ? "dropped in the store build" : "kept in the personal build", !BuildConfig.DROP_GONE, any);
    }

    /**
     * D4: a なろう work the API leaves out but whose page is still there (taken out of search by its author)
     * keeps its text and is not marked in the store build; its page is asked at most once a day.
     */
    @Test
    public void aWorkOutOfSearchButStillOnItsPageKeepsItsText() throws Exception {
        String page = "https://ncode.syosetu.com/" + E2E.NCODE + "/";
        E2E.net.put(API, "[{\"allcount\":0}]".getBytes(StandardCharsets.UTF_8));
        E2E.net.put(page, "<html><body><div class=\"p-eplist\"></div></body></html>".getBytes(StandardCharsets.UTF_8));
        java.util.List<Shelf.Work> reported = check();
        check();
        assertTrue("kept", kept(1).isFile() && kept(2).isFile() && kept(3).isFile());
        if (BuildConfig.DROP_GONE) assertTrue("not reported as gone: " + reported.size(), reported.isEmpty());
        long asked = Net.asked.stream().filter(page::equals).count();
        if (BuildConfig.DROP_GONE) {
            assertEquals("not marked: its page is there", 0, work().missingSince);
            assertEquals("the page asked once in the day", 1, asked);
        } else {
            assertEquals("the personal build never asks the page", 0, asked);
        }
    }

    /**
     * D5 (store build): one 404 from the page only marks the work; the text goes when the page still says so
     * a day later. (A work out of search that is then withdrawn gets the same day.)
     */
    @Test
    public void onePageAnswerOnlyMarksTheWork() throws Exception {
        Assume.assumeTrue(BuildConfig.DROP_GONE);
        String page = "https://ncode.syosetu.com/" + E2E.NCODE + "/";
        E2E.net.put(API, "[{\"allcount\":0}]".getBytes(StandardCharsets.UTF_8));
        E2E.net.put(page, "<html><body><div class=\"p-eplist\"></div></body></html>".getBytes(StandardCharsets.UTF_8));
        check();                                                            // out of search: there
        E2E.net.remove(page);                                              // now the page answers 404
        aDayLater();
        assertEquals("reported once the page says gone", 1, check().size());
        assertTrue("marked by the page's first 404", work().missingSince > 0);
        assertTrue("kept after one page answer", kept(1).isFile() && kept(3).isFile());
        aDayLater();
        check();
        assertTrue("dropped on the second, a day later", !kept(1).isFile() && !kept(2).isFile() && !kept(3).isFile());
        assertNotNull(work());
        // Dropped: the page is not asked again, a day later or at any later check.
        Net.asked.clear();
        aDayLater();
        check();
        check();
        assertTrue("the page not asked after the drop: " + Net.asked, !Net.asked.contains(page));
    }

    /** D2: a work found again at a later check is no longer marked, so a later miss starts over. */
    @Test
    public void aWorkFoundAgainIsUnmarked() throws Exception {
        E2E.net.put(API, "[{\"allcount\":0}]".getBytes(StandardCharsets.UTF_8));
        check();
        assertTrue(work().missingSince > 0);
        E2E.net.put(API, E2E.narouApi(3));
        check();
        assertEquals(0, work().missingSince);
        assertTrue(kept(1).isFile());
    }

    /** D3: the long-press menu has 全話を保存 in the personal build only; 保存した話を消す in both. */
    @Test
    public void theMenuOffersFullSaveOnlyInThePersonalBuild() throws Exception {
        shelf = ActivityScenario.launch(new Intent(E2E.ctx(), ShelfActivity.class));
        E2E.device().wait(Until.findObject(By.text(E2E.TITLE)), 5_000).longClick();
        assertNotNull(E2E.device().wait(Until.findObject(By.text("保存した話を消す")), 5_000));
        if (BuildConfig.FULL_SAVE) assertNotNull(E2E.device().findObject(By.text("全話を保存")));
        else assertNull(E2E.device().findObject(By.text("全話を保存")));
        E2E.device().pressBack();
    }
}
