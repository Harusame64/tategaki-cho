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
