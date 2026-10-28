package jp.harusame.aozorareader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/** docs/e2e.md B1–B4: the bundled guide book, and はじめかた on a shelf with nothing else. */
@RunWith(AndroidJUnit4.class)
public class GuideBookTest {
    ActivityScenario<?> scenario;

    @Before
    public void setUp() {
        E2E.reset();
        E2E.ctx().getSharedPreferences("reader", Context.MODE_PRIVATE).edit().remove(Guide.SEEDED).commit();   // a new install
    }

    @After
    public void tearDown() {
        if (scenario != null) scenario.close();
    }

    /** B1: a new shelf holds the guide book, and はじめかた offers the ways to add a work. */
    /** The sections kept from an older guide.txt are dropped at start, so an update's new pages show. */
    @Test
    public void sectionsKeptFromAnOlderGuideAreFormattedAgain() throws Exception {
        java.io.File kept = AozoraSite.sectionsFile(E2E.ctx(), Guide.KEY);
        kept.getParentFile().mkdirs();
        java.nio.file.Files.write(kept.toPath(), "[\"古い説明\"]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        E2E.ctx().getSharedPreferences("reader", Context.MODE_PRIVATE).edit().putString(Guide.FORMATTED, "old").commit();
        Guide.ensure(E2E.ctx());
        org.junit.Assert.assertFalse("kept from the older text", kept.isFile());
        // The same text at the next start: what was formatted from it is kept.
        java.nio.file.Files.write(kept.toPath(), "[\"今の説明\"]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Guide.ensure(E2E.ctx());
        org.junit.Assert.assertTrue("formatted from the same text", kept.isFile());
    }

    @Test
    public void aNewShelfHoldsTheGuideAndHowToStart() throws Exception {
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), ShelfActivity.class));
        assertNotNull(E2E.device().wait(Until.findObject(By.text(Guide.TITLE)), 5_000));
        assertNotNull(E2E.device().wait(Until.findObject(By.text("はじめかた")), 5_000));
        assertNotNull(E2E.device().findObject(By.text("青空文庫から探す")));
        assertNotNull(Shelf.load(E2E.ctx()).find(Guide.KEY));
        assertEquals("nothing asked of any site", 0, Net.asked.size());
    }

    /** B2: the guide book opens without a request, with a 目次 and no links to a site. */
    @Test
    public void theGuideOpensOfflineWithoutSiteLinks() throws Exception {
        Guide.ensure(E2E.ctx());
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", Shelf.episodeUrl(Guide.KEY, 2)).putExtra("page", 0));
        AtomicReference<Activity> a = new AtomicReference<>();
        ((ActivityScenario<ReaderActivity>) scenario).onActivity(a::set);
        E2E.waitReady(a.get());
        E2E.waitFor(a.get(), "document.getElementById('book').textContent.indexOf('この本について')>=0", "true");
        assertEquals("0", E2E.js(a.get(), "document.querySelectorAll('#info .head a').length"));
        assertEquals("true", E2E.js(a.get(), "getComputedStyle(document.getElementById('tocb')).display!=='none'"));
        assertEquals(0, Net.asked.size());
    }

    /** B4: the end of the guide book offers no link to a site. */
    @Test
    public void theGuidesEndCardHasNoSiteLink() throws Exception {
        Guide.ensure(E2E.ctx());
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), ReaderActivity.class)
                .putExtra("url", Shelf.episodeUrl(Guide.KEY, 999)).putExtra("page", 0));   // past the last: the last section
        AtomicReference<Activity> a = new AtomicReference<>();
        ((ActivityScenario<ReaderActivity>) scenario).onActivity(a::set);
        E2E.waitReady(a.get());
        E2E.js(a.get(), "__reader.go(__reader.pages()-1)");
        E2E.tapLeft();
        E2E.waitFor(a.get(), "document.getElementById('end').classList.contains('on')", "true");
        assertEquals("0", E2E.js(a.get(), "document.querySelectorAll('#end a').length"));
        assertEquals(0, Net.asked.size());
    }

    /** B3: taken off, the guide stays off at the next start, and ＋ puts it back. */
    @Test
    public void takenOffItStaysOffAndComesBackFromPlus() throws Exception {
        Guide.ensure(E2E.ctx());
        Shelf.remove(E2E.ctx(), java.util.Collections.singleton(Guide.KEY));
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), ShelfActivity.class));
        assertNotNull(E2E.device().wait(Until.findObject(By.text("はじめかた")), 5_000));
        assertNull("not put back by itself", Shelf.load(E2E.ctx()).find(Guide.KEY));
        E2E.device().wait(Until.findObject(By.desc("作品を足す")), 5_000).click();
        E2E.device().wait(Until.findObject(By.text("使い方の本を本棚に戻す")), 5_000).click();
        assertNotNull(E2E.device().wait(Until.findObject(By.text(Guide.TITLE)), 5_000));
        assertNotNull(Shelf.load(E2E.ctx()).find(Guide.KEY));
    }
}
