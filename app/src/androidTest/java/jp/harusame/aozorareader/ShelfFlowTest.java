package jp.harusame.aozorareader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

/** docs/e2e.md R10, R17, S1, S4, P1: the 目次, sharing a page in, removing a work, and what read-ahead asks for. */
@RunWith(AndroidJUnit4.class)
public class ShelfFlowTest {
    static final String API = "https://api.syosetu.com/novelapi/api/?out=json&of=n-t-w-ga-nt-e&lim=500&ncode=" + E2E.NCODE;
    ActivityScenario<?> scenario;

    @Before
    public void setUp() {
        E2E.reset();
    }

    @After
    public void tearDown() {
        if (scenario != null) scenario.close();
    }

    /** The activity in front once something opened it (the reader, from the shelf). */
    static Activity resumed(Class<?> type) {
        long until = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < until) {
            AtomicReference<Activity> found = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Collection<Activity> as = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED);
                for (Activity a : as) if (type.isInstance(a)) found.set(a);
            });
            if (found.get() != null) return found.get();
            SystemClock.sleep(200);
        }
        throw new AssertionError(type.getSimpleName() + " never came to the front");
    }

    String tocPage(int... nos) {
        StringBuilder b = new StringBuilder("<div class=\"p-eplist\"><div class=\"p-eplist__chapter-title\">第一章</div>");
        for (int n : nos) b.append("<div class=\"p-eplist__sublist\"><a href=\"/").append(E2E.NCODE).append("/").append(n)
                .append("/\" class=\"p-eplist__subtitle\">第").append(n).append("話　試験</a></div>");
        return b.append("</div>").toString();
    }

    /** R10: the 目次 lists the work (read from its page once), and a jump opens that episode. */
    @Test
    public void theTocJumpsToAnEpisode() throws Exception {
        E2E.seedNarou(3, 20);
        E2E.net.put("https://ncode.syosetu.com/" + E2E.NCODE + "/", tocPage(1, 2, 3).getBytes(StandardCharsets.UTF_8));
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", Shelf.episodeUrl(E2E.NCODE, 1)).putExtra("page", 0));
        Activity a = resumed(ReaderActivity.class);
        E2E.waitReady(a);
        E2E.js(a, "document.getElementById('tocb').click()");
        E2E.waitFor(a, "document.querySelectorAll('#toc .ep').length", "3");
        assertEquals("\"第1話　試験\"", E2E.js(a, "document.querySelector('#toc .ep.cur').textContent"));
        E2E.js(a, "document.querySelector('#toc .ep[data-n=\"3\"]').click()");
        E2E.waitFor(a, "INFO.no", "3");
        assertEquals(Arrays.asList("https://ncode.syosetu.com/" + E2E.NCODE + "/"), Net.asked);
    }

    /** R17: the 目次 marks the episodes kept in the app and counts them. */
    @Test
    public void theTocMarksKeptEpisodes() throws Exception {
        E2E.seedNarou(3, 20);                                              // 1–3 kept; 4 is not
        Shelf.update(E2E.ctx(), s -> { Shelf.Work w = s.find(E2E.NCODE); w.episodes = 4; w.seen = 4; });
        E2E.net.put("https://ncode.syosetu.com/" + E2E.NCODE + "/", tocPage(1, 2, 3, 4).getBytes(StandardCharsets.UTF_8));
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", Shelf.episodeUrl(E2E.NCODE, 1)).putExtra("page", 0));
        Activity a = resumed(ReaderActivity.class);
        E2E.waitReady(a);
        E2E.js(a, "document.getElementById('tocb').click()");
        E2E.waitFor(a, "document.querySelectorAll('#toc .ep').length", "4");
        assertEquals("\"1,2,3\"", E2E.js(a, "Array.from(document.querySelectorAll('#toc .ep.kept')).map(function(e){return e.dataset.n}).join()"));
        assertEquals("\"保存済み 3 / 4 話\"", E2E.js(a, "document.querySelector('#toc .st').textContent"));
    }

    /** S1: a shared なろう episode URL adds the work and opens that episode; made again, it is not added twice. */
    @Test
    public void aSharedEpisodeIsAddedAndOpened() throws Exception {
        E2E.net.put(API, E2E.narouApi(3));
        String ep2 = "https://ncode.syosetu.com/" + E2E.NCODE + "/2/";
        E2E.net.put(ep2, E2E.narouEpisode(2, 3, "第2話　試験", 20, "").getBytes(StandardCharsets.UTF_8));
        // The shelf is singleTask (the launcher); it is started as another app's share would start it.
        E2E.ctx().startActivity(new Intent(Intent.ACTION_SEND).setClassName(E2E.ctx(), ShelfActivity.class.getName())
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "これ面白い " + ep2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Activity r = resumed(ReaderActivity.class);
        E2E.waitReady(r);
        assertEquals("2", E2E.js(r, "INFO.no"));
        Shelf.Work w = E2E.work();
        assertNotNull(w);
        assertEquals(E2E.TITLE, w.title);
        assertEquals(3, w.episodes);
        assertEquals("the API once, then the episode", Arrays.asList(API, ep2), Net.asked.subList(0, 2));
        // The place moves on, then the shelf is made again with its old share intent.
        E2E.js(r, "__reader.go(1)");
        SystemClock.sleep(500);
        r.finish();
        Activity s = resumed(ShelfActivity.class);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(s::recreate);
        SystemClock.sleep(1500);
        assertEquals(1, E2E.work().lastPage);
        assertEquals(1, Shelf.load(E2E.ctx()).works.size());
    }

    /** S4: removing a work takes everything kept for it. */
    @Test
    public void removingAWorkDeletesWhatIsKept() throws Exception {
        E2E.seedNarou(2, 5);
        File dir = new File(E2E.ctx().getFilesDir(), "episodes/" + E2E.NCODE);
        assertTrue(dir.isDirectory());
        Shelf.remove(E2E.ctx(), java.util.Collections.singleton(E2E.NCODE));
        assertNull(E2E.work());
        assertFalse(dir.exists());
    }

    /** P1: read-ahead asks for the next episodes only, one after another, and nothing for a work not on the shelf. */
    @Test
    public void readAheadAsksForTheNextEpisodesOnly() throws Exception {
        E2E.seedNarou(5, 10);
        for (int n = 2; n <= 5; n++) {
            new File(E2E.ctx().getFilesDir(), "episodes/" + E2E.NCODE + "/" + n + ".html").delete();
            E2E.net.put("https://ncode.syosetu.com/" + E2E.NCODE + "/" + n + "/",
                    E2E.narouEpisode(n, 5, "第" + n + "話", 10, "").getBytes(StandardCharsets.UTF_8));
        }
        E2E.ctx().getSharedPreferences("reader", 0).edit().putString("display", "{\"ahead\":3,\"guided\":true}").commit();
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", Shelf.episodeUrl(E2E.NCODE, 1)).putExtra("page", 0));
        Activity a = resumed(ReaderActivity.class);
        E2E.waitReady(a);
        long t0 = SystemClock.uptimeMillis();
        while (Net.asked.size() < 3 && SystemClock.uptimeMillis() - t0 < 30_000) SystemClock.sleep(250);
        long took = SystemClock.uptimeMillis() - t0;
        SystemClock.sleep(6000);                                         // nothing more comes
        assertEquals(Arrays.asList("https://ncode.syosetu.com/" + E2E.NCODE + "/2/", "https://ncode.syosetu.com/" + E2E.NCODE + "/3/",
                "https://ncode.syosetu.com/" + E2E.NCODE + "/4/"), Net.asked);
        assertTrue("spaced out: three in " + took + " ms", took >= 9_000);
        // Off the shelf: nothing is fetched for it.
        Shelf.remove(E2E.ctx(), java.util.Collections.singleton(E2E.NCODE));
        Net.asked.clear();
        E2E.js(a, "__reader.go(__reader.pages()-1)");
        SystemClock.sleep(300);
        E2E.tapLeft();
        SystemClock.sleep(2000);
        assertTrue("no request after removal: " + Net.asked, Net.asked.isEmpty());
    }

    /** R8b: after a jump from the 目次 reloads the page, Back closes the reader (nothing is open any more). */
    @Test
    public void backWorksAfterAJumpReloadsThePage() throws Exception {
        E2E.seedNarou(3, 20);
        E2E.net.put("https://ncode.syosetu.com/" + E2E.NCODE + "/", tocPage(1, 2, 3).getBytes(StandardCharsets.UTF_8));
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", Shelf.episodeUrl(E2E.NCODE, 1)).putExtra("page", 0));
        Activity a = resumed(ReaderActivity.class);
        E2E.waitReady(a);
        E2E.js(a, "document.getElementById('tocb').click()");
        E2E.waitFor(a, "document.querySelectorAll('#toc .ep').length", "3");
        E2E.js(a, "document.querySelector('#toc .ep[data-n=\"3\"]').click()");   // not prepared: a full reload
        E2E.waitFor(a, "INFO.no", "3");
        SystemClock.sleep(500);
        E2E.device().pressBack();
        E2E.waitUntil(a::isFinishing, "Back closed the reader");
    }

    /** T1: the read log, opened from the shelf's ⋮, has no tabs and its back arrow returns to the shelf. */
    @Test
    public void theBackArrowLeavesTheReadLog() throws Exception {
        E2E.seedNarou(1, 5);
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), ShelfActivity.class));
        resumed(ShelfActivity.class);
        E2E.device().wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.desc("その他")), 5000).click();
        E2E.device().wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("読書の記録")), 5000).click();
        Activity log = resumed(LogActivity.class);
        assertNull("no tab bar on the read log", E2E.device().findObject(androidx.test.uiautomator.By.text("探す")));
        androidx.test.uiautomator.UiObject2 back = E2E.device().wait(
                androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.desc("戻る")), 5000);
        assertNotNull("the read log has a back arrow", back);
        back.click();
        E2E.waitUntil(log::isFinishing, "the back arrow closed the read log");
        resumed(ShelfActivity.class);
    }
}
