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

/** docs/e2e.md A1: Google's accessibility checks find no error on the native screens, in a light and a dark theme. */
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
        }
        assertTrue(all.size() + " accessibility errors:\n" + String.join("\n", all), all.isEmpty());
    }
}
