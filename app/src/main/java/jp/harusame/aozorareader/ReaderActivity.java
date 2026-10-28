package jp.harusame.aozorareader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


import jp.harusame.aozorareader.core.AozoraFormatter;
import jp.harusame.aozorareader.core.EpisodeFormatter;
import jp.harusame.aozorareader.core.EpisodeInfo;
import jp.harusame.aozorareader.core.Toc;

/** Spike: an episode's HTML → aozora's formatter (core) → vertical XHTML in a WebView, one screen at a time. */
public class ReaderActivity extends Activity {
    static final String TAG = "AozoraReader";
    static final String SAMPLE_URL = "https://ncode.syosetu.com/n0000aa/1/";
    /** Waits before reading the next episode ahead, so requests stay one at a time and spaced out. */
    static final int PREFETCH_DELAY_S = 5;
    /** the most episodes read ahead (Aa: 1, 3, 5, 15, 20) */
    static final int MAX_AHEAD = 20;
    /** Between the pages of a 目次, which is read only when the user opens it. */
    static final int TOC_PAGE_DELAY_MS = 1000;
    /** A 目次 kept this long is shown without reading it again. */
    static final long TOC_FRESH_MS = 24L * 60 * 60 * 1000;
    WebView web;
    SharedPreferences prefs;
    /** Opening, turning and formatting episodes, and the 目次; read-ahead has its own worker (ahead). */
    final ScheduledExecutorService work = worker();
    /**
     * Read-ahead fetches and formats here, not on work: a slow fetch on a weak signal does not hold up turning
     * into an episode already prepared. Its requests still go through Net, one at a time.
     */
    final ScheduledExecutorService ahead = worker();
    /** Saves the place off the page's thread, the latest one only (see Bridge.page). */
    final java.util.concurrent.ExecutorService saver = Executors.newSingleThreadExecutor();

    /** Runs on e unless the reader has gone (a bridge call can arrive while onDestroy shuts e down). */
    static void run(java.util.concurrent.ExecutorService e, Runnable r) {
        try { e.execute(r); } catch (java.util.concurrent.RejectedExecutionException gone) { /* the reader has gone */ }
    }

    static void later(ScheduledExecutorService e, Runnable r, long delay, TimeUnit unit) {
        try { e.schedule(r, delay, unit); } catch (java.util.concurrent.RejectedExecutionException gone) { /* the reader has gone */ }
    }

