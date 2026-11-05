package jp.harusame.aozorareader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** docs/e2e.md W1–W6: 読みさし, from the reader's bar and over the shelf. */
@RunWith(AndroidJUnit4.class)
public class YomisashiTest {
    static final String OTHER = "n9998zz";
    ActivityScenario<?> scenario;

    @Before
    public void setUp() {
        E2E.reset();
    }

    @After
    public void tearDown() {
        if (scenario != null) scenario.close();
    }

    /** A なろう work on the shelf with its episodes kept, read last at {@code read}, left at episode/page. */
    static void seed(String ncode, String title, int episodes, int episode, int page, int pages, long read) throws Exception {
        Context c = E2E.ctx();
        File dir = new File(c.getFilesDir(), "episodes/" + ncode);
        dir.mkdirs();
        for (int n = 1; n <= episodes; n++)
            Files.write(new File(dir, n + ".html").toPath(),
                    E2E.narouEpisode(n, episodes, "第" + n + "話　試験", 40, "").getBytes(StandardCharsets.UTF_8));
        Shelf.update(c, s -> {
            Shelf.Work w = new Shelf.Work();
            w.ncode = ncode;
            w.title = title;
            w.author = E2E.AUTHOR;
            w.episodes = episodes;
            w.seen = episodes;
            w.lastEpisode = episode;
            w.lastPage = page;
            w.lastPages = pages;
            w.lastRead = read;
            w.added = read;
            s.works.add(w);
            s.checked = System.currentTimeMillis();
        });
    }

