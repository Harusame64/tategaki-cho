package jp.harusame.aozorareader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import jp.harusame.aozorareader.core.KakuyomuWork;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全話を保存: every episode of one なろう or カクヨム work on the shelf, fetched one at a time with GAP_MS between
 * fetches, while the app is in front (it waits while no screen is shown). Episodes already kept are skipped; taking
 * the work off the shelf or 止める ends it.
 */
final class FullSave {
    private FullSave() {
    }

    /** Whether there is 全話を保存: the personal build only (app/build.gradle). */
    static final boolean ENABLED = BuildConfig.FULL_SAVE;

    /** between two fetches, on top of Net's own spacing (tests set 0) */
    static volatile long GAP_MS = 3_000;

    /** One work at a time, in the order asked. */
    static final ExecutorService RUN = Executors.newSingleThreadExecutor();

    /** work → {episodes kept, episodes in all}, while queued or saving */
    static final Map<String, int[]> PROGRESS = new ConcurrentHashMap<>();
    static final java.util.Set<String> STOP = ConcurrentHashMap.newKeySet();
    /** held around each fetch and its commit, and by 保存した話を消す, so a fetch in flight cannot put back what was cleared */
    static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    static Object lock(String key) {
        return LOCKS.computeIfAbsent(key, k -> new Object());
    }

    /** Called on the main thread after each step, so the shelf can show the count; null when no shelf is shown. */
    static volatile Runnable listener;

    /** screens of the app started and not stopped (App counts them); the save waits while there are none */
    static final java.util.concurrent.atomic.AtomicInteger SHOWN = new java.util.concurrent.atomic.AtomicInteger();
    /** tests that run no screen set this */
    static volatile boolean ANY_TIME;
    private static final Object FRONT = new Object();

    static void wake() {
        synchronized (FRONT) { FRONT.notifyAll(); }
    }

    /** Waits while the app is not in front (or until stopped). */
    static void awaitFront(String key) throws InterruptedException {
        synchronized (FRONT) {
            while (!ANY_TIME && SHOWN.get() <= 0 && !STOP.contains(key)) FRONT.wait(1_000);
        }
    }

    static boolean offered(Shelf.Work w) {
        return ENABLED && !Shelf.isAozora(w.ncode);
    }

    static void start(Context ctx, String key) {
        if (!ENABLED) return;
        Context c = ctx.getApplicationContext();
        if (PROGRESS.putIfAbsent(key, new int[]{0, 0}) != null) return;   // queued or saving already
        STOP.remove(key);
        tell();
        RUN.execute(() -> {
            String msg;
            try {
                msg = run(c, key);
            } catch (Exception e) {
                Log.w(Shelf.TAG, "full save " + key, e);
                msg = "全話を保存できませんでした（" + e.getMessage() + "）";
            } finally {
                PROGRESS.remove(key);
                STOP.remove(key);
                tell();
            }
            String m = msg;
            if (m != null) new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(c, m, Toast.LENGTH_LONG).show());
        });
    }

    static void stop(String key) {
        STOP.add(key);
        wake();
        tell();
    }

    /**
     * Every way of clearing a work's kept episodes goes through here: a save of the work is stopped, and the
     * clearing waits for its fetch in flight, so nothing cleared is fetched back.
     */
    static long prune(Context c, Shelf s, String key, boolean all) {
        stop(key);
        synchronized (lock(key)) { return s.prune(c, key, all); }
    }

    /** @return what to tell the user, or null */
    static String run(Context c, String key) throws Exception {
        Shelf.Work w = Shelf.load(c).find(key);
        if (w == null) return null;
        // Stopped while queued behind another work, or its turn came with the app not in front: not even the
        // カクヨム list is read again until it is.
        // Every request is asked again when its turn comes (it may wait behind the reader's): stopped, or the app
        // left the front, and it is not sent; in the second case it waits and is asked for again.
        java.util.function.BooleanSupplier wanted = () -> !STOP.contains(key) && (ANY_TIME || SHOWN.get() > 0);
        List<String> urls;
        while (true) {
            awaitFront(key);
            if (STOP.contains(key)) return w.title + "：全話の保存を止めました";
            try {
                urls = urls(c, w, wanted);
                break;
            } catch (Net.NotWanted later) {
                // stopped or out of front: looked at again above
            }
        }
        if (urls == null) return null;                                       // taken off the shelf meanwhile
        int[] p = PROGRESS.get(key);
        p[1] = urls.size();
        p[0] = kept(c, urls);
        tell();
        for (String u : urls) {
            if (STOP.contains(key)) return w.title + "：全話の保存を止めました（" + p[0] + " / " + p[1] + " 話）";
            if (Shelf.load(c).find(key) == null) return null;                // taken off the shelf
            if (Episodes.cacheFile(c, u).isFile()) continue;                  // kept already (also by the reader meanwhile)
            if (GAP_MS > 0) Thread.sleep(GAP_MS);
            while (true) {
                awaitFront(key);
                synchronized (lock(key)) {
                    if (STOP.contains(key)) return w.title + "：全話の保存を止めました（" + p[0] + " / " + p[1] + " 話）";
                    try {
                        Episodes.get(c, u, wanted);
                        break;
                    } catch (Net.NotWanted later) {
                        // stopped (caught above on the next turn) or out of front (awaitFront waits)
                    } catch (IllegalStateException off) {                    // taken off the shelf meanwhile
                        return null;
                    }
                }
            }
            p[0] = kept(c, urls);                                           // counted again: the reader may keep some too
            tell();
        }
        return w.title + "：全 " + p[1] + " 話を保存しました";
    }

    static int kept(Context c, List<String> urls) throws Exception {
        int n = 0;
        for (String u : urls) if (Episodes.cacheFile(c, u).isFile()) n++;
        return n;
    }

    /** Every episode of the work, by the URLs the reader keeps them under; null if it left the shelf meanwhile. */
    static List<String> urls(Context c, Shelf.Work w, java.util.function.BooleanSupplier wanted) throws Exception {
        List<String> out = new ArrayList<>();
        if (Shelf.isKakuyomu(w.ncode)) {
            // Read again (the newest list), and kept only if the work is still on the shelf: a removal while the
            // page was coming leaves no list behind.
            KakuyomuWork page = KakuyomuSite.fetchWork(w.ncode.substring(Shelf.KK.length()), wanted);
            JSONObject list;
            synchronized (Shelf.class) {
                if (Shelf.load(c).find(w.ncode) == null) return null;
                list = KakuyomuSite.save(c, w.ncode, page);
            }
            for (int n = 1; ; n++) {
                String id = KakuyomuSite.idAt(list, n);
                if (id == null) break;
                out.add(Shelf.kkEpisodeUrl(w.ncode, id));
            }
        } else if (w.shortStory) {
            out.add(w.startUrl());
        } else {
            for (int n = 1; n <= w.episodes; n++) out.add(Shelf.episodeUrl(w.ncode, n));
        }
        return out;
    }

    static void tell() {
        Runnable l = listener;
        if (l != null) new Handler(Looper.getMainLooper()).post(l);
    }
}
