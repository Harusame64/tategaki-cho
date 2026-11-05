package jp.harusame.aozorareader;

import android.app.Activity;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 読みさし's page (assets/yomisashi.js) talking to the app, as "YS": one for the reader's page and one for the sheet
 * over the shelf, which differ only in how a work is opened and what a close does. One class, as two copies of these
 * methods drifted apart from one review round to the next.
 *
 * <p>The screens are held weakly by what runs later (the cards' text, ⟳'s check, which can wait minutes for another
 * check): a screen left meanwhile is not kept alive by them.
 */
final class YomisashiBridge {
    /** How a screen opens a work: {@code failed} runs (on any thread) when nothing was opened. */
    interface Opener {
        void open(String key, Runnable failed);
    }

    /** The cards' text, read off the page's thread: up to twenty kept episodes. */
    private static final ExecutorService TEXTS = Executors.newSingleThreadExecutor();

    private final WeakReference<Activity> activity;
    private final WeakReference<WebView> web;
    private final Supplier<String> current;
    private final Opener opener;
    private final Runnable checked, closed;
    /** A work is being opened: a second open (another tap, a sheet shown again meanwhile) does nothing. */
    private final AtomicBoolean opening = new AtomicBoolean();

    /**
     * @param current the work being read (its card only closes the sheet), or null
     * @param checked run on the UI thread after ⟳'s check (the shelf behind is drawn again), or null
     * @param closed run on the UI thread when the page has closed the sheet, or null
     */
    YomisashiBridge(Activity a, WebView w, Supplier<String> current, Opener opener, Runnable checked, Runnable closed) {
        activity = new WeakReference<>(a);
        web = new WeakReference<>(w);
        this.current = current;
        this.opener = opener;
        this.checked = checked;
        this.closed = closed;
    }

    /** The sheet is gone (hidden, or the screen left): a work may be opened again. */
    void reset() { opening.set(false); }

    /** Runs a script in the page on the UI thread, if the screen is still there. */
    private void js(String script, Runnable after) {
        Activity a = activity.get();
        if (a == null) return;
        a.runOnUiThread(() -> {
            WebView w = web.get();
            if (a.isDestroyed() || w == null) return;
            w.evaluateJavascript(script, null);
            if (after != null) after.run();
        });
    }

    /** The cards as JSON (Yomisashi.json), without their text: that comes after, from {@link #texts()}. */
    @JavascriptInterface
    public String list() {
        Activity a = activity.get();
        return a == null ? "{}" : Yomisashi.json(a, current.get(), false).toString();
    }

    /** Reads the cards' text off the page's thread and gives it to the page (Yomisashi.gotTexts). */
    @JavascriptInterface
    public void texts() {
        Activity a = activity.get();
        if (a == null) return;
        android.content.Context c = a.getApplicationContext();
        String key = current.get();
        WeakReference<YomisashiBridge> me = new WeakReference<>(this);
        TEXTS.execute(() -> {
            String json;
            try {
                json = Yomisashi.json(c, key, true).toString();
            } catch (Exception e) {
                Log.w(Shelf.TAG, "読みさし: texts", e);
                return;
            }
            YomisashiBridge b = me.get();
            if (b != null) b.js("window.Yomisashi&&Yomisashi.gotTexts(" + JSONObject.quote(json) + ")", null);
        });
    }

    @JavascriptInterface
    public void open(String key) {
        if (!opening.compareAndSet(false, true)) return;
        WeakReference<YomisashiBridge> me = new WeakReference<>(this);
        opener.open(key, () -> {
            YomisashiBridge b = me.get();
            if (b == null) return;
            b.opening.set(false);
            b.js("window.Yomisashi&&Yomisashi.failed()", null);
        });
    }

    @JavascriptInterface
    public void drop(String key) {
        Activity a = activity.get();
        if (a != null) Yomisashi.drop(a, key);
    }

    /** The view last used: "c" (sheet) or "a" (large cards). */
    @JavascriptInterface
    public void view(String v) {
        Activity a = activity.get();
        if (a != null) Yomisashi.setView(a, v);
    }

    /** ⟳: the shelf's check; the page is told when it is over (Yomisashi.checked). */
    @JavascriptInterface
    public void check() {
        Activity a = activity.get();
        if (a == null) return;
        WeakReference<YomisashiBridge> me = new WeakReference<>(this);
        Yomisashi.check(a, msg -> {
            YomisashiBridge b = me.get();
            if (b != null) b.js("window.Yomisashi&&Yomisashi.checked(" + JSONObject.quote(msg) + ")", b.checked);
        });
    }

    @JavascriptInterface
    public void closed() {
        Activity a = activity.get();
        if (a != null && closed != null) a.runOnUiThread(closed);
    }
}
