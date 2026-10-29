package jp.harusame.aozorareader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/** docs/e2e.md R1–R7: reading a なろう work from the shelf. No real site is reached. */
@RunWith(AndroidJUnit4.class)
public class ReadingTest {
    ActivityScenario<ReaderActivity> reader;

    @Before
    public void setUp() throws Exception {
        E2E.reset();
        E2E.seedNarou(3, 40);
    }

    @After
    public void tearDown() {
        if (reader != null) reader.close();
    }

    Activity open(int episode, int page) {
        Intent i = new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", Shelf.episodeUrl(E2E.NCODE, episode)).putExtra("page", page);
        reader = ActivityScenario.launch(i);
        AtomicReference<Activity> a = new AtomicReference<>();
        reader.onActivity(a::set);
        return a.get();
    }

    /** R1: the episode opens from the cache, credited, without a request. */
    @Test
    public void opensFromTheCacheWithTheAuthorShown() throws Exception {
        Activity a = open(1, 0);
        E2E.waitReady(a);
        assertEquals("\"作者：" + E2E.AUTHOR + "\"", E2E.js(a, "document.getElementById('credit').textContent"));
        assertTrue(E2E.num(a, "__reader.pages()") > 1);
        assertTrue("nothing fetched: " + Net.asked, Net.asked.isEmpty());
    }

    /** R2: taps turn pages both ways, and the place is saved. */
    @Test
    public void tapsTurnPagesAndThePlaceIsSaved() throws Exception {
        Activity a = open(1, 0);
        E2E.waitReady(a);
        E2E.tapLeft();
        E2E.waitFor(a, "__reader.cur()", "1");
        E2E.tapLeft();
        E2E.waitFor(a, "__reader.cur()", "2");
        E2E.tapRight();
        E2E.waitFor(a, "__reader.cur()", "1");
        E2E.waitUntil(() -> E2E.work().lastPage == 1, "the place saved");     // written on the saver thread
        assertEquals(1, E2E.work().lastEpisode);
    }

    /** R3/R4: past the last page the next episode comes in; before the first, the previous one at its end. */
    @Test
    public void turningPastTheEndsCrossesEpisodes() throws Exception {
        Activity a = open(1, 0);
        E2E.waitReady(a);
        E2E.js(a, "__reader.go(__reader.pages()-1)");
        SystemClock.sleep(6500);                                         // the next episode is prepared 5 s after opening
        E2E.tapLeft();
        E2E.waitFor(a, "INFO.no", "2");
        E2E.waitFor(a, "__reader.cur()", "0");
        E2E.tapRight();
        E2E.waitFor(a, "INFO.no", "1");
        assertEquals(E2E.num(a, "__reader.pages()-1"), E2E.num(a, "__reader.cur()"));
        assertTrue("all kept, nothing fetched: " + Net.asked, Net.asked.isEmpty());
    }

    /** R5: after the last page of the last episode, the end card. */
    @Test
    public void theEndCardAfterTheLastEpisode() throws Exception {
        E2E.net.put("https://api.syosetu.com/novelapi/api/?out=json&of=n-t-w-ga-nt-e&lim=500&ncode=" + E2E.NCODE, E2E.narouApi(3));
        Activity a = open(3, 0);
        E2E.waitReady(a);
        E2E.js(a, "__reader.go(__reader.pages()-1)");
        E2E.tapLeft();
        E2E.waitFor(a, "document.getElementById('end').classList.contains('on')", "true");
        E2E.waitFor(a, "document.querySelector('#end .t').textContent", "\"最新話まで読みました\"");
    }

    /** R6: the reader made again (a system setting, or the process back) stays where it was. */
    @Test
    public void recreatingKeepsThePlace() throws Exception {
        Activity a = open(1, 0);
        E2E.waitReady(a);
        E2E.js(a, "__reader.go(__reader.pages()-1)");
        SystemClock.sleep(6500);
        E2E.tapLeft();
        E2E.waitFor(a, "INFO.no", "2");
        E2E.tapLeft();
        E2E.waitFor(a, "__reader.cur()", "1");
        SystemClock.sleep(300);
        reader.recreate();
        AtomicReference<Activity> b = new AtomicReference<>();
        reader.onActivity(b::set);
        E2E.waitReady(b.get());
        assertEquals("2", E2E.js(b.get(), "INFO.no"));
        assertEquals(1, E2E.num(b.get(), "__reader.cur()"));
    }

