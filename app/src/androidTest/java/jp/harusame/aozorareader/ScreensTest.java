package jp.harusame.aozorareader;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
/**
 * Not part of the E2E run: walks every screen in a light and a dark theme, logging "SHOT <theme>_<screen>" when each is
 * ready, for tools/screen-matrix.sh to capture (docs/release.md, "Screens on many devices"). Runs only when asked for
 * with -e screens 1.
 */
public class ScreensTest {
    static void mark(String n) { android.util.Log.i("SHOT", n); SystemClock.sleep(3500); }

    /** A 目次 of 30 episodes: two before any chapter, then three chapters, long enough to scroll well into one.
     * With chapters false, the same episodes without a chapter. */
    String tocPage(boolean chapters) {
        StringBuilder b = new StringBuilder("<div class=\"p-eplist\">");
        for (int n = 1; n <= 30; n++) {
            String ch = !chapters ? null : n == 3 ? "第一章　はじまり" : n == 6 ? "第二章　旅立ち" : n == 20 ? "第三章　王都" : null;
            if (ch != null) b.append("<div class=\"p-eplist__chapter-title\">").append(ch).append("</div>");
            b.append("<div class=\"p-eplist__sublist\"><a href=\"/").append(E2E.NCODE).append("/").append(n)
                    .append("/\" class=\"p-eplist__subtitle\">第").append(n).append("話　試験</a></div>");
        }
        return b.append("</div>").toString();
    }

