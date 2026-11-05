package jp.harusame.aozorareader;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

/**
 * 読みさし over the shelf, from its toolbar: the same sheet as the reader's (assets/yomisashi.js), in a see-through
 * WebView laid over the whole screen. The page is loaded once, as the shelf opens, and only shown and hidden after:
 * a screen of its own took a second on the Galaxy A25 before anything was drawn (2026-11-05). A card opens its work;
 * a tap outside, 閉じる, a pull down or Back hides it.
 */
final class YomisashiOverlay {
    private final Activity a;
    private final ExecutorService bg;
    private final WebView web;
    private boolean ready, wanted;
    private final OnBackInvokedCallback back = this::close;
    private final YomisashiBridge bridge;

    /** run on the UI thread after ⟳'s check, so the shelf behind shows what it found */
    private final Runnable checked;

    YomisashiOverlay(Activity a, ExecutorService bg, Runnable checked) {
        this.a = a;
        this.bg = bg;
        this.checked = checked;
        web = new WebView(a);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setVisibility(View.GONE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        // No file: URLs, which does not cover file:///android_asset/ (the page's css and scripts, as in the
        // reader): "doesn't affect access to assets and resources" (WebSettings). W6 and W7b draw the sheet.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);
        web.setVerticalScrollBarEnabled(false);
        float density = a.getResources().getDisplayMetrics().density;
        // The system bars' heights, for the page to keep clear of (the overlay covers the whole window).
        web.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars());
            String js = "__nav(" + Math.round(i.bottom / density) + "," + Math.round(i.top / density) + ")";
            v.setTag(js);
            if (ready) web.evaluateJavascript(js, null);
            return in;
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }  // it opens nothing

            @Override
            public void onPageFinished(WebView v, String url) {
                ready = true;
                Object js = v.getTag();
                if (js instanceof String) v.evaluateJavascript((String) js, null);
                if (wanted) { wanted = false; show(); }
            }
        });
        bridge = new YomisashiBridge(a, web, () -> null,
                (key, failed) -> bg.execute(() -> { if (!Yomisashi.open(a, key, null)) failed.run(); }),   // the overlay goes as the reader comes up (the shelf's onPause)
                checked,
                // Only if it is still closed: Back hid it, the icon showed it again, and then this old close came in.
                () -> { if (!a.isDestroyed()) web.evaluateJavascript("Yomisashi.isOpen()", v -> { if ("false".equals(v)) hide(); }); });
        web.addJavascriptInterface(bridge, "YS");
        a.addContentView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        try {
            web.loadDataWithBaseURL("file:///android_asset/", asset("yomisashi.html"), "text/html", "UTF-8", null);
        } catch (Exception e) {
            Log.e(Shelf.TAG, "yomisashi.html", e);
        }
    }

    boolean shown() { return web.getVisibility() == View.VISIBLE; }

    /** the page, for the tests */
    WebView web() { return web; }

    /** Shows the sheet, drawn afresh, in the reader's theme as it is now. */
    void show() {
        if (shown()) return;
        // Not before the page is loaded: a see-through overlay with nothing drawn would take the shelf's taps.
        if (!ready) { wanted = true; return; }
        web.setVisibility(View.VISIBLE);
        web.bringToFront();
        a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back);
        String[] l = look();
        web.evaluateJavascript("__look(" + JSONObject.quote(l[0]) + "," + JSONObject.quote(l[1]) + ");Yomisashi.open()", null);
    }

    /** Hides it; the page's own close (Yomisashi.close) comes back here through {@code closed}. */
    void close() {
        if (ready) web.evaluateJavascript("Yomisashi.close()", null);
        hide();
    }

    private void hide() {
        wanted = false;
        bridge.reset();
        if (!shown()) return;
        web.setVisibility(View.GONE);
        a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);
    }

    void destroy() { web.destroy(); }

    /** {theme, font} of the reader. */
    private String[] look() {
        SharedPreferences p = a.getSharedPreferences("reader", Activity.MODE_PRIVATE);
        String theme = "kinari", font = "serif";
        try {
            String d = p.getString("display", null);
            if (d != null) {
                JSONObject o = new JSONObject(d);
                theme = o.optString("theme", theme);
                font = o.optString("font", font);
            }
        } catch (Exception ignored) {
        }
        return new String[]{theme, font};
    }

    private String asset(String name) throws Exception {
        try (InputStream in = a.getAssets().open(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
