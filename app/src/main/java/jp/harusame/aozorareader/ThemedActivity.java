package jp.harusame.aozorareader;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowInsetsController;
import android.widget.TextView;

import org.json.JSONObject;

/** The shelf and the search screen: the reader's theme, and the small view helpers they share. */
abstract class ThemedActivity extends Activity {
    int bg, fg, sub, line, card, accent;
    /**
     * Shelf writes (add, remove, open) run here: one worker for the app's life, so leaving a screen does not cut
     * an add short, and none waits behind a screen's own long task (a check for new episodes).
     */
    static final java.util.concurrent.ExecutorService BG = java.util.concurrent.Executors.newSingleThreadExecutor();

    static final int TAB_NONE = -1, TAB_SHELF = 0, TAB_SEARCH = 1, TAB_AOZORA = 2;

    /**
     * The screen's content above the tabs shared by the shelf, the search and 青空文庫, so the shelf is one tap
     * from anywhere. A tab brings its screen forward rather than stacking another copy of it.
     */
    void setContentWithTabs(android.view.View content, int current) {
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setBackgroundColor(bg);
        col.addView(content, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        android.view.View rule = new android.view.View(this);
        rule.setBackgroundColor(line);
        col.addView(rule, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1));
        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setBackgroundColor(card);
        String[] names = {"本棚", "探す", "青空文庫"};
        Class<?>[] to = {ShelfActivity.class, SearchActivity.class, AozoraActivity.class};
        for (int i = 0; i < names.length; i++) {
            TextView t = text(names[i], 14, i == current ? accent : sub);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(14), 0, dp(14));
            if (i == current) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            int k = i;
            t.setOnClickListener(v -> {
                if (k == current) return;
                startActivity(new android.content.Intent(this, to[k]).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                overridePendingTransition(0, 0);
            });
            bar.addView(t, new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        col.addView(bar);
        setContentView(col);
        // Edge to edge from Android 15: keep the screen clear of the status and navigation bars.
        col.setFitsSystemWindows(true);
        applyBars();
    }

    /** Dark bar icons on a light background, light ones on a dark one; the window takes the theme's colour. */
    void applyBars() {
        getWindow().getDecorView().setBackgroundColor(bg);
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) bars.setSystemBarsAppearance(Color.luminance(bg) > 0.5f ? light : 0, light);
    }

    GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Android's smallest comfortable touch target (A11yTest checks it). */
    static final int TOUCH_DP = 48;

    TextView iconButton(String label) {
        TextView b = text(label, 22, fg);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(TOUCH_DP));
        b.setMinHeight(dp(TOUCH_DP));
        return b;
    }

    /**
     * A pill button as Material draws chips: the view is a full touch target high, the pill drawn 32dp high
     * in its middle.
     */
    void pill(TextView c, int color) {
        c.setMinHeight(dp(TOUCH_DP));
        c.setMinWidth(dp(TOUCH_DP));                                       // a one-word pill (人気, 保留) too
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(12), 0, dp(12), 0);
        c.setBackground(new android.graphics.drawable.InsetDrawable(round(color, dp(16)), 0, dp((TOUCH_DP - 32) / 2), 0, dp((TOUCH_DP - 32) / 2)));
    }

    int dialogTheme() {
        return Color.luminance(bg) > 0.5f
                ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert;
    }

    /** These screens take the reader's background so they read as one app. */
    void theme() {
        SharedPreferences p = getSharedPreferences("reader", MODE_PRIVATE);
        String t = "kinari";
        try {
            String d = p.getString("display", null);
            if (d != null) t = new JSONObject(d).optString("theme", t);
        } catch (Exception ignored) {
        }
        int[] c = ReaderActivity.themeColors(t);
        bg = c[0]; fg = c[1]; sub = c[2]; line = c[3];
        // The card a shade off the background, the accent the reader's link colour.
        boolean light = Color.luminance(bg) > 0.5f;
        card = light ? blend(bg, 0xFF000000, 0.05f) : blend(bg, 0xFFFFFFFF, 0.07f);
        accent = light ? ("sepia".equals(t) ? 0xFF7A4B12 : 0xFF1A5FB4) : 0xFF8AB4F8;
    }

    static int blend(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = Math.round((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        return t;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