    /** R7: an author's literal markup is text, not a script that a tap would run. */
    @Test
    public void anAuthorsMarkupDoesNotRun() throws Exception {
        String evil = "<p id=\"L0\">&lt;a href=\"http.x\" style=\"position:fixed;inset:0;z-index:99\" onclick=\"window.__ran=1\"&gt;押して&lt;/a&gt;</p>";
        java.nio.file.Files.write(new java.io.File(E2E.ctx().getFilesDir(), "episodes/" + E2E.NCODE + "/1.html").toPath(),
                E2E.narouEpisode(1, 3, "第1話　試験", 10, evil).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Activity a = open(1, 0);
        E2E.waitReady(a);
        assertEquals("0", E2E.js(a, "document.querySelectorAll('#book a,[onclick]').length"));
        E2E.tapLeft();
        SystemClock.sleep(500);
        assertEquals("null", E2E.js(a, "window.__ran===undefined?null:window.__ran"));
        assertFalse(E2E.js(a, "document.body.innerHTML").contains("onclick"));
    }

    /** R8: Back closes the bars first, and closes the reader only when nothing is open. */
    @Test
    public void backClosesTheBarsBeforeTheReader() throws Exception {
        Activity a = open(1, 0);
        E2E.waitReady(a);
        E2E.tapMiddle();
        E2E.waitFor(a, "document.getElementById('info').classList.contains('on')", "true");
        SystemClock.sleep(300);
        E2E.device().pressBack();
        E2E.waitFor(a, "document.getElementById('info').classList.contains('on')", "false");
        assertFalse("the reader is still open", a.isFinishing());
    }

    /** R9: an episode that cannot be read, opened first, still leaves the 目次 and the previous episode. */
    @Test
    public void aDeadFirstEpisodeLeavesAWayOut() throws Exception {
        new java.io.File(E2E.ctx().getFilesDir(), "episodes/" + E2E.NCODE + "/2.html").delete();   // not kept, and the "site" has nothing
        Activity a = open(2, 0);
        E2E.waitFor(a, "!!document.querySelector('#book p')&&document.querySelector('#book').textContent.indexOf('読み込めませんでした')>=0", "true");
        E2E.waitFor(a, "getComputedStyle(document.getElementById('tocb')).display!=='none'", "true");
        E2E.tapRight();                                                  // back to episode 1, at its end
        E2E.waitFor(a, "INFO.no", "1");
        assertEquals(1, Net.asked.size());                               // the one failed fetch of episode 2
    }

    /** R13: opening part-way saves no page 0 and logs no page read until one is turned. */
    @Test
    public void openingPartWayReadsNothingYet() throws Exception {
        java.util.List<Integer> reports = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        ReaderActivity.reported = reports;
        Activity a;
        try {
            a = open(1, 2);
            E2E.waitReady(a);
        } finally {
            ReaderActivity.reported = null;
        }
        assertEquals("the first place reported is the saved one, never 0: " + reports, Integer.valueOf(2), reports.get(0));
        assertTrue("0 never reported: " + reports, !reports.contains(0));
        assertEquals(2, E2E.num(a, "__reader.cur()"));
        SystemClock.sleep(800);
        assertEquals("the place stays at page 2", 2, E2E.work().lastPage);
        java.util.List<ReadLog.Entry> today = ReadLog.day(E2E.ctx().getFilesDir(), java.time.LocalDate.now());
        assertTrue("no page counted yet: " + (today.isEmpty() ? 0 : today.get(0).pages), today.isEmpty() || today.get(0).pages == 0);
        E2E.tapLeft();
        E2E.waitFor(a, "__reader.cur()", "3");
        E2E.waitUntil(() -> {
            java.util.List<ReadLog.Entry> t = ReadLog.day(E2E.ctx().getFilesDir(), java.time.LocalDate.now());
            return !t.isEmpty() && t.get(0).pages == 1;
        }, "one page counted after one turn");
    }

    /** R14: 字下げ 付ける indents a paragraph written flush, at once and in the episodes opened after. */
    @Test
    public void theIndentSettingIndentsFlushParagraphs() throws Exception {
        String flush = "<p id=\"L0\">字下げなしで始まる段落。</p>";
        java.nio.file.Files.write(new java.io.File(E2E.ctx().getFilesDir(), "episodes/" + E2E.NCODE + "/1.html").toPath(),
                E2E.narouEpisode(1, 3, "第1話　試験", 10, flush).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String has = "document.getElementById('book').textContent.indexOf('　字下げなしで始まる')>=0";
        Activity a = open(1, 0);
        E2E.waitReady(a);
        assertEquals("written flush by default", "false", E2E.js(a, has));
        E2E.js(a, "document.querySelector('#info .ik[data-indent=\"1\"]').click()");
        E2E.waitFor(a, has, "true");
        reader.recreate();
        AtomicReference<Activity> b = new AtomicReference<>();
        reader.onActivity(b::set);
        E2E.waitReady(b.get());
        assertEquals("kept across a new reader", "true", E2E.js(b.get(), has));
        assertEquals(0, Net.asked.size());
    }

    /**
     * R14b: 英字 すべて縦向き stands an English phrase upright (full-width), at once and after reopening; a short word is
     * upright either way. On the device only: Android's \w matches kanji and kana, which once made the whole paragraph
     * "English" and left every letter half-width (core's unit tests run on a JVM, where \w is ASCII).
     */
    @Test
    public void theUprightSettingStandsEnglishUpright() throws Exception {
        String text = "<p id=\"L0\">帰還のKing FisherとPKの話。</p>";
        java.nio.file.Files.write(new java.io.File(E2E.ctx().getFilesDir(), "episodes/" + E2E.NCODE + "/1.html").toPath(),
                E2E.narouEpisode(1, 3, "第1話　試験", 10, text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String book = "document.getElementById('book').textContent";
        Activity a = open(1, 0);
        E2E.waitReady(a);
        assertEquals("narou.rb's rule by default", "true", E2E.js(a, book + ".indexOf('帰還のKing FisherとＰＫの話')>=0"));
        E2E.js(a, "document.querySelector('#info .uk[data-upright=\"1\"]').click()");
        E2E.waitFor(a, book + ".indexOf('帰還のＫｉｎｇ ＦｉｓｈｅｒとＰＫの話')>=0", "true");
        reader.recreate();
        AtomicReference<Activity> b = new AtomicReference<>();
        reader.onActivity(b::set);
        E2E.waitReady(b.get());
        assertEquals("kept across a new reader", "true", E2E.js(b.get(), book + ".indexOf('帰還のＫｉｎｇ ＦｉｓｈｅｒとＰＫの話')>=0"));
        assertEquals(0, Net.asked.size());
    }

    /** R15: 行間 and 余白 in Aa change the layout at once, keep the share read, and stay for the next reader. */
    @Test
    public void lineHeightAndMarginsChangeTheLayout() throws Exception {
        Activity a = open(1, 0);
        E2E.waitReady(a);
        assertEquals("2", E2E.js(a, "getComputedStyle(document.getElementById('book')).lineHeight.replace('px','')/parseFloat(getComputedStyle(document.getElementById('book')).fontSize)"));
        assertEquals("22", E2E.js(a, "parseInt(document.getElementById('book').style.left)"));
        assertEquals("36", E2E.js(a, "parseInt(document.getElementById('book').style.top)"));
        int before = E2E.num(a, "__reader.pages()");
        E2E.js(a, "__reader.go(Math.round((__reader.pages()-1)/2))");
        E2E.js(a, "document.querySelector('#info .lh[data-lh=\"1.6\"]').click()");
        E2E.js(a, "document.querySelector('#info .mx[data-mx=\"s\"]').click()");
        E2E.js(a, "document.querySelector('#info .my[data-my=\"s\"]').click()");
        assertEquals("12", E2E.js(a, "parseInt(document.getElementById('book').style.left)"));
        assertEquals("28", E2E.js(a, "parseInt(document.getElementById('book').style.top)"));
        int after = E2E.num(a, "__reader.pages()");
        assertTrue("tighter lines and smaller margins hold more: " + after + " < " + before, after < before);
        int cur = E2E.num(a, "__reader.cur()");
        assertTrue("about half way still: " + cur + " of " + after, Math.abs(cur - (after - 1) / 2.0) <= 1);
        SystemClock.sleep(300);
        reader.recreate();
        AtomicReference<Activity> b = new AtomicReference<>();
        reader.onActivity(b::set);
        E2E.waitReady(b.get());
        assertEquals("12", E2E.js(b.get(), "parseInt(document.getElementById('book').style.left)"));
        assertEquals("28", E2E.js(b.get(), "parseInt(document.getElementById('book').style.top)"));
        assertEquals("1.6", E2E.js(b.get(), "Number(getComputedStyle(document.documentElement).getPropertyValue('--lh'))"));
    }

    /** R16: a 青空文庫 book hides 空行 and 字下げ (they format web novels only); a なろう episode shows them. */
    @Test
    public void aozoraBooksHideTheWebFormattingRows() throws Exception {
        String key = Shelf.AO + "999";
        java.nio.file.Files.write(new java.io.File(AozoraSite.worksDir(E2E.ctx()), key + ".json").toPath(),
                ("{\"title\":\"試験の名作\",\"author\":\"試験文豪\",\"card\":\"https://www.aozora.gr.jp/cards/000000/card999.html\","
                        + "\"zip\":\"https://www.aozora.gr.jp/cards/000000/files/999_ruby.zip\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AozoraSite.saveSections(E2E.ctx(), key, java.util.Arrays.asList("<p>　吾輩は試験である。</p>", "<p>　名前はまだ無い。</p>"));
        Shelf.update(E2E.ctx(), sh -> { Shelf.Work w = new Shelf.Work(); w.ncode = key; w.title = "試験の名作"; w.episodes = 2; w.seen = 2; sh.works.add(w); });
        String shown = "Array.prototype.map.call(document.querySelectorAll('#info .row.web'),function(r){return getComputedStyle(r).display}).join()";
        reader = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class).putExtra("url", Shelf.episodeUrl(key, 1)).putExtra("page", 0));
        AtomicReference<Activity> a = new AtomicReference<>();
        reader.onActivity(a::set);
        E2E.waitReady(a.get());
        E2E.waitFor(a.get(), "document.getElementById('book').textContent.indexOf('吾輩は試験')>=0", "true");
        assertEquals("\"none,none,none\"", E2E.js(a.get(), shown));
        assertEquals(0, Net.asked.size());
        reader.close();
        Activity b = open(1, 0);
        E2E.waitReady(b);
        assertEquals("\"flex,flex,flex\"", E2E.js(b, shown));
    }

    /** R18: the page slider runs right to left, as the pages turn: its right end is page 1, its left end the last page. */
    @Test
    public void theSliderRunsRightToLeft() throws Exception {
        Activity a = open(1, 0);
        E2E.waitReady(a);
        int last = E2E.num(a, "__reader.pages()-1");
        assertTrue(last > 1);
        E2E.tapMiddle();
        E2E.waitFor(a, "document.getElementById('info').classList.contains('on')", "true");
        E2E.tapSliderEnd(a, true);
        E2E.waitFor(a, "__reader.cur()", String.valueOf(last));
        E2E.tapSliderEnd(a, false);
        E2E.waitFor(a, "__reader.cur()", "0");
    }

    /**
     * R19: a long press on the bar's count switches the slider to the work's episodes. Moving it shows each episode's
     * number and title (from the kept 目次) and fetches nothing; letting go on an episode not kept fetches that one
     * episode and opens it at its first page. The bars shown again span the pages.
     */
    @Test
    public void holdingTheCountMovesThroughEpisodes() throws Exception {
        E2E.reset();
        E2E.seedNarou(5, 40);
        java.io.File dir = new java.io.File(E2E.ctx().getFilesDir(), "episodes/" + E2E.NCODE);
        assertTrue(new java.io.File(dir, "5.html").delete());
        E2E.net.put(Shelf.episodeUrl(E2E.NCODE, 5),
                E2E.narouEpisode(5, 5, "第5話　試験", 40, "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder toc = new StringBuilder("{\"done\":true,\"at\":1,\"pages\":1,\"read\":1,\"entries\":[");
        for (int n = 1; n <= 5; n++) toc.append(n > 1 ? "," : "").append("{\"n\":").append(n).append(",\"t\":\"題").append(n).append("\"}");
        java.nio.file.Files.write(new java.io.File(dir, "toc.json").toPath(), toc.append("]}").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Activity a = open(1, 0);
        E2E.waitReady(a);
        E2E.tapMiddle();
        E2E.waitFor(a, "document.getElementById('info').classList.contains('on')", "true");
        E2E.holdSeekCount(a);
        E2E.waitFor(a, "document.getElementById('seekv').textContent", "\"1 / 5 話\"");
        String st = "document.querySelector('#info .epp.on .st').textContent";
        E2E.js(a, "var s=document.getElementById('seek');s.value=4;s.dispatchEvent(new Event('input'))");
        assertEquals("\"題4\"", E2E.js(a, "document.querySelector('#info .epp.on .tt').textContent"));
        assertEquals("\"保存済み\"", E2E.js(a, st));
        E2E.js(a, "var s=document.getElementById('seek');s.value=5;s.dispatchEvent(new Event('input'))");
        assertEquals("\"未保存　離すと読み込みます\"", E2E.js(a, st));
        assertTrue("nothing fetched while moving: " + Net.asked, Net.asked.isEmpty());
        // Moved away and back, then let go: a range sends no change for its starting value, and the release
        // alone puts the page back (codex on #12).
        E2E.js(a, "var s=document.getElementById('seek');s.value=1;s.dispatchEvent(new Event('input'));s.dispatchEvent(new Event('touchend'))");
        E2E.waitFor(a, "document.querySelector('#info .epp').classList.contains('on')", "false");
        assertEquals("\"1 / 5 話\"", E2E.js(a, "document.getElementById('seekv').textContent"));
        // Aa while the slider spans episodes: back to pages, so the sheet's changes show on the text (review on #12).
        E2E.js(a, "var s=document.getElementById('seek');s.value=4;s.dispatchEvent(new Event('input'))");
        E2E.js(a, "document.getElementById('dispb').click()");
        assertEquals("false", E2E.js(a, "document.querySelector('#info .epp').classList.contains('on')"));
        assertEquals("\"1 / " + E2E.num(a, "__reader.pages()") + "\"", E2E.js(a, "document.getElementById('seekv').textContent"));
        E2E.js(a, "document.getElementById('dispb').click()");
        E2E.holdSeekCount(a);
        E2E.waitFor(a, "document.getElementById('seekv').textContent", "\"1 / 5 話\"");
        E2E.js(a, "var s=document.getElementById('seek');s.value=5;s.dispatchEvent(new Event('input'))");
        // Let go on the one being read: the page comes back, nothing opens.
        E2E.js(a, "var s=document.getElementById('seek');s.value=1;s.dispatchEvent(new Event('input'));s.dispatchEvent(new Event('change'))");
        assertEquals("false", E2E.js(a, "document.querySelector('#info .epp').classList.contains('on')"));
        assertEquals("1", E2E.js(a, "INFO.no"));
        // A real tap at the slider's left end: the last episode, not kept, fetched once.
        E2E.tapSliderEnd(a, true);
        E2E.waitFor(a, "INFO.no", "5");
        E2E.waitFor(a, "__reader.cur()", "0");
        assertEquals(java.util.Collections.singletonList(Shelf.episodeUrl(E2E.NCODE, 5)), new java.util.ArrayList<>(Net.asked));
        E2E.tapMiddle();
        E2E.waitFor(a, "document.getElementById('info').classList.contains('on')", "true");
        assertEquals("\"1 / " + E2E.num(a, "__reader.pages()") + "\"", E2E.js(a, "document.getElementById('seekv').textContent"));
    }

    /**
     * R19c: until the count has been held once, the bars show a hint above it, at most 3 times; a hold stops it,
     * and a tap on the episode title page goes back to the page being read (the user on the A25 read it as frozen).
     */
    @Test
    public void theCountsHintShowsUntilItIsHeld() throws Exception {
        E2E.reset();
        E2E.seedNarou(3, 40);
        String hint = "document.querySelector('#info .ephint').classList.contains('on')";
        String bars = "document.getElementById('info').classList.contains('on')";
        Activity a = open(1, 0);
        E2E.waitReady(a);
        for (int n = 1; n <= 4; n++) {
            E2E.tapMiddle();
            E2E.waitFor(a, bars, "true");
            assertEquals("opening " + n, n <= 3 ? "true" : "false", E2E.js(a, hint));
            SystemClock.sleep(600);
            E2E.tapMiddle();
            E2E.waitFor(a, bars, "false");
        }
        // Saved, so the next launch does not show it either.
        assertTrue(E2E.ctx().getSharedPreferences("reader", 0).getString("display", "").contains("\"ephint\":3"));

        // A fresh start: shown once, then a hold stops it for good.
        reader.close();
        E2E.ctx().getSharedPreferences("reader", 0).edit().putString("display", "{\"guided\":true}").commit();
        a = open(1, 0);
        E2E.waitReady(a);
        E2E.tapMiddle();
        E2E.waitFor(a, bars, "true");
        assertEquals("true", E2E.js(a, hint));
        E2E.holdSeekCount(a);
        E2E.waitFor(a, "document.querySelector('#info .epp').classList.contains('on')", "true");
        assertEquals("false", E2E.js(a, hint));
        // A tap on the title page closes the bars, back on the page being read.
        E2E.tapMiddle();
        E2E.waitFor(a, bars, "false");
        assertEquals("1", E2E.js(a, "INFO.no"));
        assertEquals("0", E2E.js(a, "__reader.cur()"));
        E2E.tapMiddle();
        E2E.waitFor(a, bars, "true");
        assertEquals("false", E2E.js(a, hint));
        assertTrue(E2E.ctx().getSharedPreferences("reader", 0).getString("display", "").contains("\"ephint\":3"));
    }

    /**
     * R20: the base of the thumb does not turn the page, a resting finger does not keep the thumb's tap, and a
     * fingertip near the edge still turns (端の誤タッチ, on by default). Touches are sent with a contact width
     * (radiusX), as measured on the A25 held in one hand.
     */
    @Test
    public void theBaseOfTheThumbDoesNotTurn() throws Exception {
        E2E.reset();
        E2E.seedNarou(3, 180);
        Activity a = open(1, 0);
        E2E.waitReady(a);
        assertTrue("needs pages to turn", E2E.num(a, "__reader.pages()") >= 12);
        E2E.js(a, "window.__t=function(type,list,changed){function mk(o){return new Touch({identifier:o.id,target:document.body,"
                + "clientX:o.x,clientY:o.y,radiusX:o.r,radiusY:o.r});}document.body.dispatchEvent(new TouchEvent(type,"
                + "{touches:list.map(mk),changedTouches:changed.map(mk),bubbles:true,cancelable:true}));};"
                + "window.__tap=function(o){__t('touchstart',[o],[o]);__t('touchend',[],[o]);};0");
        String h = "innerHeight", w = "innerWidth";
        // The base, narrow at the left edge, low down (87 to 98 % down where it was measured), alone: no turn.
        E2E.js(a, "__tap({id:1,x:6,y:" + h + "*0.9,r:0.3})");
        SystemClock.sleep(400);
        assertEquals("0", E2E.js(a, "__reader.cur()"));
        // The base in the bottom corner, wider, 14 px in (it slipped through before the corner rule): no turn.
        // (Each on its own: a left tap and a right tap that both got through would cancel out.)
        E2E.js(a, "__tap({id:2,x:14,y:" + h + "*0.93,r:1.1})");
        SystemClock.sleep(400);
        assertEquals("left corner", "0", E2E.js(a, "__reader.cur()"));
        E2E.js(a, "__tap({id:3,x:" + w + "-10,y:" + h + "*0.95,r:1.3})");
        SystemClock.sleep(400);
        assertEquals("right corner", "0", E2E.js(a, "__reader.cur()"));
        // A fingertip 14 px from the edge at mid height: it turns.
        E2E.js(a, "__tap({id:4,x:14,y:" + h + "*0.6,r:1.1})");
        E2E.waitFor(a, "__reader.cur()", "1");
        SystemClock.sleep(400);
        // A palm resting further in, then the thumb taps: the thumb turns, the palm lifting does not.
        E2E.js(a, "window.R={id:5,x:40,y:" + h + "*0.7,r:2};__t('touchstart',[R],[R])");
        SystemClock.sleep(400);                                          // resting, not in the middle of a tap
        E2E.js(a, "var T={id:6,x:30,y:" + h + "*0.5,r:1.2};__t('touchstart',[R,T],[T]);__t('touchend',[R],[T])");
        E2E.waitFor(a, "__reader.cur()", "2");
        SystemClock.sleep(400);
        E2E.js(a, "__t('touchend',[],[{id:5,x:40,y:" + h + "*0.7,r:2}])");
        SystemClock.sleep(400);
        assertEquals("2", E2E.js(a, "__reader.cur()"));
        // The base landing at the edge during a tap (wide, so only "another finger is down" names it): the tap
        // turns on its own release, while the base is still down; the base lifting turns nothing.
        E2E.js(a, "var T={id:7,x:30,y:" + h + "*0.5,r:1.2},B={id:8,x:6,y:" + h + "*0.8,r:1.5};"
                + "__t('touchstart',[T],[T]);__t('touchstart',[T,B],[B]);__t('touchend',[B],[T])");
        E2E.waitFor(a, "__reader.cur()", "3");
        SystemClock.sleep(400);
        E2E.js(a, "__t('touchend',[],[{id:8,x:6,y:" + h + "*0.8,r:1.5}])");
        SystemClock.sleep(400);
        assertEquals("3", E2E.js(a, "__reader.cur()"));
        // Another finger landing in the middle of a tap (not a base, on the other side): the tap keeps the gesture
        // and turns forward; the other finger lifting turns nothing (review on #13).
        E2E.js(a, "var T={id:10,x:30,y:" + h + "*0.5,r:1.2},F={id:11,x:" + w + "-60,y:" + h + "*0.5,r:1.5};"
                + "__t('touchstart',[T],[T]);__t('touchstart',[T,F],[F]);__t('touchend',[F],[T])");
        E2E.waitFor(a, "__reader.cur()", "4");
        SystemClock.sleep(400);
        E2E.js(a, "__t('touchend',[],[{id:11,x:" + w + "-60,y:" + h + "*0.5,r:1.5}])");
        SystemClock.sleep(400);
        assertEquals("4", E2E.js(a, "__reader.cur()"));
        // A palm resting, then the base lands wide at the edge and lifts quickly: "another finger is down" names
        // it a base, so it does not take the gesture from the resting palm and turns nothing.
        E2E.js(a, "window.P={id:13,x:60,y:" + h + "*0.7,r:2};__t('touchstart',[P],[P])");
        SystemClock.sleep(400);
        E2E.js(a, "var B={id:14,x:6,y:" + h + "*0.7,r:1.5};__t('touchstart',[P,B],[B]);__t('touchend',[P],[B])");
        SystemClock.sleep(400);
        assertEquals("4", E2E.js(a, "__reader.cur()"));
        E2E.js(a, "__t('touchend',[],[P])");
        SystemClock.sleep(600);
        assertEquals("4", E2E.js(a, "__reader.cur()"));
        // A swipe whose release never arrives (the page swapped under the finger): the next tap still works.
        E2E.js(a, "var S={id:15,x:150,y:" + h + "*0.5,r:1.5};__t('touchstart',[S],[S]);"
                + "__t('touchmove',[{id:15,x:165,y:" + h + "*0.5,r:1.5}],[{id:15,x:165,y:" + h + "*0.5,r:1.5}])");
        SystemClock.sleep(300);
        E2E.js(a, "__tap({id:16,x:30,y:" + h + "*0.5,r:1.2})");
        E2E.waitFor(a, "__reader.cur()", "5");
        SystemClock.sleep(400);
        // A fingertip at the very edge at mid height, its contact cut as narrow as the base's (holding the phone
        // normally, the user found these ignored when width alone named the base): it turns.
        E2E.js(a, "__tap({id:12,x:5,y:" + h + "*0.45,r:0.3})");
        E2E.waitFor(a, "__reader.cur()", "6");
        SystemClock.sleep(400);
        // Both hands: the other hand rests a finger on the right side, then the left thumb taps the left side at
        // mid height. The resting finger is on the other half, so the tap is not a base: it turns.
        E2E.js(a, "window.Q={id:17,x:" + w + "-8,y:" + h + "*0.5,r:1.5};__t('touchstart',[Q],[Q])");
        SystemClock.sleep(400);
        E2E.js(a, "var L={id:18,x:6,y:" + h + "*0.5,r:0.4};__t('touchstart',[Q,L],[L]);__t('touchend',[Q],[L])");
        E2E.waitFor(a, "__reader.cur()", "7");
        SystemClock.sleep(400);
        E2E.js(a, "__t('touchend',[],[Q])");
        SystemClock.sleep(600);
        assertEquals("7", E2E.js(a, "__reader.cur()"));
        // A base landing in the bottom corner during a tap, still down after the tap: it is remembered as a base,
        // so the next tap at the same side is not taken for one (codex on #13).
        E2E.js(a, "var T={id:19,x:30,y:" + h + "*0.5,r:1.2};window.C={id:20,x:8,y:" + h + "*0.93,r:1.5};"
                + "__t('touchstart',[T],[T]);__t('touchstart',[T,C],[C]);__t('touchend',[C],[T])");
        E2E.waitFor(a, "__reader.cur()", "8");
        SystemClock.sleep(400);
        E2E.js(a, "var U={id:21,x:6,y:" + h + "*0.5,r:1.2};__t('touchstart',[C,U],[U]);__t('touchend',[C],[U])");
        E2E.waitFor(a, "__reader.cur()", "9");
        SystemClock.sleep(400);
        E2E.js(a, "__t('touchend',[],[C])");
        SystemClock.sleep(400);
        // 防がない: a touch in the bottom corner turns, as before.
        E2E.tapMiddle();
        E2E.waitFor(a, "document.getElementById('info').classList.contains('on')", "true");
        SystemClock.sleep(600);                                          // a click in the bars' first 500 ms is dropped
        E2E.js(a, "document.getElementById('dispb').click();document.querySelector('.ek[data-edge=\"0\"]').click()");
        E2E.waitUntil(() -> E2E.ctx().getSharedPreferences("reader", 0).getString("display", "").contains("\"edge\":false"),
                "端の誤タッチ 防がない saved");
        E2E.js(a, "document.getElementById('dispb').click()");
        E2E.tapMiddle();
        E2E.waitFor(a, "document.getElementById('info').classList.contains('on')", "false");
        SystemClock.sleep(600);
        E2E.js(a, "__tap({id:9,x:6,y:" + h + "*0.9,r:0.3})");
        E2E.waitFor(a, "__reader.cur()", "10");
    }

    /**
     * R21: Aa's sheet pulled down from its top follows the finger; let go past a third of its height it closes with the
     * bars, short of that it slides back. 作品's sheet does the same. めくりの振動 is off until chosen, and saved.
     */
    @Test
    public void aSheetPulledDownCloses() throws Exception {
        E2E.reset();
        E2E.seedNarou(3, 40);
        Activity a = open(1, 0);
        E2E.waitReady(a);
        String bars = "document.getElementById('info').classList.contains('on')";
        E2E.js(a, "window.__pull=function(sel,dy){var sh=document.querySelector(sel),r=sh.getBoundingClientRect(),"
                + "x=r.left+r.width/2,y=r.top+20;function t(type,yy,list){var o=new Touch({identifier:1,target:sh,clientX:x,clientY:yy});"
                + "sh.dispatchEvent(new TouchEvent(type,{touches:list?[o]:[],changedTouches:[o],bubbles:true}));}"
                + "t('touchstart',y,1);t('touchmove',y+dy/2,1);t('touchmove',y+dy,1);var moved=sh.style.transform;t('touchend',y+dy,0);return moved;};0");
        for (String b : new String[] {"dispb", "workb"}) {
            String sheet = b.equals("dispb") ? "#info .sheet.d" : "#info .sheet.w";
            E2E.tapMiddle();
            E2E.waitFor(a, bars, "true");
            SystemClock.sleep(600);                                      // a click in the bars' first 500 ms is dropped
            E2E.js(a, "document.getElementById('" + b + "').click()");
            // A short pull: past 10 px of give it followed the finger, and slides back on letting go.
            assertEquals("\"translateY(30px)\"", E2E.js(a, "__pull('" + sheet + "',40)"));
            assertEquals("true", E2E.js(a, bars));
            E2E.waitFor(a, "document.querySelector('" + sheet + "').style.transform", "\"\"");
            // Past a third of its height: closed, back to the page.
            E2E.js(a, "__pull('" + sheet + "',document.querySelector('" + sheet + "').offsetHeight*0.6)");
            E2E.waitFor(a, bars, "false");
            SystemClock.sleep(400);
        }
        // A second finger during a pull: the pull is dropped and the sheet slides back, not left where it was (codex on #16).
        E2E.tapMiddle();
        E2E.waitFor(a, bars, "true");
        SystemClock.sleep(600);
        E2E.js(a, "document.getElementById('dispb').click()");
        E2E.js(a, "var sh=document.querySelector('#info .sheet.d'),r=sh.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+20;"
                + "function T(id,yy){return new Touch({identifier:id,target:sh,clientX:x,clientY:yy});}"
                + "function ev(type,list,ch){sh.dispatchEvent(new TouchEvent(type,{touches:list,changedTouches:ch,bubbles:true}));}"
                + "var a1=T(1,y),a2=T(1,y+100),b=T(2,y+50);ev('touchstart',[a1],[a1]);ev('touchmove',[a2],[a2]);"
                + "ev('touchstart',[a2,b],[b]);ev('touchend',[b],[a2]);ev('touchend',[],[b]);0");
        E2E.waitFor(a, "document.querySelector('#info .sheet.d').style.transform", "\"\"");
        assertEquals("true", E2E.js(a, bars));
        // A touch that starts at the top, scrolls the sheet and comes back past the top a long way: a scroll, not a
        // pull, so the sheet stays open (review on 0.1.5). On 操作, the tab long enough to scroll.
        E2E.js(a, "document.querySelector('#info .tab[data-tab=\"touch\"]').click()");
        assertEquals("a scroll back past the top", "\"open\"", E2E.js(a, "var sh=document.querySelector('#info .sheet.d'),r=sh.getBoundingClientRect(),"
                + "x=r.left+r.width/2,y=r.top+200;function t(type,yy,list){var o=new Touch({identifier:1,target:sh,clientX:x,clientY:yy});"
                + "sh.dispatchEvent(new TouchEvent(type,{touches:list?[o]:[],changedTouches:[o],bubbles:true}));}"
                + "t('touchstart',y,1);sh.scrollTop=40;var s=sh.scrollTop;t('touchmove',y-40,1);sh.scrollTop=0;"
                + "t('touchmove',y+sh.offsetHeight*0.3,1);t('touchmove',y+sh.offsetHeight*0.6,1);var m=sh.style.transform;t('touchend',y+sh.offsetHeight*0.6,0);"
                + "s>0&&!m?'open':'scrolled '+s+' moved '+m"));
        SystemClock.sleep(300);
        assertEquals("true", E2E.js(a, bars));
        E2E.js(a, "document.getElementById('dispb').click()");
        E2E.tapMiddle();
        E2E.waitFor(a, bars, "false");
        SystemClock.sleep(400);
        // めくりの振動: off by default, saved when chosen, and a tap still turns.
        assertTrue(!E2E.ctx().getSharedPreferences("reader", 0).getString("display", "").contains("\"buzz\":true"));
        E2E.tapMiddle();
        E2E.waitFor(a, bars, "true");
        SystemClock.sleep(600);
        E2E.js(a, "document.getElementById('dispb').click();document.querySelector('.zk[data-buzz=\"1\"]').click()");
        E2E.waitUntil(() -> E2E.ctx().getSharedPreferences("reader", 0).getString("display", "").contains("\"buzz\":true"),
                "めくりの振動 震わせる saved");
        E2E.js(a, "document.getElementById('dispb').click()");
        E2E.tapMiddle();
        E2E.waitFor(a, bars, "false");
        SystemClock.sleep(600);
        int before = E2E.num(a, "__reader.cur()");
        assertEquals("0", E2E.js(a, "__reader.ticks()"));
        E2E.tapLeft();
        E2E.waitFor(a, "__reader.cur()", String.valueOf(before + 1));
        assertEquals("a tap turn ticks", "1", E2E.js(a, "__reader.ticks()"));
        SystemClock.sleep(400);
        // A swipe turns without a tick.
        androidx.test.uiautomator.UiDevice d = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation());
        d.swipe(d.getDisplayWidth() * 3 / 10, d.getDisplayHeight() / 2, d.getDisplayWidth() * 8 / 10, d.getDisplayHeight() / 2, 12);
        E2E.waitFor(a, "__reader.cur()", String.valueOf(before + 2));
        assertEquals("a swipe turn does not tick", "1", E2E.js(a, "__reader.ticks()"));
        // At the first page of the first episode a tap reaches the app once ("これが最初の話です") and ticks once; a second
        // tap while that is on its way is dropped, and does not tick (review on 0.1.5).
        E2E.js(a, "__reader.go(0)");
        E2E.waitFor(a, "__reader.cur()", "0");
        SystemClock.sleep(1600);                                     // past the last edge's 1.5 s
        E2E.js(a, "__reader.turn(-1);__reader.turn(-1);0");
        assertEquals("one tick for the tap that reached the app", "2", E2E.js(a, "__reader.ticks()"));
    }

    /**
     * R22: the tap zones. By default a tap at the left turns to the next page, the middle shows the bars; 左右反転
     * swaps the sides; a zone tapped in Aa goes next, previous, menu, nothing, but the last menu zone stays; a zone set
     * to nothing does nothing; 真ん中の幅 moves where the middle starts; the guide is drawn from the zones; all saved.
     */
    @Test
    public void theTapZonesCanBeChanged() throws Exception {
        E2E.reset();
        E2E.seedNarou(3, 40);
        Activity a = open(1, 0);
        E2E.waitReady(a);
        String bars = "document.getElementById('info').classList.contains('on')";
        E2E.js(a, "__reader.go(2)");
        E2E.waitFor(a, "__reader.cur()", "2");
        // 左右反転: the left now goes back, the right forward.
        openAa(a, bars);
        // Every row of choices in Aa shows its choice the same way: a row whose class the stylesheet does not list keeps
        // the chosen button looking like the others (上端のタップ did, until the screen matrix showed it). The swatches
        // (an outline) and the size steps (A− A＋, no choice) are not rows of choices.
        assertEquals("\"\"", E2E.js(a, "(function(){var on=getComputedStyle(document.querySelector('#info .zp.on')).color,out=[];"
                + "document.querySelectorAll('#info .sheet.d .opts').forEach(function(o){if(o.querySelector('.sw,[data-fs]'))return;"
                + "var b=o.querySelectorAll('button.on');"
                + "if(b.length!==1||getComputedStyle(b[0]).color!==on)out.push(o.parentNode.textContent.slice(0,8)+':'+b.length);});"
                + "return out.join(' ');})()"));
        E2E.js(a, "document.querySelector('.zp[data-zp=\"pmnpmnpmn\"]').click()");
        assertEquals("true", E2E.js(a, "document.querySelector('.zp[data-zp=\"pmnpmnpmn\"]').classList.contains('on')"));
        assertEquals("\"前へ\"", E2E.js(a, "document.querySelector('.zc[data-zc=\"0\"]').textContent"));
        closeAa(a, bars);
        E2E.tapLeft();
        E2E.waitFor(a, "__reader.cur()", "1");
        E2E.tapRight();
        E2E.waitFor(a, "__reader.cur()", "2");
        // Back to 標準, then the middle column tapped: menu becomes nothing, twice; the last menu zone stays.
        openAa(a, bars);
        E2E.js(a, "document.querySelector('.zp[data-zp=\"nmpnmpnmp\"]').click()");
        E2E.js(a, "document.querySelector('.zc[data-zc=\"1\"]').click();document.querySelector('.zc[data-zc=\"4\"]').click();"
                + "document.querySelector('.zc[data-zc=\"7\"]').click()");
        assertEquals("\"メニュー\"", E2E.js(a, "document.querySelector('.zc[data-zc=\"7\"]').textContent"));
        // The message goes with the next change.
        E2E.js(a, "document.querySelector('.zw[data-zw=\"m\"]').click()");
        assertEquals("\"\"", E2E.js(a, "document.querySelector('.zmsg').textContent"));
        E2E.js(a, "document.querySelector('.zc[data-zc=\"7\"]').click()");
        assertTrue(E2E.js(a, "document.querySelector('.zmsg').textContent").contains("1 つ残すか"));
        E2E.waitUntil(() -> E2E.ctx().getSharedPreferences("reader", 0).getString("display", "").contains("\"zones\":\"n0pn0pnmp\""),
                "zones saved");
        // The guide is drawn from them: each zone in its colour, one label per region of zones that do the same.
        E2E.js(a, "document.querySelector('#info button[data-guide]').click()");
        E2E.waitFor(a, "document.getElementById('guide').classList.contains('on')", "true");
        assertEquals("\"n o p n o p n m p\"", E2E.js(a, "Array.prototype.map.call(document.querySelectorAll('#guide .z'),function(z){return z.classList[1];}).join(' ')"));
        assertEquals("\"次のページ なし 前のページ メニュー\"", E2E.js(a, "Array.prototype.map.call(document.querySelectorAll('#guide .z b'),function(z){return z.textContent;}).join(' ')"));
        E2E.tapMiddle();
        E2E.waitFor(a, "document.getElementById('guide').classList.contains('on')", "false");
        SystemClock.sleep(400);
        // The middle of the screen does nothing now: neither the bars nor a turn.
        E2E.tapMiddle();
        SystemClock.sleep(700);
        assertEquals("false", E2E.js(a, bars));
        assertEquals("2", E2E.js(a, "__reader.cur()"));
        // At 35 % across and low down, the menu row: with 真ん中の幅 普通 that is the left column (next page); 広い
        // starts the middle at 33 %, the menu.
        androidx.test.uiautomator.UiDevice d = E2E.device();
        d.click(d.getDisplayWidth() * 35 / 100, d.getDisplayHeight() * 80 / 100);
        E2E.waitFor(a, "__reader.cur()", "3");
        SystemClock.sleep(400);
        // The bars from the menu zone at the bottom middle.
        d.click(d.getDisplayWidth() / 2, d.getDisplayHeight() * 80 / 100);
        E2E.waitFor(a, bars, "true");
        SystemClock.sleep(600);
        E2E.js(a, "document.getElementById('dispb').click();document.querySelector('.zw[data-zw=\"l\"]').click();"
                + "document.querySelector('.zh[data-zh=\"l\"]').click()");
        E2E.waitUntil(() -> E2E.ctx().getSharedPreferences("reader", 0).getString("display", "").contains("\"zh\":\"l\""), "zw, zh saved");
        // Opened again, the reader reads them back: at 35 % across and 78 % down, the middle column (広い starts it at
        // 33 %) and the bottom row (高い makes the middle row 25 % to 75 %), the menu; the middle of the screen still
        // does nothing.
        reader.close();
        a = open(1, 2);
        E2E.waitReady(a);
        d.click(d.getDisplayWidth() / 2, d.getDisplayHeight() / 2);
        SystemClock.sleep(700);
        assertEquals("false", E2E.js(a, bars));
        assertEquals("2", E2E.js(a, "__reader.cur()"));
        // At 70 % down, still the middle row with 真ん中の高さ 高い (with 普通 it would be the bottom row's menu): nothing (codex).
        d.click(d.getDisplayWidth() * 35 / 100, d.getDisplayHeight() * 70 / 100);
        SystemClock.sleep(700);
        assertEquals("false", E2E.js(a, bars));
        assertEquals("2", E2E.js(a, "__reader.cur()"));
        d.click(d.getDisplayWidth() * 35 / 100, d.getDisplayHeight() * 78 / 100);
        E2E.waitFor(a, bars, "true");
        assertEquals("2", E2E.js(a, "__reader.cur()"));
        // A saved size that is no choice (an inherited name) falls back to 普通: the menu zone at the top left stays
        // reachable (codex).
        reader.close();
        E2E.ctx().getSharedPreferences("reader", 0).edit()
                .putString("display", "{\"guided\":true,\"zones\":\"mnnnnnnnn\",\"zw\":\"constructor\",\"zh\":\"toString\"}").commit();
        a = open(1, 2);
        E2E.waitReady(a);
        d.click(d.getDisplayWidth() / 10, d.getDisplayHeight() / 10);
        E2E.waitFor(a, bars, "true");
        // 上端のタップ メニューを表示: a tap at the top right, in the strip where Aa sits, shows the bars, though the zone
        // there turns to the next page; just below the strip it still turns. The guide draws the strip.
        SystemClock.sleep(600);
        E2E.js(a, "document.getElementById('dispb').click();document.querySelector('.tk[data-tb=\"1\"]').click()");
        E2E.waitUntil(() -> E2E.ctx().getSharedPreferences("reader", 0).getString("display", "").contains("\"tb\":true"), "tb saved");
        String strip = E2E.js(a, "document.querySelector('#info .topbar').offsetHeight*devicePixelRatio");
        int sh = (int) Double.parseDouble(strip);
        closeAa(a, bars);
        d.click(d.getDisplayWidth() * 90 / 100, sh / 2);
        E2E.waitFor(a, bars, "true");
        assertEquals("2", E2E.js(a, "__reader.cur()"));
        SystemClock.sleep(600);
        closeAa(a, bars);
        d.click(d.getDisplayWidth() * 90 / 100, sh + d.getDisplayHeight() / 20);
        E2E.waitFor(a, "__reader.cur()", "3");
        assertEquals("false", E2E.js(a, bars));
        SystemClock.sleep(400);
        d.click(d.getDisplayWidth() / 2, sh / 2);
        E2E.waitFor(a, bars, "true");
        SystemClock.sleep(600);
        E2E.js(a, "document.getElementById('dispb').click();document.querySelector('#info button[data-guide]').click()");
        E2E.waitFor(a, "document.getElementById('guide').classList.contains('on')", "true");
        assertEquals("\"メニュー\"", E2E.js(a, "document.querySelector('#guide .tb').textContent"));
        // Opened again, before any bars are up (the strip measured with them hidden): the strip still shows them (codex).
        reader.close();
        a = open(1, 2);
        E2E.waitReady(a);
        d.click(d.getDisplayWidth() * 90 / 100, sh / 2);
        E2E.waitFor(a, bars, "true");
        assertEquals("2", E2E.js(a, "__reader.cur()"));
        // With the strip, the last menu zone may go; the strip then stays on (it is the only way to the bars) until a
        // zone is a menu again.
        SystemClock.sleep(600);
        E2E.js(a, "document.getElementById('dispb').click();document.querySelector('.zc[data-zc=\"0\"]').click()");
        E2E.waitUntil(() -> E2E.ctx().getSharedPreferences("reader", 0).getString("display", "").contains("\"zones\":\"0nnnnnnnn\""), "no menu zone saved");
        E2E.js(a, "document.querySelector('.tk[data-tb=\"0\"]').click()");
        assertTrue(E2E.js(a, "document.querySelector('.zmsg').textContent").contains("区画どおり"));
        assertEquals("true", E2E.js(a, "document.querySelector('.tk[data-tb=\"1\"]').classList.contains('on')"));
        closeAa(a, bars);
        d.click(d.getDisplayWidth() / 10, sh / 2);
        E2E.waitFor(a, bars, "true");
        assertTrue(E2E.ctx().getSharedPreferences("reader", 0).getString("display", "").contains("\"tb\":true"));
        // Opened again, the zones without a menu are kept: the middle turns the page.
        reader.close();
        a = open(1, 2);
        E2E.waitReady(a);
        E2E.tapMiddle();
        E2E.waitFor(a, "__reader.cur()", "3");
        assertEquals("false", E2E.js(a, bars));
        // Saved zones without a menu and without the strip (an older version's, or a broken value): the default zones,
        // the bars from the middle.
        reader.close();
        E2E.ctx().getSharedPreferences("reader", 0).edit()
                .putString("display", "{\"guided\":true,\"zones\":\"0nnnnnnnn\",\"tb\":false}").commit();
        a = open(1, 2);
        E2E.waitReady(a);
        E2E.tapMiddle();
        E2E.waitFor(a, bars, "true");
        assertEquals("2", E2E.js(a, "__reader.cur()"));
    }

    /**
     * R23: Aa opens on 見た目 with only its rows; 操作 and その他 show theirs, the sheet as tall as they are; the tabs
     * stay where they were (at the sheet's foot, on the screen's edge) and stay there over a scrolled sheet; the tab
     * last open comes back.
     */
    @Test
    public void theAaSheetIsInTabs() throws Exception {
        E2E.reset();
        E2E.seedNarou(3, 40);
        Activity a = open(1, 0);
        E2E.waitReady(a);
        String bars = "document.getElementById('info').classList.contains('on')";
        String shown = "(function(s){var e=document.querySelector(s);return !!(e&&e.offsetParent);})";
        // Where the tab is drawn, and whether a tap there reaches it rather than something over it.
        String tab = "(function(t){var b=document.querySelector('#info .tab[data-tab=\"'+t+'\"]'),r=b.getBoundingClientRect();"
                + "return Math.round(r.top)+(document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)===b?'':' covered');})";
        openAa(a, bars);
        assertEquals("true", E2E.js(a, shown + "('#info .sw')"));
        assertEquals("false", E2E.js(a, shown + "('#info .zg')"));
        assertEquals("false", E2E.js(a, shown + "('#info .ah')"));
        String top = E2E.js(a, tab + "('look')");
        assertFalse(top, top.contains("covered"));
        E2E.js(a, "document.querySelector('#info .tab[data-tab=\"touch\"]').click()");
        assertEquals("true", E2E.js(a, shown + "('#info .zg')"));
        assertEquals("false", E2E.js(a, shown + "('#info .sw')"));
        assertEquals("true", E2E.js(a, "document.querySelector('#info .tab[data-tab=\"touch\"]').getAttribute('aria-selected')==='true'"));
        assertEquals("the tabs did not move", top, E2E.js(a, tab + "('touch')"));
        // Scrolled to the end, the tabs are still there to tap.
        E2E.js(a, "document.querySelector('#info .sheet.d').scrollTop=9999");
        assertEquals("true", E2E.js(a, "document.querySelector('#info .sheet.d').scrollTop>0"));
        assertEquals(top, E2E.js(a, tab + "('other')"));
        // A tab opens at its top, also the one already open.
        E2E.js(a, "document.querySelector('#info .tab[data-tab=\"touch\"]').click()");
        assertEquals("0", E2E.js(a, "document.querySelector('#info .sheet.d').scrollTop"));
        E2E.js(a, "document.querySelector('#info .sheet.d').scrollTop=9999");
        E2E.js(a, "document.querySelector('#info .tab[data-tab=\"other\"]').click()");
        assertEquals("true", E2E.js(a, shown + "('#info .ah')"));
        assertEquals("false", E2E.js(a, shown + "('#info .zg')"));
        assertEquals("0", E2E.js(a, "document.querySelector('#info .sheet.d').scrollTop"));
        assertEquals(top, E2E.js(a, tab + "('other')"));
        // その他's two rows: the sheet is short and the page shows above it.
        assertEquals("true", E2E.js(a, "document.querySelector('#info .sheet.d').getBoundingClientRect().top>window.innerHeight/2"));
        // Closed and opened again: その他, at the top.
        closeAa(a, bars);
        openAa(a, bars);
        assertEquals("true", E2E.js(a, shown + "('#info .ah')"));
        assertEquals("true", E2E.js(a, "document.querySelector('#info .tab[data-tab=\"other\"]').classList.contains('on')"));
    }

    /**
     * R24: the place is the share of the episode read. Turned to landscape and back, the reader stays at the same
     * share, not the same page number; opened from a place saved at another page count (a size changed since), it
     * opens at the same share; a larger size under the bars counts the page bar again.
     */
    @Test
    public void thePlaceIsTheShareRead() throws Exception {
        E2E.reset();
        E2E.seedNarou(3, 120);
        androidx.test.uiautomator.UiDevice d = E2E.device();
        try {
            d.setOrientationPortrait();
            Activity a = open(1, 0);
            E2E.waitReady(a);
            String share = "__reader.cur()/Math.max(1,__reader.pages()-1)";
            int pages = Integer.parseInt(E2E.js(a, "__reader.pages()"));
            assertTrue("pages " + pages, pages >= 8);
            E2E.js(a, "__reader.go(" + (pages * 3 / 4) + ")");
            double before = Double.parseDouble(E2E.js(a, share));
            d.setOrientationLandscape();
            E2E.waitUntil(() -> { try { return Integer.parseInt(E2E.js(a, "__reader.pages()")) != pages; } catch (Exception e) { return false; } }, "relaid out");
            SystemClock.sleep(500);
            int lp = Integer.parseInt(E2E.js(a, "__reader.pages()"));
            double after = Double.parseDouble(E2E.js(a, share));
            assertTrue("landscape " + before + " -> " + after + " of " + lp, Math.abs(after - before) <= 1.0 / Math.max(1, lp - 1) + 1e-9);
            d.setOrientationPortrait();
            E2E.waitUntil(() -> { try { return Integer.parseInt(E2E.js(a, "__reader.pages()")) == pages; } catch (Exception e) { return false; } }, "back");
            SystemClock.sleep(500);
            int back = Integer.parseInt(E2E.js(a, "__reader.cur()"));
            assertTrue("portrait again at " + back + ", was " + (pages * 3 / 4), Math.abs(back - pages * 3 / 4) <= 1);
            // A larger size with the bars up: the page bar counts the new pages.
            String bars = "document.getElementById('info').classList.contains('on')";
            openAa(a, bars);
            E2E.js(a, "document.querySelector('#info [data-fs=\"1\"]').click();document.querySelector('#info [data-fs=\"1\"]').click()");
            assertEquals(E2E.js(a, "String(__reader.pages())"), E2E.js(a, "document.getElementById('seek').max"));
            reader.close();
            // Saved at page 30 of 40 (a smaller size then): opened at 75 % of the pages now.
            Intent i = new Intent(E2E.ctx(), ReaderActivity.class).putExtra("url", Shelf.episodeUrl(E2E.NCODE, 1))
                    .putExtra("page", 30).putExtra("pages", 41);
            reader = ActivityScenario.launch(i);
            AtomicReference<Activity> r = new AtomicReference<>();
            reader.onActivity(r::set);
            E2E.waitReady(r.get());
            int now = Integer.parseInt(E2E.js(r.get(), "__reader.pages()"));
            assertEquals(Math.round(0.75 * (now - 1)), Long.parseLong(E2E.js(r.get(), "__reader.cur()")));
        } finally {
            d.unfreezeRotation();
        }
    }

    /**
     * R25: small things from the review of 0.1.6. The episode being read, picked in the 目次, closes it as 閉じる does;
     * the bounce at an end is busy until it settles, so a swipe started during it waits; a volume key during a swipe
     * does nothing.
     */
    @Test
    public void smallThingsFromTheReview() throws Exception {
        E2E.reset();
        E2E.seedNarou(3, 40);
        Activity a = open(1, 0);
        E2E.waitReady(a);
        E2E.js(a, "var t=document.getElementById('toc');t.classList.add('on');t.dataset.scrolled='1';"
                + "t.querySelector('.ls').insertAdjacentHTML('beforeend','<a class=\"ep cur\" data-n=\"1\">第1話</a>');"
                + "t.querySelector('.ls [data-n=\"1\"]').click()");
        assertEquals("false", E2E.js(a, "document.getElementById('toc').classList.contains('on')"));
        assertEquals("false", E2E.js(a, "'scrolled' in document.getElementById('toc').dataset"));
        // At the first page, a swipe back bounces; another at once is not taken while it does.
        E2E.js(a, "__reader.go(0);__reader.turn(-1,true);__reader.turn(1,true);0");
        SystemClock.sleep(600);
        assertEquals("0", E2E.js(a, "__reader.cur()"));
        // A finger swiping: the volume key does nothing; lifted, it turns.
        E2E.js(a, "var o=new Touch({identifier:7,target:document.body,clientX:200,clientY:400}),m=new Touch({identifier:7,target:document.body,clientX:240,clientY:400});"
                + "document.dispatchEvent(new TouchEvent('touchstart',{touches:[o],changedTouches:[o],bubbles:true}));"
                + "document.dispatchEvent(new TouchEvent('touchmove',{touches:[m],changedTouches:[m],bubbles:true}));__reader.key(1);0");
        assertEquals("0", E2E.js(a, "__reader.cur()"));
        E2E.js(a, "var o=new Touch({identifier:7,target:document.body,clientX:205,clientY:400});"
                + "document.dispatchEvent(new TouchEvent('touchend',{touches:[],changedTouches:[o],bubbles:true}));0");
        SystemClock.sleep(600);                                      // the lift was a tap: whatever it opened is closed
        E2E.js(a, "__reader.closeOverlays();0");
        int at = Integer.parseInt(E2E.js(a, "__reader.cur()"));
        E2E.js(a, "__reader.key(1)");
        E2E.waitFor(a, "__reader.cur()", String.valueOf(at + 1));
        // A swipe whose text is swapped from under it (the next episode came in): its lift never reaches the document,
        // and the keys still turn after it (codex on #25).
        E2E.js(a, "var o=new Touch({identifier:8,target:document.body,clientX:200,clientY:400}),m=new Touch({identifier:8,target:document.body,clientX:240,clientY:400});"
                + "document.dispatchEvent(new TouchEvent('touchstart',{touches:[o],changedTouches:[o],bubbles:true}));"
                + "document.dispatchEvent(new TouchEvent('touchmove',{touches:[m],changedTouches:[m],bubbles:true}));"
                + "__reader.swap(document.getElementById('book').innerHTML,INFO,0);0");
        SystemClock.sleep(400);
        E2E.js(a, "__reader.key(1)");
        E2E.waitFor(a, "__reader.cur()", "1");
    }

    /**
     * R26: with 音量キー ページをめくる, a volume key turns the page and the system does not get it; under the bars it
     * goes to the system (the volume), and so does its release, also when the bars close in between.
     */
    @Test
    public void volumeKeysAreTheSystemsUnderTheBars() throws Exception {
        E2E.reset();
        E2E.ctx().getSharedPreferences("reader", 0).edit().putString("display", "{\"guided\":true,\"vol\":true}").commit();
        E2E.seedNarou(3, 40);
        Activity a = open(1, 0);
        E2E.waitReady(a);
        ReaderActivity r = (ReaderActivity) a;
        int down = android.view.KeyEvent.KEYCODE_VOLUME_DOWN, up = android.view.KeyEvent.KEYCODE_VOLUME_UP;
        // key, then 0 a press, 1 a repeat of it, 2 its release: whether the reader took it.
        java.util.function.BiFunction<Integer, Integer, Boolean> key = (code, what) -> {
            boolean[] got = new boolean[1];
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> got[0] = what == 2
                    ? r.onKeyUp(code, new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                    : r.onKeyDown(code, new android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, what)));
            return got[0];
        };
        assertTrue("taken to turn", key.apply(down, 0));
        assertTrue(key.apply(down, 2));
        E2E.waitFor(a, "__reader.cur()", "1");
        String bars = "document.getElementById('info').classList.contains('on')";
        E2E.tapMiddle();
        E2E.waitFor(a, bars, "true");
        E2E.waitUntil(() -> r.overlayCallback, "the app told of the bars");
        assertFalse("left to the system under the bars", key.apply(down, 0));
        // The bars close while it is held: its repeats and its release still go where its press went (codex on #26).
        E2E.js(a, "__reader.closeOverlays();0");
        E2E.waitUntil(() -> !r.overlayCallback, "the app told they closed");
        assertFalse(key.apply(down, 1));
        assertFalse(key.apply(down, 2));
        assertEquals("1", E2E.js(a, "__reader.cur()"));
        // Each key keeps its own: down held to turn, the bars opened, up pressed and let go there, then down let go.
        assertTrue(key.apply(down, 0));
        E2E.waitFor(a, "__reader.cur()", "2");
        SystemClock.sleep(600);
        E2E.tapMiddle();
        E2E.waitFor(a, bars, "true");
        E2E.waitUntil(() -> r.overlayCallback, "the app told of the bars again");
        assertFalse(key.apply(up, 0));
        assertFalse(key.apply(up, 2));
        assertTrue("down's release, taken as its press was", key.apply(down, 2));
     * R27: 字下げ (as 空行 and 英字) formats the episode again and reloads the page; Aa comes back open on its tab, to go
     * on choosing (it closed). A book opened again does not open Aa.
     */
    @Test
    public void aaStaysOpenAfterAReformat() throws Exception {
        E2E.reset();
        E2E.seedNarou(3, 40);
        Activity a = open(1, 0);
        E2E.waitReady(a);
        String bars = "document.getElementById('info').classList.contains('on')";
        openAa(a, bars);
        E2E.js(a, "window.__before=1;document.querySelector('.ik[data-indent=\"1\"]').click()");
        E2E.waitFor(a, "typeof window.__before", "\"undefined\"");                 // a new page
        E2E.waitReady(a);
        E2E.waitFor(a, "document.getElementById('info').className", "\"on disp\"");
        assertEquals("\"look\"", E2E.js(a, "document.querySelector('#info .tab.on').dataset.tab"));
        assertEquals("true", E2E.js(a, "document.querySelector('.ik[data-indent=\"1\"]').classList.contains('on')"));
        assertTrue(E2E.ctx().getSharedPreferences("reader", 0).getString("display", "").contains("\"indent\":true"));
        // Opened by the app, not a tap: a tab picked at once is taken (codex on #27).
        E2E.js(a, "document.querySelector('#info .tab[data-tab=\"touch\"]').click()");
        assertEquals("\"touch\"", E2E.js(a, "document.querySelector('#info .tab.on').dataset.tab"));
        // Only that page: the next one loaded (an episode picked in the 目次) opens without Aa.
        E2E.js(a, "__reader.closeOverlays();window.__before=1;Android.open('3');0");
        E2E.waitFor(a, "typeof window.__before", "\"undefined\"");
        E2E.waitReady(a);
        SystemClock.sleep(500);
        assertEquals("false", E2E.js(a, bars));
        reader.close();
        a = open(1, 0);
        E2E.waitReady(a);
        SystemClock.sleep(500);
        assertEquals("false", E2E.js(a, bars));
    }

    private static void openAa(Activity a, String bars) throws Exception {
        E2E.tapMiddle();
        E2E.waitFor(a, bars, "true");
        SystemClock.sleep(600);                                      // a click in the bars' first 500 ms is dropped
        E2E.js(a, "document.getElementById('dispb').click()");
    }

    private static void closeAa(Activity a, String bars) throws Exception {
        E2E.js(a, "document.getElementById('dispb').click()");
        E2E.js(a, "document.getElementById('info').click()");
        E2E.waitFor(a, bars, "false");
        SystemClock.sleep(400);
    }

    /** R19b: a 青空文庫 book's sections are its episodes on the page bar, all kept, named as in its 目次. */
    @Test
    public void aBooksSectionsAreItsEpisodesOnTheSlider() throws Exception {
        Guide.add(E2E.ctx());
        reader = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class).putExtra("url", Shelf.episodeUrl(Guide.KEY, 2)).putExtra("page", 0));
        AtomicReference<Activity> r = new AtomicReference<>();
        reader.onActivity(r::set);
        Activity a = r.get();
        E2E.waitReady(a);
        E2E.tapMiddle();
        E2E.waitFor(a, "document.getElementById('info').classList.contains('on')", "true");
        E2E.holdSeekCount(a);
        E2E.waitFor(a, "/^2 \\/ \\d+ 話$/.test(document.getElementById('seekv').textContent)", "true");
        assertEquals("\"いま読んでいる話\"", E2E.js(a, "document.querySelector('#info .epp.on .st').textContent"));
        assertEquals("true", E2E.js(a, "document.querySelector('#info .epp.on .tt').textContent.indexOf('この本について')===0"));
        assertEquals(0, Net.asked.size());
    }
}
