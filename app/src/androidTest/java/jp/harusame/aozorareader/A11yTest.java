package jp.harusame.aozorareader;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType;
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult;
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * docs/e2e.md Y1: Google's accessibility checks find no error on the native screens, in a light and a dark theme.
 * Y2: the reader is a web page those checks do not see into, so its touch targets are measured there.
 */
@RunWith(AndroidJUnit4.class)
public class A11yTest {
    ActivityScenario<?> scenario;

    @Before
    public void setUp() throws Exception {
        E2E.reset();
    }

    @After
    public void tearDown() {
        if (scenario != null) scenario.close();
    }

    /** The errors the checks find on the activity's window, one line each. */
    static List<String> errors(Activity a) {
        List<String> out = new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View root = a.getWindow().getDecorView();
            AccessibilityValidator v = new AccessibilityValidator().setRunChecksFromRootView(true).setThrowExceptionForErrors(false);
            for (AccessibilityViewCheckResult r : v.checkAndReturnResults(root)) {
                if (r.getType() != AccessibilityCheckResultType.ERROR) continue;
                View e = r.getView();
                String what = e == null ? "?" : e.getClass().getSimpleName()
                        + (e instanceof android.widget.TextView ? " \"" + ((android.widget.TextView) e).getText() + "\"" : "")
                        + (e.getContentDescription() != null ? " (" + e.getContentDescription() + ")" : "");
                out.add(r.getSourceCheckClass().getSimpleName() + " [" + what + "] " + r.getMessage(Locale.ENGLISH));
            }
        });
        return out;
    }

    <T extends Activity> List<String> screen(Class<T> type, String theme) {
        E2E.ctx().getSharedPreferences("reader", 0).edit().putString("display", "{\"guided\":true,\"theme\":\"" + theme + "\"}").commit();
        if (scenario != null) scenario.close();
        ActivityScenario<T> s = ActivityScenario.launch(new Intent(E2E.ctx(), type));
        scenario = s;
        AtomicReference<Activity> a = new AtomicReference<>();
        s.onActivity(a::set);
        SystemClock.sleep(1500);
        List<String> e = errors(a.get());
        List<String> named = new ArrayList<>();
        for (String x : e) named.add(type.getSimpleName() + "/" + theme + ": " + x);
        return named;
    }

    @Test
    public void nativeScreensPassTheChecks() throws Exception {
        E2E.seedNarou(3, 5);
        Guide.add(E2E.ctx());
        Shelf.update(E2E.ctx(), s -> { Shelf.Work w = s.find(E2E.NCODE); w.episodes = 5; w.lastRead = System.currentTimeMillis(); w.lastEpisode = 2; });
        List<String> all = new ArrayList<>();
        for (String theme : new String[]{"kinari", "dark"}) {
            all.addAll(screen(ShelfActivity.class, theme));
            all.addAll(screen(LogActivity.class, theme));
            all.addAll(screen(AozoraActivity.class, theme));
            all.addAll(screen(SearchActivity.class, theme));
            all.addAll(screen(LicenceActivity.class, theme));
        }
        assertTrue(all.size() + " accessibility errors:\n" + String.join("\n", all), all.isEmpty());
    }

    /** Every button and link shown in the reader's bars, sheets, 目次 and end card that is under 48 x 48 px (= dp in the WebView). */
    static final String SMALL = "(function(){var out=[];document.querySelectorAll('#info button,#info a,#toc button,#toc .ep,#end button,#end a').forEach(function(e){"
            + "var r=e.getBoundingClientRect();if(!r.width||!r.height)return;"
            + "if(r.width<47.5||r.height<47.5)out.push((e.textContent||e.className).trim().slice(0,10)+' '+Math.round(r.width)+'x'+Math.round(r.height));});"
            + "return out.join(' | ');})()";

    @Test
    public void readerTouchTargetsAreFullSize() throws Exception {
        E2E.seedNarou(3, 5);
        StringBuilder toc = new StringBuilder("<div class=\"p-eplist\"><div class=\"p-eplist__chapter-title\">第一章</div>");
        for (int n = 1; n <= 3; n++) toc.append("<div class=\"p-eplist__sublist\"><a href=\"/").append(E2E.NCODE).append("/").append(n)
                .append("/\" class=\"p-eplist__subtitle\">第").append(n).append("話</a></div>");
        E2E.net.put("https://ncode.syosetu.com/" + E2E.NCODE + "/", toc.append("</div>").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ActivityScenario<ReaderActivity> s = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", Shelf.episodeUrl(E2E.NCODE, 2)).putExtra("page", 0));
        scenario = s;
        AtomicReference<Activity> a = new AtomicReference<>();
        s.onActivity(a::set);
        E2E.waitReady(a.get());
        List<String> small = new ArrayList<>();
        E2E.tapMiddle();
        E2E.waitFor(a.get(), "document.getElementById('info').classList.contains('on')", "true");
        SystemClock.sleep(600);                                      // the bars take no click while the tap that opened them is over
        small.add("bars: " + E2E.js(a.get(), SMALL));
        E2E.js(a.get(), "document.getElementById('workb').click()");
        E2E.waitFor(a.get(), "document.getElementById('info').classList.contains('work')", "true");
        small.add("作品: " + E2E.js(a.get(), SMALL));
        E2E.js(a.get(), "document.getElementById('dispb').click()");
        E2E.waitFor(a.get(), "document.getElementById('info').classList.contains('disp')", "true");
        small.add("Aa: " + E2E.js(a.get(), SMALL));
        E2E.js(a.get(), "document.getElementById('dispb').click();document.getElementById('tocb').click()");
        E2E.waitFor(a.get(), "document.getElementById('toc').classList.contains('on')", "true");
        E2E.waitFor(a.get(), "document.querySelectorAll('#toc .ep').length", "3");
        small.add("目次: " + E2E.js(a.get(), SMALL));
        E2E.js(a.get(), "document.getElementById('tocx').click();__reader.end(false,'https://ncode.syosetu.com/" + E2E.NCODE + "/',false)");
        E2E.waitFor(a.get(), "document.getElementById('end').classList.contains('on')", "true");
        small.add("end: " + E2E.js(a.get(), SMALL));
        String all = String.join("\n", small);
        assertTrue("touch targets under 48px:\n" + all, all.replace("\"\"", "").replaceAll("(?m)^[^:]+: $", "").trim().isEmpty());
    }
}