    /** One thread; delayed tasks are dropped once the reader is gone. */
    static ScheduledExecutorService worker() {
        java.util.concurrent.ScheduledThreadPoolExecutor e = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
        e.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return e;
    }
    volatile EpisodeInfo current;
    volatile int readAheadGen;
    /** One toast at a time: a new one replaces the last instead of queueing behind it. */
    Toast toast;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);                                     // the page is given its text; it opens nothing
        s.setBlockNetworkLoads(true);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setWebViewClient(new WebViewClient() {
            // Links to the original site open in the browser; the reader itself never goes online.
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // Only a link the reader tapped opens the browser, never a navigation script started.
                Uri u = r.getUrl();
                if ("https".equals(u.getScheme()) && r.hasGesture()) startActivity(new Intent(Intent.ACTION_VIEW, u));
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        // The page sits below the status bar when it is shown, and fills the screen when it is not.
        FrameLayout root = new FrameLayout(this);
        root.addView(web);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            v.setPadding(0, in.getInsets(WindowInsets.Type.statusBars()).top, 0, 0);
            return in;
        });
        setContentView(root);
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        applyWindowBackground(prefs.getString("display", null));
        // An episode from the shelf, at its saved page; without one, the bundled sample.
        String url = getIntent().getStringExtra("url");
        int start = getIntent().getIntExtra("page", 0);
        // Made again (a system setting changed, or the process came back): go on from where the shelf says the
        // work was left, not from where it was first opened.
        if (savedInstanceState != null && url != null) {
            String key = Shelf.ncodeOf(url);
            Shelf.Work w = key == null ? null : Shelf.load(this).find(key);
            if (w != null && w.lastEpisode > 0 && !w.shortStory) { url = w.startUrl(); start = w.lastPage; }
        }
        String u0 = url;
        int s0 = start;
        run(work, () -> load(u0, s0));
    }

    @Override
    protected void onPause() {
        super.onPause();
        run(saver, this::savePlace);                                       // the last place, before the app may be killed
    }

    /** Kindle's option: the volume keys turn pages (down = next), when chosen in the settings. */
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (volumeTurns() && (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP)) {
            web.evaluateJavascript("window.__reader&&__reader.key(" + (code == KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1) + ")", null);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (volumeTurns() && (code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP)) return true;
        return super.onKeyUp(code, e);
    }

    boolean volumeTurns() {
        try {
            String d = prefs.getString("display", null);
            return d != null && new JSONObject(d).optBoolean("vol", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    void say(String text) {
        runOnUiThread(() -> {
            if (toast != null) toast.cancel();
            toast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
            toast.show();
        });
    }

    @Override
    protected void onDestroy() {
        // No interrupt: a worker may be reading the shelf. The place still to save is saved first.
        work.shutdown();
        ahead.shutdown();
        saver.shutdown();
        web.destroy();
        super.onDestroy();
    }

    /** Back closes the bars, the 目次 or the end card first; the reader closes when none is open. */
    final android.window.OnBackInvokedCallback closeOverlay =
            () -> web.evaluateJavascript("window.__reader&&__reader.closeOverlays()", null);
    boolean overlayCallback;

    void overlay(boolean open) {
        runOnUiThread(() -> {
            if (open == overlayCallback) return;
            overlayCallback = open;
            if (open) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeOverlay);
            else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(closeOverlay);
        });
    }

    /** What the page calls: turning past either end of an episode, and the page now shown. */
    class Bridge {
        @JavascriptInterface
        public void next() {
            EpisodeInfo c = current;
            if (c == null || SAMPLE_URL.equals(c.url)) { say("見本はここまでです"); return; }
            String to = neighbour(1);
            if (END.equals(to)) { finished(c); return; }                    // found by id, and it is the newest
            if (to == null) to = c.nextUrl != null ? c.nextUrl : laterEpisode(c.url);
            if (to == null) { finished(c); return; }
            move(to, 0, null);
        }

        /** Back to the shelf, from the reader's bar or the card at the end of a work. */
        @JavascriptInterface
        public void shelf() {
            runOnUiThread(() -> {
                startActivity(new Intent(ReaderActivity.this, ShelfActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            });
        }

        @JavascriptInterface
        public void prev() {
            EpisodeInfo c = current;
            if (c != null && SAMPLE_URL.equals(c.url)) return;
            String to = neighbour(-1);
            if (END.equals(to)) { say("これが最初の話です"); return; }
            move(to != null ? to : c == null ? null : c.prevUrl, -1, "これが最初の話です");
        }

        /** The 目次 was closed: a なろう 目次 still being read stops. */
        @JavascriptInterface
        public void tocClosed() { tocGen++; }

        @JavascriptInterface
        public void page(int n, int total, String url) {
            java.util.List<Integer> rec = reported;
            if (rec != null) rec.add(n);
            Prepared p = shown;
            // Only the episode on screen, and never an error page, moves the saved place.
            if (p == null || p.failed || p.ep == null || p.url == null || !p.url.equals(url) || SAMPLE_URL.equals(p.url)) return;
            Object[] ep = Shelf.episodeOf(p.url);
            String[] kk = Shelf.kkEpisodeOf(p.url);
            String ncode = ep != null ? (String) ep[0] : Shelf.ncodeOf(p.url);
            if (ncode == null) return;
            // The page waits for this call, so the shelf is written on the saver: a drag of the page bar makes
            // many calls, and only the latest place is written. カクヨム: the place is the episode's id; its
            // number is worked out when it is written.
            place.set(new Object[]{ncode, ep != null ? (Integer) ep[1] : 0, n, total, kk == null ? null : kk[1]});
            run(saver, ReaderActivity.this::savePlace);
        }

        /** An overlay (the bars, the 目次, the end card) opened or closed: Back closes it first. */
        @JavascriptInterface
        public void overlay(boolean open) { ReaderActivity.this.overlay(open); }

        /** The display settings as the page keeps them (theme, text size), as JSON. */
        @JavascriptInterface
        public void savePrefs(String json) {
            prefs.edit().putString("display", json).apply();
            applyWindowBackground(json);
        }

        /** Formats the episode shown again (after the 空行, 字下げ or 英字 setting changed), at the same share read. */
        @JavascriptInterface
        public void reformat(double share) {
            Prepared p = shown;
            if (p == null) return;
            nextReady = prevReady = null;
            shown = null;
            run(work, () -> load(p.url, 0, share));
        }

        /** The work's 目次, delivered to the page as it is read (see loadToc). */
        @JavascriptInterface
        public void toc() {
            EpisodeInfo c = current;
            String ncode = c == null || SAMPLE_URL.equals(c.url) ? null : Shelf.ncodeOf(c.url);
            if (ncode == null || (Shelf.episodeOf(c.url) == null && Shelf.kkEpisodeOf(c.url) == null)) { deliverToc("null"); return; }
            int gen = ++tocGen;
            if (Shelf.isKakuyomu(ncode)) run(work, () -> kakuyomuToc(ncode));
            else if (Shelf.isAozora(ncode)) run(work, () -> aozoraToc(ncode));
            else run(work, () -> loadToc(gen, ncode));
        }

        /** The work's episodes for the page bar's 話 range, as JSON (see {@link #episodeList}); "null" when there are none. */
        @JavascriptInterface
        public String episodes() {
            EpisodeInfo c = current;
            if (c == null || SAMPLE_URL.equals(c.url)) return "null";
            try {
                return episodeList(c);
            } catch (Exception e) {
                Log.w(TAG, "episode list", e);
                return "null";
            }
        }

        /**
         * めくりの振動 (the user's request, off by default): a tick as a tap turns a page. The long press's feedback:
         * on the user's Galaxy A25 it was the only one of six that vibrated (VIRTUAL_KEY, KEYBOARD_TAP,
         * CONTEXT_CLICK, CONFIRM and CLOCK_TICK did nothing), and the user chose it over adding the vibrate
         * permission for a lighter one.
         */
        @JavascriptInterface
        public void tick() {
            runOnUiThread(() -> web.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS));
        }

        /** A light tick, as Android gives a long press (the page bar changing between pages and episodes). */
        @JavascriptInterface
        public void haptic() {
            runOnUiThread(() -> web.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS));
        }

        /** Opens an episode of this work picked in the 目次: its number, or for カクヨム its id. */
        @JavascriptInterface
        public void open(String ref) {
            EpisodeInfo c = current;
            String ncode = c == null ? null : Shelf.ncodeOf(c.url);
            if (ncode == null || ref == null || !ref.matches("\\d{1,30}")) return;
            String to;
            if (Shelf.isKakuyomu(ncode)) to = Shelf.kkEpisodeUrl(ncode, ref);
            else if (ref.length() <= 9 && Integer.parseInt(ref) >= 1) to = Shelf.episodeUrl(ncode, Integer.parseInt(ref));
            else return;
            tocGen++;
            move(to, 0, null);
        }
    }

    /**
     * The window behind the page takes the theme's colour, so a dark theme does not flash white while an
     * episode loads; and the status bar (time, notifications) is shown or hidden as chosen.
     */
    void applyWindowBackground(String json) {
        String theme = "kinari";
        boolean bar = false;
        try {
            if (json != null) {
                JSONObject o = new JSONObject(json);
                theme = o.optString("theme", theme);
                bar = o.optBoolean("bar", false);
            }
        } catch (Exception ignored) { }
        int color = themeColors(theme)[0];
        boolean light = (0.299 * ((color >> 16) & 0xFF) + 0.587 * ((color >> 8) & 0xFF) + 0.114 * (color & 0xFF)) > 128;
        boolean showBar = bar;
        runOnUiThread(() -> {
            web.setBackgroundColor(color);
            getWindow().getDecorView().setBackgroundColor(color);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsets.Type.navigationBars());
            if (showBar) c.show(WindowInsets.Type.statusBars());
            else c.hide(WindowInsets.Type.statusBars());
            c.setSystemBarsAppearance(light ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        });
    }

    /**
     * {background, text, secondary text, rule} of a theme, the same values as reader.html's THEMES. The
     * secondary text is at least 4.5:1 on the background and on the shelf's cards (WCAG AA).
     */
    static int[] themeColors(String theme) {
        switch (theme) {
            case "white": return new int[]{0xFFFFFFFF, 0xFF111111, 0xFF6B6B6B, 0xFFE2E2E2};
            case "sepia": return new int[]{0xFFF1E7D0, 0xFF4A3B2A, 0xFF6E5F48, 0xFFD9CBAA};
            case "dark": return new int[]{0xFF1F1F1F, 0xFFD6D2C8, 0xFF9A978F, 0xFF3A3A3A};
            case "black": return new int[]{0xFF000000, 0xFFA8A49A, 0xFF8A867C, 0xFF2A2A2A};
            default: return new int[]{0xFFFBF8F1, 0xFF222222, 0xFF6B6B6B, 0xFFE4E0D6};
        }
    }

    final java.util.concurrent.atomic.AtomicReference<Object[]> place = new java.util.concurrent.atomic.AtomicReference<>();
    /** For the E2E tests only: every page the page reported, in order; null (not recorded) in the app. */
    static volatile java.util.List<Integer> reported;

    /** the place last written, to count the pages turned for the read log */
    String loggedKey;
    /** the episode last written: its number, or for カクヨム its id (a list change moves the number, not the reader) */
    String loggedEpisode;
    int loggedPage = -1;

    void savePlace() {
        Object[] pl = place.getAndSet(null);
        if (pl == null) return;
        // The read log: a different page of the same episode, or another episode, is a page read.
        String key = (String) pl[0];
        int episode = (Integer) pl[1], pg = (Integer) pl[2];
        if (pl[4] != null) {
            org.json.JSONObject list = KakuyomuSite.load(this, key);
            episode = list == null ? 0 : KakuyomuSite.numberOf(list, (String) pl[4]);
            if (episode == 0) {                                         // no longer listed (for the read log): the number it had
                Shelf.Work w = Shelf.load(this).find(key);
                if (w != null && pl[4].equals(w.lastEpisodeId)) episode = w.lastEpisode;
            }
        }
        String which = pl[4] != null ? (String) pl[4] : String.valueOf(pl[1]);
        boolean moved = key.equals(loggedKey) && (!which.equals(loggedEpisode) || pg != loggedPage);
        EpisodeInfo c = current;
        ReadLog.record(getFilesDir(), java.time.LocalDate.now(), key, c == null ? null : c.workTitle, episode, moved ? 1 : 0);
        loggedKey = key;
        loggedEpisode = which;
        loggedPage = pg;
        int no = episode;
        try {
            Shelf.update(this, s -> {
                Shelf.Work w = s.find((String) pl[0]);
                if (w == null) return;
                if (pl[4] != null) {
                    // The list is read here, under the shelf's lock, where a list change and its remap are one step.
                    if (!KakuyomuSite.place(w, (String) pl[4], KakuyomuSite.load(this, (String) pl[0]))) return;
                } else {
                    w.lastEpisode = no;
                }
                w.lastPage = (Integer) pl[2];
                w.lastPages = (Integer) pl[3];
                w.lastRead = System.currentTimeMillis();
            });
        } catch (Exception e) {
            Log.w(TAG, "place not saved", e);
        }
    }

    /**
     * カクヨム: the episode before or after the one shown, found by its id in the list as it is now (an episode
     * inserted meanwhile would otherwise make "next" the same episode again); END when found at that end of the
     * list (no fallback to the old numbering); null when not カクヨム or not found.
     */
    static final String END = "end:";

    String neighbour(int d) {
        Prepared p = shown;
        String[] kk = p == null || p.failed ? null : Shelf.kkEpisodeOf(p.url);
        return kk == null ? null : kkStep(kk[0], kk[1], d);
    }

    /**
     * カクヨム: the episode d after id in the kept list; END past an end; null when the list is not there or does
     * not have id. The shelf's place no longer listed (deleted) steps from the slot where it was (remap keeps it).
     */
    String kkStep(String key, String id, int d) {
        // The list and the shelf's slot are read together, under the lock a list save and its remap hold.
        synchronized (Shelf.class) { return kkStepLocked(key, id, d); }
    }

    private String kkStepLocked(String key, String id, int d) {
        JSONObject list = KakuyomuSite.load(this, key);
        if (list == null) return null;
        int n = KakuyomuSite.numberOf(list, id), at;
        if (n > 0) at = n + d;
        else {
            Shelf.Work w = Shelf.load(this).find(key);
            if (w == null || !id.equals(w.lastEpisodeId) || w.lastEpisode <= 0) return null;
            at = d > 0 ? w.lastEpisode + d - 1 : w.lastEpisode + d;       // the slot holds the one after it
        }
        String to = KakuyomuSite.idAt(list, at);
        return to == null ? END : Shelf.kkEpisodeUrl(key, to);
    }

    /**
     * The episode after this one when its page (kept from when it was the newest) has no 次へ, but the shelf
     * knows of more; null when there is none.
     */
    String laterEpisode(String url) {
        Object[] ep = Shelf.episodeOf(url);
        if (ep == null) return null;
        Shelf.Work w = Shelf.load(this).find((String) ep[0]);
        int no = (Integer) ep[1];
        return w != null && w.episodes > no ? Shelf.episodeUrl((String) ep[0], no + 1) : null;
    }

    /**
     * The page template's slots filled in one pass over the template: a value (a title holding "%%BOOK%%")
     * is never read again as a slot.
     */
    static String fill(String template, String info, String start, String share, String prefs, String book) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%%(INFO|START|SHARE|PREFS|BOOK)%%").matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v;
            switch (m.group(1)) {
                case "INFO": v = info; break;
                case "START": v = start; break;
                case "SHARE": v = share; break;
                case "PREFS": v = prefs; break;
                default: v = book;
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Past the last page of the last episode: the card that says so, and where to go from there. */
    void finished(EpisodeInfo c) {
        String key = Shelf.ncodeOf(c.url);
        Shelf.Work w = key == null ? null : Shelf.load(this).find(key);
        boolean done = w == null || w.finished || Shelf.isAozora(key);
        String page = w == null ? (c.workUrl == null ? "" : c.workUrl) : Shelf.workUrl(w);
        String js = "window.__reader&&__reader.end(" + done + "," + JSONObject.quote(page) + "," + Shelf.isAozora(key) + ")";
        runOnUiThread(() -> { if (!isDestroyed()) web.evaluateJavascript(js, null); });
    }

    /** The episode on its way to the screen; turns past an end ask for it once. */
    volatile String pending;

    void move(String url, int startPage, String none) {
        if (url == null) {
            if (none != null) say(none);
            return;
        }
        if (work.isShutdown() || url.equals(pending)) return;
        pending = url;
        Prepared n = nextReady, b = prevReady;
        Prepared use = n != null && sameEpisode(n.url, url) ? n : b != null && sameEpisode(b.url, url) ? b : null;
        if (use != null) {
            run(work, () -> show(withCurrentList(use), startPage, false));
            return;
        }
        try {
            if (!cacheFile(url).isFile()) say("読み込み中…");
        } catch (Exception ignored) {
        }
        run(work, () -> load(url, startPage));
    }

    /**
     * A prepared カクヨム episode with its number and neighbours from the list as it is now (it may have changed
     * since it was prepared); its formatted text is kept. Nothing is fetched.
     */
    Prepared withCurrentList(Prepared p) {
        String[] kk = p.failed ? null : Shelf.kkEpisodeOf(p.url);
        JSONObject list = kk == null ? null : KakuyomuSite.load(this, kk[0]);
        if (list == null) return p;
        try {
            EpisodeInfo ep = kakuyomuInfo(p.url, list);
            return new Prepared(p.url, ep, p.body, infoJson(ep));
        } catch (Exception e) {
            return p;
        }
    }

    /**
     * The credit of an error page: from the episode left, or (opened straight on an episode that fails) from
     * the shelf, so the 目次 and the previous episode stay reachable and a dead episode does not trap the work.
     */
    EpisodeInfo errorInfo(String url) {
        EpisodeInfo c = current;
        String[] kk = Shelf.kkEpisodeOf(url);
        // On from the error page tries the episode again, but a カクヨム episode no longer listed (deleted, its page
        // gone) goes on to the one after where it was instead.
        String on = url;
        if (kk != null) {
            JSONObject list = KakuyomuSite.load(this, kk[0]);
            String after = list != null && KakuyomuSite.numberOf(list, kk[1]) == 0 ? kkStep(kk[0], kk[1], 1) : null;
            if (END.equals(after)) on = null;                               // it was the newest: on shows the end card
            else if (after != null) on = after;
        }
        if (c != null) return EpisodeInfo.of(url, c.workTitle, c.workUrl, c.author, c.authorUrl, null, c.url, on);
        Object[] ep = Shelf.episodeOf(url);
        String key = Shelf.ncodeOf(url);
        Shelf.Work w = key == null ? null : Shelf.load(this).find(key);
        String prev = ep != null && (Integer) ep[1] > 1 ? Shelf.episodeUrl(key, (Integer) ep[1] - 1) : null;
        if (kk != null) { prev = kkStep(kk[0], kk[1], -1); if (END.equals(prev)) prev = null; }
        return EpisodeInfo.of(url, w == null ? null : w.title, w == null ? null : Shelf.workUrl(w),
                w == null ? null : w.author, null, null, prev, on);
    }

    /** An episode formatted and ready to show: its body for the page and its credit. */
    static final class Prepared {
        final String url;
        final EpisodeInfo ep;
        final String body;
        final String info;
        /** an error page: its place is never saved, and turning on from it tries the episode again */
        boolean failed;

        Prepared(String url, EpisodeInfo ep, String body, String info) {
            this.url = url;
            this.ep = ep;
            this.body = body;
            this.info = info;
        }
    }

    /**
     * The episodes either side of the one shown, formatted ahead so turning into them does not wait or reload
     * the page. The one just left is kept as is; the others are formatted only from the cache.
     */
    volatile Prepared shown, nextReady, prevReady;
    /** guards nextReady/prevReady with readAheadGen: a read-ahead finishing after a newer show() drops its result */
    final Object ready = new Object();

    static boolean sameEpisode(String a, String b) {
        return Shelf.sameEpisode(a, b);
    }

    Prepared prepare(String url) throws Exception {
        long t0 = System.nanoTime();
        url = byId(url);
        if (url != null && Shelf.isAozora(Shelf.ncodeOf(url))) return prepareAozora(url, t0);
        String html = url == null ? asset("narou-episode-sample.html") : episode(url);
        boolean kk = url != null && Shelf.isKakuyomu(Shelf.ncodeOf(url));
        String pageUrl = url == null ? SAMPLE_URL : kk ? KakuyomuSite.realUrl(this, url) : url;
        List<String> sections = new EpisodeFormatter(dataDir(), packBlankLines() ? 1 : 0, forceIndent(), uprightLetters())
                .toXhtmlSections(html, pageUrl);
        EpisodeInfo ep = kk ? kakuyomuInfo(url) : EpisodeInfo.fromNarou(html, pageUrl);
        StringBuilder sb = new StringBuilder();
        for (String sec : sections) sb.append("<section class=\"ep\">").append(sec).append("</section>");
        Log.i(TAG, "formatted " + sections.size() + " section(s) in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        return new Prepared(url, ep, sb.toString(), infoJson(ep));
    }

    /**
     * An older kakuyomu://&lt;work&gt;/&lt;n&gt; (a place kept by an earlier build) as kakuyomu://&lt;work&gt;/e/&lt;id&gt;, from
     * the list; any other URL as it is.
     */
    String byId(String url) throws Exception {
        Object[] ep = Shelf.episodeOf(url);
        if (ep == null || !Shelf.isKakuyomu((String) ep[0])) return url;
        String key = (String) ep[0];
        int no = (Integer) ep[1];
        String id = KakuyomuSite.idAt(KakuyomuSite.list(this, key, no, Long.MAX_VALUE), no);
        if (id == null) throw new IOException("第" + no + "話はありません");
        return Shelf.kkEpisodeUrl(key, id);
    }

    /** @param startPage the page to open at; -1 for the last (turning back from the next episode) */
    void load(String url, int startPage) {
        load(url, startPage, -1);
    }

    /** @param share when 0..1, the share of the episode to open at instead of startPage */
    void load(String url, int startPage, double share) {
        Prepared p;
        try {
            p = prepare(url);
        } catch (Throwable e) {
            Log.e(TAG, "format failed", e);
            // The message can carry the URL asked for: escaped, it is only text. Back goes to the episode left;
            // on tries this one again.
            EpisodeInfo info = url == null ? null : errorInfo(url);
            String json = "null";
            try { if (info != null) json = infoJson(info); } catch (Exception ignored) { }
            p = new Prepared(url, info, "<section class=\"ep\"><p>読み込めませんでした：" + android.text.TextUtils.htmlEncode(String.valueOf(e))
                    + "</p><p>電波の届く所で、もう一度めくってください。</p></section>", json);
            p.failed = true;
        }
        show(p, startPage, true, share);
    }

    /** Shows a prepared episode: a whole page load the first time, a swap inside the page after that. */
    void show(Prepared p, int startPage, boolean reload) {
        show(p, startPage, reload, -1);
    }

    void show(Prepared p, int startPage, boolean reload, double share) {
        pending = null;
        Prepared left = shown;
        if (p.ep != null) current = p.ep;
        shown = p;
        synchronized (ready) { nextReady = prevReady = null; readAheadGen++; }
        if (left != null && !left.failed && left.ep != null && p.ep != null && !p.failed) {
            if (left.url != null && p.ep.prevUrl != null && sameEpisode(left.url, p.ep.prevUrl)) prevReady = left;
            else if (left.url != null && p.ep.nextUrl != null && sameEpisode(left.url, p.ep.nextUrl)) nextReady = left;
        }
        if (p.url != null && p.ep != null && !p.failed) {
            int gen = readAheadGen;
            later(ahead, () -> readAhead(gen, p.url, 1), PREFETCH_DELAY_S, TimeUnit.SECONDS);
        }
        if (!reload) {
            String js = "window.__reader?__reader.swap(" + JSONObject.quote(p.body) + "," + p.info + "," + startPage + "):0";
            runOnUiThread(() -> { if (!isDestroyed()) web.evaluateJavascript(js, null); });
            Log.i(TAG, "swapped in " + p.url);
            return;
        }
        String page;
        try {
            String display = prefs.getString("display", null);
            String displayJson = "null";
            if (display != null) {
                try { displayJson = new JSONObject(display).toString().replace("</", "<\\/"); } catch (Exception ignored) { }
            }
            page = fill(asset("reader.html"), p.info, String.valueOf(startPage), String.valueOf(share), displayJson, p.body);
        } catch (IOException e) {
            Log.e(TAG, "reader.html", e);
            return;
        }
        String finalPage = page;
        overlay(false);                                                     // the new page starts with nothing open
        runOnUiThread(() -> { if (!isDestroyed()) web.loadDataWithBaseURL("file:///android_asset/", finalPage, "text/html", "UTF-8", null); });
    }

    /**
     * Keeps the next episodes ready for a stretch without signal: as many as the user chose (1 by default),
     * one request at a time, PREFETCH_DELAY_S apart. Stops when another episode is opened.
     */
    void readAhead(int gen, String from, int k) {
        if (gen != readAheadGen) return;
        if (k > aheadCount()) return;
        String ncode = Shelf.ncodeOf(from);
        Shelf.Work w = ncode == null ? null : Shelf.load(this).find(ncode);
        if (w == null) return;
        String next;
        String[] kk = Shelf.kkEpisodeOf(from);
        if (kk != null) {
            next = kkStep(kk[0], kk[1], k);
            if (next == null || END.equals(next)) return;
        } else {
            Object[] ep = Shelf.episodeOf(from);
            if (ep == null) return;
            int no = (Integer) ep[1] + k;
            if (w.episodes > 0 && no > w.episodes) return;
            next = Shelf.episodeUrl(ncode, no);
        }
        boolean fetched = false;
        try {
            if (!cacheFile(next).isFile()) {
                episode(next);
                fetched = true;
            }
            if (k == 1 && gen == readAheadGen) {
                Prepared n = nextReady;
                if (n == null || !sameEpisode(n.url, next)) {
                    Prepared p = prepare(next);
                    synchronized (ready) { if (gen == readAheadGen) nextReady = p; }
                }
                prepareBack(gen);
            }
        } catch (Exception e) {
            Log.w(TAG, "read ahead failed: " + next, e);
            return;
        }
        later(ahead, () -> readAhead(gen, from, k + 1), fetched ? PREFETCH_DELAY_S : 0, TimeUnit.SECONDS);
    }

    /** The episode before the one shown, when it is already kept: formatted, never fetched for this. */
    void prepareBack(int gen) {
        EpisodeInfo c = current;
        Prepared b = prevReady;
        if (c == null || c.prevUrl == null || (b != null && sameEpisode(b.url, c.prevUrl))) return;
        try {
            if (!cacheFile(c.prevUrl).isFile()) return;
            Prepared p = prepare(c.prevUrl);
            synchronized (ready) { if (gen == readAheadGen) prevReady = p; }
        } catch (Exception e) {
            Log.w(TAG, "prepare back failed: " + c.prevUrl, e);
        }
    }

    /** Whether the blank line web novels put between paragraphs is dropped (the default). */
    boolean packBlankLines() {
        try {
            String d = prefs.getString("display", null);
            return d == null || new JSONObject(d).optBoolean("pack", true);
        } catch (Exception ignored) {
            return true;
        }
    }

    /** Whether a paragraph written flush gets a full-width space (off by default: the text as written). */
    boolean forceIndent() {
        try {
            String d = prefs.getString("display", null);
            return d != null && new JSONObject(d).optBoolean("indent", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Whether every half-width letter stands upright, English included (off by default: narou.rb's rule). */
    boolean uprightLetters() {
        try {
            String d = prefs.getString("display", null);
            return d != null && new JSONObject(d).optBoolean("upright", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    int aheadCount() {
        try {
            String d = prefs.getString("display", null);
            if (d != null) return Math.max(1, Math.min(MAX_AHEAD, new JSONObject(d).optInt("ahead", 1)));
        } catch (Exception ignored) {
        }
        return 1;
    }

    /**
     * A section of a 青空文庫 work: the whole text is fetched and formatted once, its sections kept
     * (episodes/aozora-&lt;id&gt;/sections.json), and each turned like an episode.
     */
    Prepared prepareAozora(String url, long t0) throws Exception {
        Object[] ep = Shelf.episodeOf(url);
        String key = (String) ep[0];
        int no = (Integer) ep[1];
        File f = AozoraSite.sectionsFile(this, key);
        List<String> sections;
        sections = null;
        if (f.isFile()) {
            try {
                sections = AozoraSite.loadSections(this, key);
                if (sections.isEmpty()) throw new org.json.JSONException("no sections");
            } catch (org.json.JSONException bad) {
                // Cut short or empty: formatted again. (An I/O error is thrown on: the file may be fine.)
                Log.w(TAG, "sections do not parse; formatting again", bad);
                f.delete();
                sections = null;
            }
        }
        if (sections == null) {
            say("青空文庫から読み込んでいます…");
            String text = AozoraSite.text(this, key);
            sections = new jp.harusame.aozorareader.core.AozoraFormatter(dataDir().getAbsolutePath() + "/").toXhtmlSections(text);
            synchronized (Shelf.class) {
                if (Shelf.load(this).find(key) != null) AozoraSite.saveSections(this, key, sections);
            }
            int n0 = sections.size();
            Shelf.update(this, s -> { Shelf.Work w = s.find(key); if (w != null) { w.episodes = n0; w.seen = n0; } });
        }
        int n = sections.size();
        no = Math.max(1, Math.min(n, no));
        JSONObject meta = AozoraSite.meta(this, key);
        String title = meta.optString("title") + (meta.optString("subtitle").isEmpty() ? "" : "　" + meta.optString("subtitle"));
        EpisodeInfo info = EpisodeInfo.of(Shelf.episodeUrl(key, no), title, meta.optString("card"), meta.optString("author"), null,
                n > 1 ? no + "/" + n : null, no > 1 ? Shelf.episodeUrl(key, no - 1) : null, no < n ? Shelf.episodeUrl(key, no + 1) : null);
        Log.i(TAG, "aozora " + key + " section " + no + "/" + n + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        return new Prepared(info.url, info, "<section class=\"ep\">" + sections.get(no - 1) + "</section>", infoJson(info));
    }

    /** A 青空文庫 section named by its first heading or words (its number when it has none). */
    static String sectionTitle(List<String> sections, int i) {
        String t = android.text.Html.fromHtml(sections.get(i).replaceAll("(?s)<rt>.*?</rt>", ""), 0).toString()
                .replaceAll("\\s+", " ").trim();
        if (t.length() > 28) t = t.substring(0, 28) + "…";
        return t.isEmpty() ? "（" + (i + 1) + "）" : t;
    }

    /** The 目次 of a 青空文庫 work: its sections, by their first heading or words. */
    void aozoraToc(String key) {
        try {
            List<String> sections = AozoraSite.loadSections(this, key);
            JSONArray entries = new JSONArray();
            for (int i = 0; i < sections.size(); i++) entries.put(new JSONObject().put("n", i + 1).put("t", sectionTitle(sections, i)));
            deliverToc(new JSONObject().put("pages", 1).put("read", 1).put("done", true).put("entries", entries).toString());
        } catch (Exception e) {
            Log.w(TAG, "aozora toc", e);
            deliverToc("{\"error\":true}");
        }
    }

    /** The credit of a カクヨム episode, from the work's kept list: its pages carry less of it than なろう's. */
    EpisodeInfo kakuyomuInfo(String url) throws Exception {
        String[] kk = Shelf.kkEpisodeOf(url);
        return kakuyomuInfo(url, KakuyomuSite.listWith(this, kk[0], kk[1]));
    }

    EpisodeInfo kakuyomuInfo(String url, JSONObject list) throws Exception {
        String[] kk = Shelf.kkEpisodeOf(url);
        String key = kk[0];
        int no = KakuyomuSite.numberOf(list, kk[1]), n = list.getJSONArray("eps").length();
        String authorUrl = list.optString("authorUrl", "");
        String prev = no > 1 ? Shelf.kkEpisodeUrl(key, KakuyomuSite.idAt(list, no - 1)) : null;
        String next = no > 0 && no < n ? Shelf.kkEpisodeUrl(key, KakuyomuSite.idAt(list, no + 1)) : null;
        if (no == 0) {
            // No longer listed (deleted, but kept here): shown without a number. When it is the shelf's place, its
            // neighbours are the episodes either side of the slot where it was (kkStep).
            String b = kkStep(key, kk[1], -1), a = kkStep(key, kk[1], 1);
            if (b != null && !END.equals(b)) prev = b;
            if (a != null && !END.equals(a)) next = a;
        }
        return EpisodeInfo.of(url, list.optString("title"), Shelf.workUrl(key), list.optString("author"),
                authorUrl.isEmpty() ? null : authorUrl, no > 0 ? no + "/" + n : null, prev, next);
    }

    /** The 目次 of a カクヨム work, from its kept list (read again when a day old). */
    void kakuyomuToc(String key) {
        try {
            JSONObject list = KakuyomuSite.list(this, key, 0, TOC_FRESH_MS);
            JSONArray eps = list.getJSONArray("eps"), entries = new JSONArray();
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.getJSONObject(i), o = new JSONObject().put("n", i + 1).put("id", e.optString("id")).put("t", e.optString("t"));
                if (e.has("c")) o.put("c", e.getString("c"));
                entries.put(o);
            }
            deliverToc(new JSONObject().put("pages", 1).put("read", 1).put("done", true).put("entries", entries).toString());
        } catch (Exception e) {
            Log.w(TAG, "kakuyomu toc", e);
            deliverToc("{\"error\":true}");
        }
    }

    String infoJson(EpisodeInfo i) throws Exception {
        JSONObject o = new JSONObject();
        o.put("url", i.url);
        Object[] ep = Shelf.episodeOf(i.url);
        String[] kk = Shelf.kkEpisodeOf(i.url);
        // The link to the episode on its site (for カクヨム, its real URL).
        String page = i.url;
        // From what is kept only: building a credit never fetches (an error page would fetch right after a failure).
        if (kk != null) {
            o.put("id", kk[1]);
            JSONObject list = KakuyomuSite.load(this, kk[0]);
            int no = list == null ? 0 : KakuyomuSite.numberOf(list, kk[1]);
            if (no > 0) o.put("no", no);
            page = KakuyomuSite.realUrl(this, i.url);
        } else if (ep != null && Shelf.isKakuyomu((String) ep[0])) {
            page = i.workUrl;                                               // an older place not yet resolved (an error page)
            o.put("no", ep[1]);
        } else if (ep != null) {
            o.put("no", ep[1]);
            if (Shelf.isAozora((String) ep[0])) page = i.workUrl;
        }
        o.put("page", page);
        o.put("workTitle", i.workTitle);
        o.put("workUrl", i.workUrl);
        o.put("author", i.author);
        o.put("authorUrl", i.authorUrl);
        o.put("number", i.number);
        // Inside a <script>, "</" would end it.
        return o.toString().replace("</", "<\\/");
    }

    /**
     * The work's episodes, {"eps":[{"n","id"?,"t"?,"k"?}]}, from what is kept only: nothing is fetched, so a drag
     * of the page bar costs no request. Titles ("t") come from the kept 目次 (any age; none until it was opened
     * once, for なろう); "k" marks an episode kept in the app. なろう's count is the larger of the shelf's, the kept
     * 目次's and the episode's own "2/5".
     */
    String episodeList(EpisodeInfo c) throws Exception {
        String key = Shelf.ncodeOf(c.url);
        if (key == null || (Shelf.episodeOf(c.url) == null && Shelf.kkEpisodeOf(c.url) == null)) return "null";
        JSONArray eps = new JSONArray();
        if (Shelf.isKakuyomu(key)) {
            JSONObject list = KakuyomuSite.load(this, key);
            JSONArray kept = list == null ? new JSONArray() : list.getJSONArray("eps");
            for (int i = 0; i < kept.length(); i++) {
                JSONObject e = kept.getJSONObject(i);
                // An entry without an id (a list kept before ids were checked) cannot be opened by id, and its
                // position is not an id: no episode slider for that work (review on #12).
                if (!e.optString("id").matches("\\d{1,30}")) return "null";
                JSONObject o = new JSONObject().put("n", i + 1).put("id", e.optString("id")).put("t", e.optString("t"));
                if (Episodes.cacheFile(this, Shelf.kkEpisodeUrl(key, e.optString("id"))).isFile()) o.put("k", 1);
                eps.put(o);
            }
        } else if (Shelf.isAozora(key)) {
            List<String> sections = AozoraSite.loadSections(this, key);
            for (int i = 0; i < sections.size(); i++)
                eps.put(new JSONObject().put("n", i + 1).put("t", sectionTitle(sections, i)).put("k", 1));   // the whole text, kept at once
        } else {
            java.util.Map<Integer, String> titles = new java.util.HashMap<>();
            File f = new File(getFilesDir(), "episodes/" + key + "/toc.json");
            if (f.isFile()) {
                JSONArray es = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).optJSONArray("entries");
                for (int i = 0; es != null && i < es.length(); i++) titles.put(es.getJSONObject(i).optInt("n"), es.getJSONObject(i).optString("t"));
            }
            Shelf.Work w = Shelf.load(this).find(key);
            int n = Math.max(w == null ? 0 : w.episodes, titles.size());
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*/\\s*(\\d+)\\s*$").matcher(c.number == null ? "" : c.number);
            if (m.find()) n = Math.max(n, Integer.parseInt(m.group(2)));
            Object[] ep = Shelf.episodeOf(c.url);
            if (ep != null) n = Math.max(n, (Integer) ep[1]);
            for (int i = 1; i <= n; i++) {
                JSONObject o = new JSONObject().put("n", i);
                String t = titles.get(i);
                if (t != null && !t.isEmpty()) o.put("t", t);
                if (Episodes.cacheFile(this, Shelf.episodeUrl(key, i)).isFile()) o.put("k", 1);
                eps.put(o);
            }
        }
        return eps.length() == 0 ? "null" : new JSONObject().put("eps", eps).toString();
    }

    volatile int tocGen;

    /**
     * A work's 目次, read when the user opens it: kept for TOC_FRESH_MS in episodes/&lt;ncode&gt;/toc.json, otherwise
     * read again one page (100 episodes) at a time, TOC_PAGE_DELAY_MS apart, each page shown as it arrives.
     */
    void loadToc(int gen, String ncode) {
        File f = new File(getFilesDir(), "episodes/" + ncode + "/toc.json");
        try {
            if (f.isFile()) {
                JSONObject kept = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                if (kept.optBoolean("done") && System.currentTimeMillis() - kept.optLong("at") < TOC_FRESH_MS) {
                    deliverToc(kept.toString());
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "toc cache", e);
        }
        tocPage(gen, ncode, 1, new JSONArray(), f, null);
    }

    /** carry: a chapter heading left at the end of the page before, which opens this page's first episode */
    void tocPage(int gen, String ncode, int page, JSONArray entries, File f, String carry) {
        if (gen != tocGen) return;
        try {
            String url = "https://ncode.syosetu.com/" + ncode + "/" + (page > 1 ? "?p=" + page : "");
            Toc t = Toc.fromNarou(fetch(url), url, carry);
            for (Toc.Entry e : t.entries) {
                JSONObject o = new JSONObject();
                if (e.chapter != null) o.put("c", e.chapter);
                o.put("n", e.no);
                o.put("t", e.title);
                entries.put(o);
            }
            boolean done = page >= t.pages;
            JSONObject out = new JSONObject();
            out.put("pages", t.pages);
            out.put("read", page);
            out.put("done", done);
            out.put("at", System.currentTimeMillis());
            out.put("entries", entries);
            if (done) {
                synchronized (Shelf.class) {
                    if (Shelf.load(this).find(ncode) != null) {
                        f.getParentFile().mkdirs();
                        Files.write(f.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            deliverToc(out.toString());
            if (!done) later(work, () -> tocPage(gen, ncode, page + 1, entries, f, t.trailingChapter), TOC_PAGE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Log.w(TAG, "toc page " + page, e);
            deliverToc("{\"error\":true}");
        }
    }

    /**
     * Each entry of the 目次 whose episode is kept in the app gets "k" (so a reader filling up before a flight
     * sees what is there), looked up as it is delivered: the 目次 kept on disk does not hold it.
     */
    String markKept(String json) {
        try {
            if (!json.startsWith("{")) return json;
            JSONObject o = new JSONObject(json);
            JSONArray es = o.optJSONArray("entries");
            EpisodeInfo c = current;
            String key = c == null ? null : Shelf.ncodeOf(c.url);
            if (es == null || key == null || Shelf.isAozora(key)) return json;      // 青空文庫: the whole text, kept at once
            for (int i = 0; i < es.length(); i++) {
                JSONObject e = es.getJSONObject(i);
                String u = e.has("id") ? Shelf.kkEpisodeUrl(key, e.getString("id")) : Shelf.episodeUrl(key, e.optInt("n"));
                if (Episodes.cacheFile(this, u).isFile()) e.put("k", 1);
            }
            return o.toString();
        } catch (Exception e) {
            Log.w(TAG, "toc kept marks", e);
            return json;
        }
    }

    void deliverToc(String json) {
        json = markKept(json);
        String js = "window.__reader&&__reader.toc(" + json.replace("</", "<\\/") + ")";
        runOnUiThread(() -> { if (!isDestroyed()) web.evaluateJavascript(js, null); });
    }

    /** One episode the user chose, from the cache or fetched once and kept: see {@link Episodes#get}. */
    String episode(String url) throws Exception {
        return Episodes.get(this, url);
    }

    /** A page of なろう's own site (an episode or a 目次 page); nothing else is fetched. */
    String fetch(String url) throws Exception {
        URL u = new URL(url);
        if (!allowed(u)) throw new IllegalArgumentException("not a なろう URL: " + url);
        return new String(fetchBytes(u), StandardCharsets.UTF_8);
    }

    /** The only places episodes and 目次 come from (Net allows the API and 青空文庫 too). */
    static boolean allowed(URL u) {
        return Net.allowed(u) && ("ncode.syosetu.com".equals(u.getHost()) || "kakuyomu.jp".equals(u.getHost()));
    }

    byte[] fetchBytes(URL u) throws IOException {
        return Net.get(u.toString());
    }

    File cacheFile(String url) throws Exception {
        return Episodes.cacheFile(this, url);
    }

    /** aozora reads its tables and site definitions from files: copy them out of core's resources once. */
    File dataDir() throws IOException {
        File dir = new File(getFilesDir(), "aozora");
        String[] names = {"chuki_alt.txt", "chuki_ivs.txt", "chuki_latin.txt", "chuki_tag.txt", "chuki_tag_suf.txt",
                "chuki_utf.txt", "web/ncode.syosetu.com/extract.txt", "web/novel18.syosetu.com/extract.txt",
                "web/kakuyomu.jp/extract.txt"};
        for (String n : names) {
            File f = new File(dir, n);
            if (f.isFile()) continue;
            f.getParentFile().mkdirs();
            // Copied whole, then renamed: a table cut short would never be copied again.
            File tmp = new File(f.getPath() + ".tmp");
            try (InputStream in = AozoraFormatter.class.getResourceAsStream("/" + n)) {
                if (in == null) { Log.w(TAG, "missing resource " + n); continue; }
                try (OutputStream out = new FileOutputStream(tmp)) { in.transferTo(out); }
                Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        }
        return dir;
    }

    String asset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
