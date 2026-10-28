package jp.harusame.aozorareader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import jp.harusame.aozorareader.core.KakuyomuWork;

/**
 * docs/e2e.md K1–K4: a カクヨム work whose author inserts or deletes episodes while it is read. The episode
 * shown, "next", a 目次 jump and the saved place follow the episode itself, not its number. No real site is
 * reached: the work page and the episode pages are fixtures.
 */
@RunWith(AndroidJUnit4.class)
public class KakuyomuTest {
    static final String WORK = "16810000000000001";
    static final String KEY = Shelf.KK + WORK;
    static final String A = "16810000000000101", B = "16810000000000102", C = "16810000000000103", X = "16810000000000109";
    ActivityScenario<ReaderActivity> reader;

    @Before
    public void setUp() throws Exception {
        E2E.reset();
        KakuyomuSite.ASKED.clear();
        for (String[] e : new String[][]{{A, "A"}, {B, "B"}, {C, "C"}, {X, "X"}})
            E2E.net.put(KakuyomuSite.workPage(WORK) + "/episodes/" + e[0], episodePage(e[1]).getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void tearDown() {
        if (reader != null) reader.close();
    }

    /** The work page as カクヨム serves it: its details and its episodes in order, in __NEXT_DATA__. */
    static String workPage(String... ids) {
        StringBuilder refs = new StringBuilder(), eps = new StringBuilder();
        for (String id : ids) {
            refs.append(refs.length() == 0 ? "" : ",").append("{\"__ref\":\"Episode:").append(id).append("\"}");
            eps.append(",\"Episode:").append(id).append("\":{\"__typename\":\"Episode\",\"id\":\"").append(id)
                    .append("\",\"title\":\"題").append(letter(id)).append("\"}");
        }
        return "<html><head><title>試験のカクヨム - カクヨム</title></head><body>"
                + "<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":{\"__APOLLO_STATE__\":{"
                + "\"Work:" + WORK + "\":{\"__typename\":\"Work\",\"id\":\"" + WORK + "\",\"title\":\"試験のカクヨム\",\"serialStatus\":\"RUNNING\","
                + "\"author\":{\"__ref\":\"UserAccount:7\"},\"tableOfContentsV2\":[{\"__ref\":\"TableOfContentsChapter:1\"}]},"
                + "\"UserAccount:7\":{\"__typename\":\"UserAccount\",\"name\":\"tester\",\"activityName\":\"試験作者\"},"
                + "\"TableOfContentsChapter:1\":{\"episodeUnions\":[" + refs + "]}" + eps
                + "}}},\"query\":{\"workId\":\"" + WORK + "\"}}</script></body></html>";
    }

    static String letter(String id) {
        return id.equals(A) ? "A" : id.equals(B) ? "B" : id.equals(C) ? "C" : "X";
    }

    static String episodePage(String l) {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 40; i++)
            body.append("<p>本文").append(l).append("の").append(i).append("段落。夜の駅に電車が着いて、ホームの灯りが窓に映った。</p>");
        return "<html><head><title>題" + l + " - 試験のカクヨム - カクヨム</title></head><body>"
                + "<p class=\"widget-episodeTitle\">題" + l + "</p><div class=\"widget-episodeBody\">" + body + "</div></body></html>";
    }

    /** The author's list changes, as a check or a 目次 refresh would find it. */
    static void listBecomes(String... ids) throws Exception {
        E2E.net.put(KakuyomuSite.workPage(WORK), workPage(ids).getBytes(StandardCharsets.UTF_8));
        KakuyomuSite.save(E2E.ctx(), KEY, KakuyomuWork.fromPage(workPage(ids)));
    }

    /** The work on the shelf, added by sharing episode at (as a reader would), with the list A, B, C. */
    static void addAt(String at) throws Exception {
        E2E.net.put(KakuyomuSite.workPage(WORK), workPage(A, B, C).getBytes(StandardCharsets.UTF_8));
        assertNotNull(Shelf.add(E2E.ctx(), KakuyomuSite.workPage(WORK) + "/episodes/" + at));
        Shelf.update(E2E.ctx(), s -> s.checked = System.currentTimeMillis());
    }

    Activity openFromShelf() throws Exception {
        Shelf.Work w = Shelf.load(E2E.ctx()).find(KEY);
        reader = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", w.startUrl()).putExtra("page", w.lastPage));
        AtomicReference<Activity> a = new AtomicReference<>();
        reader.onActivity(a::set);
        E2E.waitReady(a.get());
        return a.get();
    }

    static void showing(Activity a, String l) throws Exception {
        try {
            // The formatter makes the letter full-width and the number a kanji numeral.
            String mark = "本文" + (char) (l.charAt(0) + 0xFEE0) + "の一段落";
            E2E.waitFor(a, "document.body.textContent.indexOf('" + mark + "')>=0", "true");
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + " / page: " + E2E.js(a, "JSON.stringify(INFO)+' | '+document.body.textContent.slice(0,300)"));
        }
    }

    static void turnPastTheEnd(Activity a) throws Exception {
        E2E.js(a, "__reader.go(__reader.pages()-1)");
        E2E.waitFor(a, "__reader.cur()===__reader.pages()-1", "true");
        E2E.tapLeft();
    }

    /** K1: an episode inserted before the one read; "next" opens the episode after it, and the place follows. */
    @Test
    public void nextAfterAnInsertBefore() throws Exception {
        addAt(B);
        Activity a = openFromShelf();
        showing(a, "B");
        listBecomes(X, A, B, C);
        turnPastTheEnd(a);
        showing(a, "C");
        E2E.waitUntil(() -> Shelf.load(E2E.ctx()).find(KEY).lastEpisode == 4, "the place is episode 4 (C) of the new list");
    }

    /** A work page whose 目次 has 第一章 (A, B), then a section with no chapter (C). */
    static String sectionedWorkPage() {
        String p = workPage(A, B, C);
        return p.replace("\"tableOfContentsV2\":[{\"__ref\":\"TableOfContentsChapter:1\"}]",
                        "\"tableOfContentsV2\":[{\"__ref\":\"TableOfContentsChapter:1\"},{\"__ref\":\"TableOfContentsChapter:2\"}]")
                .replace("\"TableOfContentsChapter:1\":{\"episodeUnions\":[{\"__ref\":\"Episode:" + A + "\"},{\"__ref\":\"Episode:" + B + "\"},{\"__ref\":\"Episode:" + C + "\"}]}",
                        "\"TableOfContentsChapter:1\":{\"chapter\":{\"__ref\":\"Chapter:1\"},\"episodeUnions\":[{\"__ref\":\"Episode:" + A + "\"},{\"__ref\":\"Episode:" + B + "\"}]},"
                        + "\"Chapter:1\":{\"title\":\"第一章\"},"
                        + "\"TableOfContentsChapter:2\":{\"chapter\":null,\"episodeUnions\":[{\"__ref\":\"Episode:" + C + "\"}]}");
    }

    /** K1b: in the 目次, a section with no chapter after 第一章 is not filed under it (not in its box, not indented). */
    @Test
    public void aSectionWithNoChapterIsNotUnderTheOneBefore() throws Exception {
        addAt(A);
        E2E.net.put(KakuyomuSite.workPage(WORK), sectionedWorkPage().getBytes(StandardCharsets.UTF_8));
        KakuyomuSite.save(E2E.ctx(), KEY, KakuyomuWork.fromPage(sectionedWorkPage()));
        Activity a = openFromShelf();
        showing(a, "A");
        E2E.js(a, "document.getElementById('tocb').click()");
        E2E.waitFor(a, "document.querySelectorAll('#toc .ep').length", "3");
        assertEquals("\"第一章:題A,題B\"", E2E.js(a, "Array.from(document.querySelectorAll('#toc .sec')).map(function(s){"
                + "return s.querySelector('.ch').textContent+':'+Array.from(s.querySelectorAll('.ep')).map(function(e){return e.textContent}).join()}).join('|')"));
        assertEquals("\"題C\"", E2E.js(a, "Array.from(document.querySelectorAll('#toc .ls>.ep')).map(function(e){return e.textContent}).join()"));
    }

    /** K2: the list changes while the 目次 is open; the episode tapped is the one opened. */
    @Test
    public void aTocJumpAfterTheListChanged() throws Exception {
        addAt(B);
        Activity a = openFromShelf();
        showing(a, "B");
        E2E.js(a, "document.getElementById('tocb').click()");
        E2E.waitFor(a, "document.querySelectorAll('#toc .ep').length", "3");
        listBecomes(X, A, B, C);
        E2E.js(a, "Array.from(document.querySelectorAll('#toc .ep')).find(function(e){return e.textContent==='題C'}).click()");
        showing(a, "C");
    }

    /** K3: the episode read is deleted; "next" opens the one that followed it. */
    @Test
    public void nextAfterTheEpisodeReadIsDeleted() throws Exception {
        addAt(B);
        Activity a = openFromShelf();
        showing(a, "B");
        listBecomes(A, C);
        turnPastTheEnd(a);
        showing(a, "C");
    }

    /** K4: an episode inserted before the place; opened from the shelf, the same episode comes back. */
    @Test
    public void reopenedAfterAnInsertBefore() throws Exception {
        addAt(B);
        listBecomes(X, A, B, C);
        Activity a = openFromShelf();
        showing(a, "B");
        assertEquals("3", E2E.js(a, "String(INFO.no)").replace("\"", ""));
    }

    /** K5: a place an earlier build kept as a number opens the same episode, and is kept by its id from then on. */
    @Test
    public void aNumberedPlaceFromAnEarlierBuild() throws Exception {
        addAt(B);
        Shelf.update(E2E.ctx(), s -> { Shelf.Work w = s.find(KEY); w.lastEpisodeId = null; w.lastEpisode = 2; });
        assertEquals("kakuyomu://" + WORK + "/2", Shelf.load(E2E.ctx()).find(KEY).startUrl());
        Activity a = openFromShelf();
        showing(a, "B");
        E2E.tapLeft();
        E2E.waitUntil(() -> B.equals(Shelf.load(E2E.ctx()).find(KEY).lastEpisodeId), "the place is kept by B's id");
        listBecomes(X, A, B, C);
        assertEquals(B, Shelf.load(E2E.ctx()).find(KEY).lastEpisodeId);
        assertEquals(3, Shelf.load(E2E.ctx()).find(KEY).lastEpisode);
    }

    /** K6: a shared カクヨム episode URL of a work on the shelf opens that episode. */
    @Test
    public void aSharedEpisodeOpensIt() throws Exception {
        addAt(A);
        E2E.ctx().startActivity(new Intent(Intent.ACTION_SEND).setClassName(E2E.ctx(), ShelfActivity.class.getName())
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, KakuyomuSite.workPage(WORK) + "/episodes/" + C)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        E2E.waitUntil(() -> C.equals(Shelf.load(E2E.ctx()).find(KEY).lastEpisodeId), "the place moved to C");
        Activity r = ShelfFlowTest.resumed(ReaderActivity.class);
        E2E.waitReady(r);
        showing(r, "C");
    }

    /** K7: the place's episode is deleted before it is opened; its neighbours are those either side of where it was. */
    @Test
    public void thePlacesEpisodeDeletedBeforeOpening() throws Exception {
        addAt(B);
        Activity a = openFromShelf();
        showing(a, "B");                                                 // kept here
        reader.close();
        reader = null;
        listBecomes(A, C);
        a = openFromShelf();
        showing(a, "B");
        turnPastTheEnd(a);
        showing(a, "C");
        E2E.js(a, "__reader.go(0)");
        E2E.waitFor(a, "__reader.cur()", "0");
        E2E.tapRight();
        showing(a, "A");
    }

    /** K8: the next episode was prepared, then the list changed; turning into it shows its number now. */
    @Test
    public void aPreparedEpisodeShowsItsNumberNow() throws Exception {
        addAt(B);
        Activity a = openFromShelf();
        showing(a, "B");
        E2E.waitUntil(() -> ((ReaderActivity) a).nextReady != null, "C is prepared ahead");
        listBecomes(X, A, B, C);
        turnPastTheEnd(a);
        showing(a, "C");
        E2E.waitFor(a, "String(INFO.no)+'/'+INFO.number", "\"4/4/4\"");
    }

    /** K9: the episode read and the one after it are deleted while it is open; "next" opens the one after where it was. */
    @Test
    public void nextAfterTheEpisodeReadAndItsNextAreDeleted() throws Exception {
        E2E.net.put(KakuyomuSite.workPage(WORK), workPage(A, B, C, X).getBytes(StandardCharsets.UTF_8));
        assertNotNull(Shelf.add(E2E.ctx(), KakuyomuSite.workPage(WORK) + "/episodes/" + B));
        Shelf.update(E2E.ctx(), s -> s.checked = System.currentTimeMillis());
        Activity a = openFromShelf();
        showing(a, "B");
        listBecomes(A, X);
        turnPastTheEnd(a);
        showing(a, "X");
    }

    /** K7b: the place's episode deleted and never kept here (its page is gone): "next" from the error opens the one after. */
    @Test
    public void thePlacesEpisodeDeletedAndNeverKept() throws Exception {
        addAt(B);
        listBecomes(A, C);
        E2E.net.remove(KakuyomuSite.workPage(WORK) + "/episodes/" + B);
        Activity a = openFromShelf();
        E2E.waitFor(a, "document.body.textContent.indexOf('読み込めませんでした')>=0", "true");
        E2E.tapLeft();
        showing(a, "C");
    }

    /** K6b: sharing an episode newer than the kept list, of a work on the shelf, opens that episode. */
    @Test
    public void aSharedNewEpisodeOpensIt() throws Exception {
        addAt(A);
        E2E.net.put(KakuyomuSite.workPage(WORK), workPage(A, B, C, X).getBytes(StandardCharsets.UTF_8));
        E2E.ctx().startActivity(new Intent(Intent.ACTION_SEND).setClassName(E2E.ctx(), ShelfActivity.class.getName())
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, KakuyomuSite.workPage(WORK) + "/episodes/" + X)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        E2E.waitUntil(() -> X.equals(Shelf.load(E2E.ctx()).find(KEY).lastEpisodeId), "the place moved to X");
        Activity r = ShelfFlowTest.resumed(ReaderActivity.class);
        E2E.waitReady(r);
        showing(r, "X");
    }

    /** K10: the page bar across the episodes follows the kept list by id: its titles, and letting go on 題C opens C. */
    @Test
    public void theEpisodeSliderOpensByIdFromTheKeptList() throws Exception {
        addAt(B);
        Activity a = openFromShelf();
        showing(a, "B");
        E2E.tapMiddle();
        E2E.waitFor(a, "document.getElementById('info').classList.contains('on')", "true");
        E2E.holdSeekCount(a);
        E2E.waitFor(a, "document.getElementById('seekv').textContent", "\"2 / 3 話\"");
        E2E.js(a, "var s=document.getElementById('seek');s.value=3;s.dispatchEvent(new Event('input'))");
        assertEquals("\"題C\"", E2E.js(a, "document.querySelector('#info .epp.on .tt').textContent"));
        E2E.js(a, "document.getElementById('seek').dispatchEvent(new Event('change'))");
        showing(a, "C");
    }
}
