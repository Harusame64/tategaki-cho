package jp.harusame.aozorareader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/** docs/e2e.md G1–G2: the first-time guide to where a tap turns the page. */
@RunWith(AndroidJUnit4.class)
public class GuideTest {
    ActivityScenario<ReaderActivity> reader;
    static final String ON = "document.getElementById('guide').classList.contains('on')";

    @Before
    public void setUp() throws Exception {
        E2E.reset();
        E2E.ctx().getSharedPreferences("reader", Context.MODE_PRIVATE).edit().remove("display").commit();   // never seen
        E2E.seedNarou(3, 40);
    }

    @After
    public void tearDown() {
        if (reader != null) reader.close();
    }

    Activity open() {
        reader = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", Shelf.episodeUrl(E2E.NCODE, 1)).putExtra("page", 0));
        AtomicReference<Activity> a = new AtomicReference<>();
        reader.onActivity(a::set);
        return a.get();
    }

    /** G1: the first book opens under the guide; a tap closes it without turning, and it does not come back. */
    @Test
    public void shownOnceAndATapClosesItWithoutTurning() throws Exception {
        Activity a = open();
        E2E.waitReady(a);
        E2E.waitFor(a, ON, "true");
        E2E.tapLeft();
        E2E.waitFor(a, ON, "false");
        SystemClock.sleep(400);
        assertEquals("the tap that closed it turned nothing", 0, E2E.num(a, "__reader.cur()"));
        E2E.tapLeft();
        E2E.waitFor(a, "__reader.cur()", "1");
        reader.recreate();
        AtomicReference<Activity> b = new AtomicReference<>();
        reader.onActivity(b::set);
        E2E.waitReady(b.get());
        SystemClock.sleep(400);
        assertEquals("seen once is enough", "false", E2E.js(b.get(), ON));
        assertTrue(E2E.ctx().getSharedPreferences("reader", Context.MODE_PRIVATE).getString("display", "").contains("\"guided\":true"));
    }

    /** G2: Aa's 触る場所 shows it again; Back closes it before the reader. */
    @Test
    public void aaShowsItAgainAndBackClosesIt() throws Exception {
        Activity a = open();
        E2E.waitReady(a);
        E2E.waitFor(a, ON, "true");
        E2E.tapMiddle();
        E2E.waitFor(a, ON, "false");
        E2E.js(a, "document.querySelector('#info button[data-guide]').click()");
        E2E.waitFor(a, ON, "true");
        E2E.device().pressBack();
        E2E.waitFor(a, ON, "false");
        assertTrue("the reader is still open", !a.isFinishing());
    }
}