    @Test
    public void shots() throws Exception {
        org.junit.Assume.assumeTrue("only with -e screens 1",
                "1".equals(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("screens")));
        String only = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("themes", "kinari,dark");
        for (String t : only.split(",")) {
            E2E.reset();
            E2E.ctx().getSharedPreferences("reader", 0).edit().putString("display", "{\"guided\":true,\"theme\":\"" + t + "\"}").commit();
            E2E.seedNarou(3, 30);
            Guide.add(E2E.ctx());
            Shelf.update(E2E.ctx(), s -> { Shelf.Work w = s.find(E2E.NCODE); w.episodes = 5; w.seen = 3; w.lastEpisode = 2; w.lastRead = System.currentTimeMillis(); w.tags.add("読んでる"); });
            E2E.net.put("https://ncode.syosetu.com/" + E2E.NCODE + "/", tocPage(true).getBytes(StandardCharsets.UTF_8));
            ActivityScenario<ShelfActivity> sh = ActivityScenario.launch(new Intent(E2E.ctx(), ShelfActivity.class));
            mark(t + "_shelf");
            sh.close();
            ActivityScenario<SearchActivity> se = ActivityScenario.launch(new Intent(E2E.ctx(), SearchActivity.class));
            mark(t + "_search");
            se.close();
            ActivityScenario<AozoraActivity> ao = ActivityScenario.launch(new Intent(E2E.ctx(), AozoraActivity.class));
            mark(t + "_aozora");
            ao.close();
            ActivityScenario<ReaderActivity> r = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                    .putExtra("url", Shelf.episodeUrl(Guide.KEY, 5)).putExtra("page", 0));
            AtomicReference<Activity> a = new AtomicReference<>();
            r.onActivity(a::set);
            E2E.waitReady(a.get());
            mark(t + "_page");
            r.close();
            r = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class).putExtra("url", Shelf.episodeUrl(E2E.NCODE, 2)).putExtra("page", 0));
            r.onActivity(a::set);
            E2E.waitReady(a.get());
            E2E.tapMiddle(); SystemClock.sleep(600);
            mark(t + "_bars");
            E2E.js(a.get(), "document.getElementById('workb').click()");
            mark(t + "_work");
            E2E.js(a.get(), "document.getElementById('dispb').click()");
            mark(t + "_aa");
            E2E.js(a.get(), "document.querySelector('#info .sheet.d').scrollTop=9999");
            mark(t + "_aa2");
            // Aa's other tabs: 操作 (the tap zones' editor first) scrolled to its end, and その他
            E2E.js(a.get(), "document.querySelector('#info .tab[data-tab=\\'other\\']').click()");
            mark(t + "_aaother");
            E2E.js(a.get(), "document.querySelector('#info .tab[data-tab=\\'touch\\']').click();document.querySelector('#info .sheet.d').scrollTop=9999");
            mark(t + "_aatouch");
            // The tap zones' editor, then the guide drawn from them
            E2E.js(a.get(), "document.querySelector('#info .sheet.d').scrollTop=0");
            mark(t + "_zones");
            E2E.js(a.get(), "document.querySelector('#info button[data-guide]').click()");
            mark(t + "_guide");
            E2E.js(a.get(), "document.getElementById('guide').click()");
            // With 上端のタップ on the top bar's strip: the guide draws the strip and the top row's labels go below it
            E2E.js(a.get(), "document.querySelector('#info [data-tb=\\'1\\']').click();document.querySelector('#info button[data-guide]').click()");
            mark(t + "_guidetb");
            E2E.js(a.get(), "document.getElementById('guide').click();document.querySelector('#info [data-tb=\\'0\\']').click()");
            E2E.tapMiddle(); SystemClock.sleep(600);
            E2E.js(a.get(), "document.getElementById('dispb').click()");
            E2E.js(a.get(), "document.getElementById('dispb').click();document.getElementById('tocb').click()");
            SystemClock.sleep(1500);
            mark(t + "_toc");
            // Scrolled into the second chapter: its band, not the first one's, stays at the top of the list
            E2E.js(a.get(), "var l=document.querySelector('#toc .ls'),c=l.querySelectorAll('.ch')[1];l.scrollTop+=c.getBoundingClientRect().top-l.getBoundingClientRect().top+300");
            SystemClock.sleep(600);
            mark(t + "_toc2");
            // A work without chapters: a flat list, not indented
            E2E.net.put("https://ncode.syosetu.com/" + E2E.NCODE + "/", tocPage(false).getBytes(StandardCharsets.UTF_8));
            // the 目次 just read is kept for a while; drop it so the flat one is read
            org.junit.Assert.assertTrue("the kept 目次 to drop is not where ReaderActivity.loadToc keeps it",
                    new java.io.File(E2E.ctx().getFilesDir(), "episodes/" + E2E.NCODE + "/toc.json").delete());
            E2E.js(a.get(), "document.getElementById('tocx').click();document.getElementById('tocb').click()");
            SystemClock.sleep(1500);
            mark(t + "_toc3");
            // The slider across the episodes (a long press on its count), and the title page while it moves (the 目次
            // just read gives the titles; episode 4 is not kept)
            E2E.js(a.get(), "document.getElementById('tocx').click()");
            E2E.tapMiddle();
            E2E.waitFor(a.get(), "document.getElementById('info').classList.contains('on')", "true");
            SystemClock.sleep(600);
            E2E.holdSeekCount(a.get());
            mark(t + "_epbar");
            E2E.js(a.get(), "var s=document.getElementById('seek');s.value=4;s.dispatchEvent(new Event('input'))");
            mark(t + "_epp");
            r.close();
            ActivityScenario<LogActivity> l = ActivityScenario.launch(new Intent(E2E.ctx(), LogActivity.class));
            mark(t + "_log");
            l.close();
        }
        // BIZ UD明朝 on the page (the bundled font)
        E2E.reset();
        E2E.ctx().getSharedPreferences("reader", 0).edit().putString("display", "{\"guided\":true,\"font\":\"bizud\"}").commit();
        Guide.add(E2E.ctx());
        ActivityScenario<ReaderActivity> r = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", Shelf.episodeUrl(Guide.KEY, 5)).putExtra("page", 0));
        AtomicReference<Activity> a = new AtomicReference<>();
        r.onActivity(a::set);
        E2E.waitReady(a.get());
        android.util.Log.i("SHOT", "font=" + E2E.js(a.get(), "document.fonts.check('16px \"BIZ UDMincho\"')+' '+[...document.fonts].filter(f=>f.family.indexOf('BIZ')>=0).map(f=>f.status).join(',')"));
        mark("bizud_page");
        r.close();
    }
}
