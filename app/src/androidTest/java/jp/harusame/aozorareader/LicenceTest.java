package jp.harusame.aozorareader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** docs/e2e.md L1–L2: the licence screen, reached from the shelf's ⋮, carries the repository's notices. */
@RunWith(AndroidJUnit4.class)
public class LicenceTest {
    ActivityScenario<?> scenario;

    @Before
    public void setUp() throws Exception {
        E2E.reset();
    }

    @After
    public void tearDown() {
        if (scenario != null) scenario.close();
    }

    static String asset(String path) throws Exception {
        try (InputStream in = E2E.ctx().getAssets().open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** L1: every text listed is in the APK: the GPL notice, the GPL itself, the notices and each licence they name. */
    @Test
    public void everyListedTextIsInTheApk() throws Exception {
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), LicenceActivity.class));
        AtomicReference<Activity> a = new AtomicReference<>();
        scenario.onActivity(a::set);
        List<String[]> entries = ((LicenceActivity) a.get()).entries();
        assertTrue("NOTICE, notices, GPL and at least the Apache, MIT and font licences: " + entries.size(), entries.size() >= 8);
        for (String[] e : entries) assertFalse(e[1] + " is empty", asset(e[1]).trim().isEmpty());
        assertTrue(asset("licences/NOTICE").contains("Copyright (C) 2026 Harusame64"));
        assertTrue(asset("licences/LICENSE").startsWith("                    GNU GENERAL PUBLIC LICENSE"));
        // Every licence file the notices point to under licenses/ ships.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("licenses/([\\w.-]+\\.txt)").matcher(asset("licences/THIRD-PARTY-NOTICES.txt"));
        int named = 0;
        while (m.find()) { named++; assertFalse(m.group(1), asset("licences/" + m.group(1)).isEmpty()); }
        assertTrue("the notices name their licence files", named >= 3);
    }

    /** L2: from the shelf's ⋮, the list opens a text in full, and Back returns to the list, then to the shelf. */
    @Test
    public void theShelfMenuOpensTheLicencesAndBackReturns() throws Exception {
        scenario = ActivityScenario.launch(new Intent(E2E.ctx(), ShelfActivity.class));
        UiDevice d = E2E.device();
        d.wait(Until.findObject(By.desc("その他")), 5000).click();
        d.wait(Until.findObject(By.text("ライセンス")), 5000).click();
        assertNotNull("the source is named", d.wait(Until.findObject(By.text(LicenceActivity.SOURCE)), 5000));
        d.findObject(By.text("使っているライブラリとフォント")).click();
        assertNotNull("the notices in full", d.wait(Until.findObject(By.textContains("THIRD-PARTY SOFTWARE NOTICES")), 5000));
        d.pressBack();
        assertNotNull("Back returns to the list", d.wait(Until.findObject(By.text("GNU General Public License v3")), 5000));
        d.pressBack();
        assertNotNull("and then to the shelf", d.wait(Until.findObject(By.desc("その他")), 5000));
        assertEquals(null, d.findObject(By.text("GNU General Public License v3")));
    }
}
