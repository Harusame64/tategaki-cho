package jp.harusame.aozorareader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.NotificationManager;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.concurrent.Executors;

/** docs/e2e.md C1: the background check asks the API once, updates the shelf and notifies only what is new. */
@RunWith(AndroidJUnit4.class)
public class CheckWorkerTest {
    static final String API = "https://api.syosetu.com/novelapi/api/?out=json&of=n-t-w-ga-nt-e&lim=500&ncode=" + E2E.NCODE;

    @Before
    public void setUp() throws Exception {
        E2E.reset();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("pm grant " + E2E.ctx().getPackageName() + " android.permission.POST_NOTIFICATIONS").close();
        // The command returns before the grant lands: on a fresh install the check would find notifications off
        // and do nothing.
        NotificationManager nm = E2E.ctx().getSystemService(NotificationManager.class);
        E2E.waitUntil(nm::areNotificationsEnabled, "notifications allowed");
        E2E.ctx().getSystemService(NotificationManager.class).cancelAll();
        CheckWorker.clearNews(E2E.ctx());
    }

    /** The shelf last checked long ago, so the background check runs. */
    static void checkedLongAgo() throws Exception {
        Shelf.update(E2E.ctx(), s -> s.checked = 0);
    }

    /** The one notification's title once shown (posting is asynchronous); null when none comes. */
    static String shownTitle(String want) throws Exception {
        NotificationManager nm = E2E.ctx().getSystemService(NotificationManager.class);
        long until = android.os.SystemClock.uptimeMillis() + 5_000;
        String last = null;
        while (android.os.SystemClock.uptimeMillis() < until) {
            StatusBarNotification[] shown = nm.getActiveNotifications();
            last = shown.length == 1 ? shown[0].getNotification().extras.getString("android.title") : null;
            if (want.equals(last)) return last;
            android.os.SystemClock.sleep(100);
        }
        return last;
    }

    ListenableWorker.Result run() {
        return TestWorkerBuilder.from(E2E.ctx(), CheckWorker.class, Executors.newSingleThreadExecutor()).build().doWork();
    }

    @Test
    public void newEpisodesAreCountedAndNotified() throws Exception {
        E2E.seedNarou(3, 5);
        checkedLongAgo();
        E2E.net.put(API, E2E.narouApi(5));
        assertTrue(run() instanceof ListenableWorker.Result.Success);
        assertEquals(Arrays.asList(API), Net.asked);
        assertEquals(5, E2E.work().episodes);
        assertEquals(2, E2E.work().newEpisodes());
        assertEquals("新着 2 話", shownTitle("新着 2 話"));
    }

    @Test
    public void nothingNewNotifiesNothing() throws Exception {
        E2E.seedNarou(3, 5);
        checkedLongAgo();
        E2E.net.put(API, E2E.narouApi(3));
        run();
        assertEquals(0, E2E.ctx().getSystemService(NotificationManager.class).getActiveNotifications().length);
        // and the same new episodes are not notified twice
        E2E.net.put(API, E2E.narouApi(4));
        checkedLongAgo();
        run();
        E2E.ctx().getSystemService(NotificationManager.class).cancelAll();
        checkedLongAgo();
        run();
        assertEquals("already counted at the last check", 0, E2E.ctx().getSystemService(NotificationManager.class).getActiveNotifications().length);
    }

    /** C5: while a check is running, another (the shelf's or this one) is not started. */
    @Test
    public void noSecondCheckWhileOneRuns() throws Exception {
        E2E.seedNarou(3, 5);
        checkedLongAgo();
        E2E.net.put(API, E2E.narouApi(5));
        Shelf.CHECKING.set(true);                                        // as if the shelf's own check were running
        try {
            run();
        } finally {
            Shelf.CHECKING.set(false);
        }
        assertTrue("nothing asked: " + Net.asked, Net.asked.isEmpty());
        assertEquals("the shelf's last check is not moved", 0, Shelf.load(E2E.ctx()).checked);
        assertEquals("no notification", null, shownTitle("—"));
    }

    /** C3: right after a check (the shelf's own), the background check asks nothing. */
    @Test
    public void noCheckSoonAfterTheLast() throws Exception {
        E2E.seedNarou(3, 5);                                             // checked just now
        E2E.net.put(API, E2E.narouApi(5));
        run();
        assertTrue("nothing asked: " + Net.asked, Net.asked.isEmpty());
    }