    static JSONObject item(String key) throws Exception {
        JSONArray a = Yomisashi.json(E2E.ctx(), null).getJSONArray("items");
        for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).getString("key").equals(key)) return a.getJSONObject(i);
        return null;
    }

    /** W1: newest first, twenty at most; a work taken out stays on the shelf and comes back when read again. */
    @Test
    public void newestFirstTwentyAtMostAndTakenOutUntilReadAgain() throws Exception {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 22; i++) seed(String.format("n%04dzy", i), "作品" + i, 1, 1, 0, 0, now - 22_000 + i * 1000L);
        seed("n9997zz", "読んでいない作品", 1, 0, 0, 0, 0);
        List<Shelf.Work> ws = Yomisashi.list(E2E.ctx());
        assertEquals(20, ws.size());
        assertEquals("n0021zy", ws.get(0).ncode);
        assertEquals("n0002zy", ws.get(19).ncode);
        for (Shelf.Work w : ws) assertFalse("never read, never in 読みさし", w.ncode.equals("n9997zz"));
        Yomisashi.drop(E2E.ctx(), "n0021zy");
        assertEquals("n0020zy", Yomisashi.list(E2E.ctx()).get(0).ncode);
        assertNotNull("taken out of 読みさし only", Shelf.load(E2E.ctx()).find("n0021zy"));
        Shelf.update(E2E.ctx(), s -> s.find("n0021zy").lastRead = System.currentTimeMillis() + 1000);
        assertEquals("read again, it is back", "n0021zy", Yomisashi.list(E2E.ctx()).get(0).ncode);
    }

    /**
     * W2: each card shows a few lines where its work was left, from the kept text (no ruby readings); past the
     * last page of an episode that is no longer the newest, the work opens at the next one, and its card says so.
     * カクヨム's text is read from its kept page by the episode's id.
     */
    @Test
    public void aCardShowsTheTextWhereItOpens() throws Exception {
        seed(E2E.NCODE, E2E.TITLE, 3, 2, 0, 10, System.currentTimeMillis());
        JSONObject it = item(E2E.NCODE);
        assertTrue(it.getString("text"), it.getString("text").startsWith("第2話の1段落。夜の駅に電車が着いて、ホームの灯りが窓に映った。"));
        assertEquals("2 / 3 話", it.getString("sub"));
        assertFalse(it.getBoolean("fresh"));
        Shelf.update(E2E.ctx(), s -> s.find(E2E.NCODE).lastPage = 9);
        it = item(E2E.NCODE);
        assertTrue(it.getString("text"), it.getString("text").startsWith("第3話の1段落。"));
        assertEquals("2話を読み終え", it.getString("sub"));
        assertTrue(it.getBoolean("fresh"));
        Object[] at = Yomisashi.start(Shelf.load(E2E.ctx()).find(E2E.NCODE));
        assertEquals(Shelf.episodeUrl(E2E.NCODE, 3), at[0]);
        assertEquals(0, at[1]);
        // カクヨム: from its page's text as kept, by the episode's id; the title above it is not the text.
        String kk = Shelf.KK + KakuyomuTest.WORK;
        File dir = new File(E2E.ctx().getFilesDir(), "episodes/" + kk);
        dir.mkdirs();
        Files.write(new File(dir, "k" + KakuyomuTest.A + ".html").toPath(), KakuyomuTest.episodePage("A").getBytes(StandardCharsets.UTF_8));
        Shelf.update(E2E.ctx(), s -> {
            Shelf.Work w = new Shelf.Work();
            w.ncode = kk;
            w.title = "試験のカクヨム";
            w.episodes = 3;
            w.seen = 3;
            w.lastEpisode = 1;
            w.lastEpisodeId = KakuyomuTest.A;
            w.lastRead = System.currentTimeMillis() + 1000;
            s.works.add(w);
        });
        it = item(kk);
        assertTrue(it.getString("text"), it.getString("text").startsWith("本文Aの1段落。"));
        assertEquals(Net.asked.toString(), 0, Net.asked.size());
    }

    Activity reader(String ncode, int episode, int page) throws Exception {
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", Shelf.episodeUrl(ncode, episode)).putExtra("page", page));
        Activity a = ShelfFlowTest.resumed(ReaderActivity.class);
        E2E.waitReady(a);
        return a;
    }

    /** The bars, then 読みさし from its icon, with real taps. */
    static void openFromBar(Activity a) throws Exception {
        E2E.tapMiddle();
        E2E.waitFor(a, "document.getElementById('info').classList.contains('on')", "true");
        SystemClock.sleep(600);                       // the bars take no tap for half a second after they come up
        E2E.tapIn(((ReaderActivity) a).web, "#ysb");
        E2E.waitFor(a, "Yomisashi.isOpen()&&document.querySelectorAll('#ys .cd').length>0", "true");
    }

    /**
     * W3: from the reader, a real tap on another work's card opens it where it was left, and the place in the
     * work left is not moved. The tap once reached the page beneath too: a card on the right turned it a page
     * back, so every switch lost a page.
     */
    @Test
    public void aCardSwitchesWorksAndLeavesThePlaceAlone() throws Exception {
        long now = System.currentTimeMillis();
        seed(E2E.NCODE, E2E.TITLE, 3, 1, 2, 0, now - 1000);
        seed(OTHER, "もう一つの作品", 3, 2, 1, 0, now - 2000);
        Activity a = reader(E2E.NCODE, 1, 2);
        assertEquals(2, E2E.num(a, "__reader.cur()"));
        openFromBar(a);
        // The work being read is first (on the left), the other on the right, under the right-hand page turn.
        assertEquals("\"" + E2E.NCODE + "\"", E2E.js(a, "document.querySelector('#ys .cd').dataset.key"));
        E2E.tapIn(((ReaderActivity) a).web, "#ys .cd[data-key=\"" + OTHER + "\"] .ti");
        E2E.waitUntil(() -> a.isFinishing() || a.isDestroyed(), "the reader of the work left closes");
        Activity b = ShelfFlowTest.resumed(ReaderActivity.class);
        E2E.waitReady(b);
        assertTrue(E2E.js(b, "INFO.url"), E2E.js(b, "INFO.url").contains(OTHER + "/2/"));
        assertEquals(1, E2E.num(b, "__reader.cur()"));
        assertEquals("the place in the work left", 2, Shelf.load(E2E.ctx()).find(E2E.NCODE).lastPage);
    }

    /** W4: a tap on the work being read only closes 読みさし; so do 閉じる and Back, and none turns the page. */
    @Test
    public void theCurrentCardCloseAndBackOnlyClose() throws Exception {
        long now = System.currentTimeMillis();
        seed(E2E.NCODE, E2E.TITLE, 3, 1, 2, 0, now - 1000);
        seed(OTHER, "もう一つの作品", 3, 2, 1, 0, now - 2000);
        Activity a = reader(E2E.NCODE, 1, 2);
        WebView w = ((ReaderActivity) a).web;
        openFromBar(a);
        E2E.tapIn(w, "#ys .cd.cur .ti");
        E2E.waitFor(a, "Yomisashi.isOpen()", "false");
        openFromBar(a);
        E2E.tapIn(w, "#ys .sh [data-ys=\"close\"]");
        E2E.waitFor(a, "Yomisashi.isOpen()", "false");
        openFromBar(a);
        E2E.device().pressBack();
        E2E.waitFor(a, "Yomisashi.isOpen()", "false");
        assertFalse("Back closed 読みさし, not the reader", a.isFinishing());
        assertEquals(2, E2E.num(a, "__reader.cur()"));
    }

    /** W5: pulled down from its top past a third of its height the sheet closes; a short pull slides back. */
    @Test
    public void aPullDownClosesTheSheet() throws Exception {
        seed(E2E.NCODE, E2E.TITLE, 3, 1, 2, 0, System.currentTimeMillis());
        Activity a = reader(E2E.NCODE, 1, 2);
        WebView w = ((ReaderActivity) a).web;
        openFromBar(a);
        int[] top = E2E.onScreen(w, "#ys .sh .hd .t");
        assertNotNull(top);
        UiDevice d = E2E.device();
        d.swipe(top[0], top[1], top[0], top[1] + 60, 20);
        SystemClock.sleep(500);
        assertEquals("a short pull slides back", "true", E2E.js(a, "Yomisashi.isOpen()"));
        d.swipe(top[0], top[1], top[0], top[1] + d.getDisplayHeight() / 2, 30);
        E2E.waitFor(a, "Yomisashi.isOpen()", "false");
        assertEquals(2, E2E.num(a, "__reader.cur()"));
    }

    /**
     * W6: over the shelf, the toolbar's icon shows the same sheet at once (loaded with the shelf); Back hides it
     * and leaves the shelf; a card opens its work where it was left.
     */
    @Test
    public void overTheShelfTheIconShowsItAndACardOpensTheWork() throws Exception {
        long now = System.currentTimeMillis();
        seed(E2E.NCODE, E2E.TITLE, 3, 1, 2, 0, now - 1000);
        seed(OTHER, "もう一つの作品", 3, 2, 1, 0, now - 2000);
        scenario = ActivityScenario.launch(ShelfActivity.class);
        ShelfActivity s = (ShelfActivity) ShelfFlowTest.resumed(ShelfActivity.class);
        UiDevice d = E2E.device();
        UiObject2 icon = d.wait(Until.findObject(By.desc("読みさし")), 10_000);
        assertNotNull("the toolbar's icon", icon);
        WebView w = s.yomisashi.web();
        E2E.waitUntil(() -> "true".equals(E2E.js(w, "typeof Yomisashi==='object'")), "the page is loaded");
        icon.click();
        E2E.waitUntil(() -> "2".equals(E2E.js(w, "document.querySelectorAll('#ys .cd').length")), "two cards shown");
        assertEquals("no work is being read here", "0", E2E.js(w, "document.querySelectorAll('#ys .cd.cur').length"));
        d.pressBack();
        E2E.waitUntil(() -> !s.yomisashi.shown(), "Back hides 読みさし");
        assertFalse("and leaves the shelf", s.isFinishing());
        icon = d.wait(Until.findObject(By.desc("読みさし")), 10_000);
        icon.click();
        E2E.waitUntil(() -> "2".equals(E2E.js(w, "document.querySelectorAll('#ys .cd').length")), "shown again");
        E2E.tapIn(w, "#ys .cd[data-key=\"" + OTHER + "\"] .ti");
        Activity b = ShelfFlowTest.resumed(ReaderActivity.class);
        E2E.waitReady(b);
        assertTrue(E2E.js(b, "INFO.url"), E2E.js(b, "INFO.url").contains(OTHER + "/2/"));
        assertEquals(1, E2E.num(b, "__reader.cur()"));
        AtomicReference<Boolean> shown = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> shown.set(s.yomisashi.shown()));
        assertFalse("gone from over the shelf", shown.get());
    }
}
