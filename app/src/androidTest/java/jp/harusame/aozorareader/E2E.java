package jp.harusame.aozorareader;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.webkit.WebView;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the E2E tests share: a clean app (its own build, ….e2e), a shelf and episodes put in place, fixtures
 * the network door answers from (no real site is reached), and reading the reader page through its JS.
 */
final class E2E {
    private E2E() { }

    /** not n0000aa: its first episode's URL is the reader's bundled sample, which never saves or turns on */
    static final String NCODE = "n9999zz";
    static final String TITLE = "試験の作品";
    static final String AUTHOR = "試験作者";
    /** what Net answered, by URL; a URL missing here is "HTTP 404" */
    static final Map<String, byte[]> net = new ConcurrentHashMap<>();

    static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    static UiDevice device() {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    /** Every screen an earlier test left open is closed (a shelf left behind would start its own check). */
    static void finishAll() {
        for (int round = 0; round < 3; round++) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (androidx.test.runner.lifecycle.Stage st : androidx.test.runner.lifecycle.Stage.values())
                    for (Activity a : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(st))
                        if (!a.isFinishing()) a.finish();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(200);
        }
        // A check a closed screen started may still run: it is waited for, not cut off (a stuck flag must show).
        long until = SystemClock.uptimeMillis() + 15_000;
        while (Shelf.CHECKING.get() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
        if (Shelf.CHECKING.get()) throw new AssertionError("a check never finished");
    }

    /** Everything the app keeps is wiped, and the network answers only from {@link #net}. */
    static void reset() {
        finishAll();
        Context c = ctx();
        for (File f : c.getFilesDir().listFiles()) Shelf.deleteTree(f);
        // The first-time tap guide is taken as seen, so taps reach the page (GuideTest starts without this), and
        // the bundled guide book as put on the shelf already, so the shelf holds only what a test puts there
        // (GuideBookTest starts without it).
        c.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear().putString("display", "{\"guided\":true}")
                .putBoolean(Guide.SEEDED, true).commit();
        net.clear();
        Net.asked.clear();
        Net.fake = net::get;
        FullSave.GAP_MS = 0;
        FullSave.ANY_TIME = true;                                          // FullSaveTest F6 runs without it
    }

    /** A なろう episode page as the site serves it: the announce box, the pager and the text. */
    static String narouEpisode(int no, int of, String title, int paragraphs, String extra) {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= paragraphs; i++)
            body.append("<p id=\"L").append(i).append("\">　第").append(no).append("話の").append(i)
                    .append("段落。夜の駅に電車が着いて、ホームの灯りが<ruby>窓<rp>(</rp><rt>まど</rt><rp>)</rp></ruby>に映った。</p>\n");
        String pager = "<div class=\"c-pager\"><a href=\"https://ncode.syosetu.com/" + NCODE + "/\">目次</a>"
                + (no > 1 ? " <a href=\"/" + NCODE + "/" + (no - 1) + "/\">前へ</a>" : "")
                + (no < of ? " <a href=\"/" + NCODE + "/" + (no + 1) + "/\">次へ</a>" : "") + "</div>";
        return "<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"UTF-8\"><title>" + TITLE + "</title></head><body>"
                + "<div class=\"c-announce-box\"><div class=\"c-announce\"><a href=\"/" + NCODE + "/\">" + TITLE + "</a>&nbsp;&nbsp;作者： "
                + "<a href=\"https://mypage.syosetu.com/000000/\">" + AUTHOR + "</a></div></div>"
                + "<div class=\"p-novel\">" + pager + "<div class=\"p-novel__number\">" + no + "/" + of + "</div>"
                + "<h1 class=\"p-novel__title\">" + title + "</h1><div class=\"js-novel-text p-novel__text\">"
                + extra + body + "</div></div></body></html>";
    }

    /** The なろう API's answer for the test work. */
    static byte[] narouApi(int episodes) throws Exception {
        JSONArray a = new JSONArray().put(new JSONObject().put("allcount", 1))
                .put(new JSONObject().put("ncode", NCODE.toUpperCase()).put("title", TITLE).put("writer", AUTHOR)
                        .put("noveltype", 1).put("end", 1).put("general_all_no", episodes));
        return a.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The test work on the shelf with its episodes kept, checked just now (no check runs on opening). */
    static void seedNarou(int episodes, int paragraphs) throws Exception {
        Context c = ctx();
        File dir = new File(c.getFilesDir(), "episodes/" + NCODE);
        dir.mkdirs();
        for (int n = 1; n <= episodes; n++)
            Files.write(new File(dir, n + ".html").toPath(),
                    narouEpisode(n, episodes, "第" + n + "話　試験", paragraphs, "").getBytes(StandardCharsets.UTF_8));
        Shelf.update(c, s -> {
            Shelf.Work w = new Shelf.Work();
            w.ncode = NCODE;
            w.title = TITLE;
            w.author = AUTHOR;
            w.episodes = episodes;
            w.seen = episodes;
            w.lastEpisode = 1;
            w.added = System.currentTimeMillis();
            s.works.add(w);
            s.checked = System.currentTimeMillis();
        });
    }

    /** Runs a script in the reader's page and returns its JSON result ("null" until the page is ready). */
    static String js(Activity a, String expr) throws Exception {
        AtomicReference<String> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            WebView w = ((ReaderActivity) a).web;
            w.evaluateJavascript(expr, v -> { out.set(v); done.countDown(); });
        });
        if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("no answer from the page: " + expr);
        return out.get();
    }

    /** Waits until the script's result equals want (as JSON), or fails with the last one seen. */
    static void waitFor(Activity a, String expr, String want) throws Exception {
        String last = null;
        long until = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < until) {
            last = js(a, expr);
            if (want.equals(last)) return;
            SystemClock.sleep(200);
        }
        throw new AssertionError(expr + " was " + last + ", wanted " + want);
    }

    interface Check { boolean ok() throws Exception; }

    /** Waits until the check holds (a write on another thread), or fails. */
    static void waitUntil(Check c, String what) throws Exception {
        long until = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < until) {
            if (c.ok()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("never: " + what);
    }

    /** The reader is showing an episode: its page and episode number. */
    static void waitReady(Activity a) throws Exception {
        waitFor(a, "!!(window.__reader&&__reader.pages()>0)", "true");
        // Taps are taken only once the reader's window is in front and takes input (not mid-transition
        // from a screen an earlier test closed).
        waitUntil(a::hasWindowFocus, "the reader's window has focus");
        device().waitForIdle();
        SystemClock.sleep(300);
    }

    static int num(Activity a, String expr) throws Exception {
        return (int) Double.parseDouble(js(a, expr));
    }

    /** A tap where a reader taps: the left side turns forward, the right back, the middle shows the bars. */
    static void tapLeft() { UiDevice d = device(); d.click(d.getDisplayWidth() / 8, d.getDisplayHeight() / 2); }
    static void tapRight() { UiDevice d = device(); d.click(d.getDisplayWidth() * 7 / 8, d.getDisplayHeight() / 2); }
    static void tapMiddle() { UiDevice d = device(); d.click(d.getDisplayWidth() / 2, d.getDisplayHeight() / 2); }

    static Shelf.Work work() {
        return Shelf.load(ctx()).find(NCODE);
    }
}