    /** C4: news not yet looked at adds up across rounds instead of being replaced; opening the shelf clears it. */
    @Test
    public void newsAddsUpUntilTheShelfIsOpened() throws Exception {
        E2E.seedNarou(3, 5);
        checkedLongAgo();
        E2E.net.put(API, E2E.narouApi(4));
        run();
        checkedLongAgo();
        E2E.net.put(API, E2E.narouApi(6));
        run();
        assertEquals("新着 3 話", shownTitle("新着 3 話"));
        // Opening the shelf (its onResume) clears the news and the notification.
        E2E.ctx().startActivity(new android.content.Intent(E2E.ctx(), ShelfActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        E2E.waitUntil(() -> E2E.ctx().getSystemService(NotificationManager.class).getActiveNotifications().length == 0,
                "the notification cancelled when the shelf opened");
        assertTrue(E2E.ctx().getSharedPreferences("reader", 0).getString("news", null) == null);
    }

    /** C6: the shelf's own check asked while another runs waits for it and shows its result. */
    @Test
    public void theShelfsCheckTakesTheRunningOnesResult() throws Exception {
        E2E.seedNarou(3, 5);
        checkedLongAgo();
        Shelf.CHECKING.set(true);                                        // a background round is running …
        java.util.concurrent.atomic.AtomicReference<Shelf> seen = new java.util.concurrent.atomic.AtomicReference<>();
        Thread shelfCheck = new Thread(() -> {
            try {
                Shelf s = Shelf.load(E2E.ctx());
                s.refresh(new java.util.ArrayList<>(s.works), E2E.ctx());
                seen.set(s);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        shelfCheck.start();
        try {
            E2E.waitUntil(() -> shelfCheck.getState() == Thread.State.TIMED_WAITING, "the shelf's check is waiting");
            // … and finishes: it found 5 episodes.
            Shelf.update(E2E.ctx(), s -> { s.find(E2E.NCODE).episodes = 5; s.checked = System.currentTimeMillis(); });
            Shelf.CHECKS_DONE.incrementAndGet();
        } finally {
            Shelf.CHECKING.set(false);                                   // the waiter notices within a second
        }
        shelfCheck.join(5000);
        assertNotNull("the shelf's check returned", seen.get());
        assertEquals("the shelf shows the finished check's count", 5, seen.get().find(E2E.NCODE).episodes);
        assertTrue("and asked nothing itself: " + Net.asked, Net.asked.isEmpty());
    }

    /** C7: a running check that ends without finishing (no check time moved): the shelf's check takes its own turn. */
    @Test
    public void theShelfsCheckRunsWhenTheOtherDidNotFinish() throws Exception {
        E2E.seedNarou(3, 5);
        checkedLongAgo();
        E2E.net.put(API, E2E.narouApi(5));
        Shelf.CHECKING.set(true);
        Thread shelfCheck = new Thread(() -> {
            try {
                Shelf s = Shelf.load(E2E.ctx());
                s.refresh(new java.util.ArrayList<>(s.works), E2E.ctx());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        shelfCheck.start();
        try {
            E2E.waitUntil(() -> shelfCheck.getState() == Thread.State.TIMED_WAITING, "the shelf's check is waiting");
        } finally {
            Shelf.CHECKING.set(false);                                   // ended, having saved nothing
        }
        shelfCheck.join(10_000);
        assertEquals("the shelf asked itself", java.util.Arrays.asList(API), Net.asked);
        assertEquals(5, E2E.work().episodes);
    }

    /** C8: the background check tests the last check again once it has its turn (one may have just ended). */
    @Test
    public void theBackgroundCheckReadsTheTimeAgainWithItsTurn() throws Exception {
        E2E.seedNarou(3, 5);                                             // the file says: checked just now
        E2E.net.put(API, E2E.narouApi(5));
        Shelf stale = Shelf.load(E2E.ctx());
        stale.checked = 0;                                               // what the worker read before its turn
        assertEquals(null, stale.refresh(new java.util.ArrayList<>(stale.works), E2E.ctx(), false, ShelfActivity.CHECK_EVERY_MS, null));
        assertTrue("nothing asked: " + Net.asked, Net.asked.isEmpty());
    }
}
