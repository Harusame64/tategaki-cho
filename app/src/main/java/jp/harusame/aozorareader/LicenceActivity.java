package jp.harusame.aozorareader;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ライセンス: the app's GPLv3 notice and where its source is, the libraries and fonts it ships, and each licence
 * in full. The texts are the repository's own (NOTICE, LICENSE, THIRD-PARTY-NOTICES.txt, licenses/), copied into
 * the assets at build time (app/build.gradle, copyLicences), and the fonts' licences beside the fonts.
 */
public class LicenceActivity extends ThemedActivity {
    static final String SOURCE = "https://github.com/Harusame64/tategaki-cho";
    static final String DIR = "licences/";

    LinearLayout body;
    TextView title;
    /** the asset shown in full, or null for the list */
    String shown;
    final android.window.OnBackInvokedCallback toList = this::showList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme();
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(24));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView back = icon(R.drawable.ic_back, "戻る");
        back.setOnClickListener(v -> { if (shown != null) showList(); else finish(); });
        head.addView(back);
        title = text("ライセンス", 22, fg);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = -dp(12);                                    // the arrow, not its touch target, lines up with the text
        root.addView(head, hlp);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);
        sv.addView(root);
        setContentAlone(sv);
        String open = savedInstanceState == null ? null : savedInstanceState.getString("shown");
        if (open != null) showText(open, savedInstanceState.getString("title", "")); else showList();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("shown", shown);
        out.putString("title", title.getText().toString());
    }

    /** {title, asset path} of every text, the app's own first, then each licence, then the fonts'. */
    List<String[]> entries() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"たてがき帖（GNU GPL v3）", DIR + "NOTICE"});
        out.add(new String[]{"使っているライブラリとフォント", DIR + "THIRD-PARTY-NOTICES.txt"});
        out.add(new String[]{"GNU General Public License v3", DIR + "LICENSE"});
        try {
            String[] files = getAssets().list(DIR.substring(0, DIR.length() - 1));
            Arrays.sort(files);
            for (String f : files) {
                if (f.equals("NOTICE") || f.equals("LICENSE") || f.equals("THIRD-PARTY-NOTICES.txt")) continue;
                out.add(new String[]{name(f), DIR + f});
            }
            String[] fonts = getAssets().list("fonts");
            Arrays.sort(fonts);
            for (String f : fonts) if (f.endsWith("-OFL.txt")) out.add(new String[]{name(f), "fonts/" + f});
        } catch (java.io.IOException ignored) {
            // the three above are still listed
        }
        return out;
    }

    /** "MIT-jsoup.txt" → "MIT License（jsoup）", "bizudmincho-OFL.txt" → "SIL Open Font License 1.1（BIZ UD明朝）" */
    static String name(String file) {
        switch (file) {
            case "Apache-2.0.txt": return "Apache License 2.0";
            case "MIT-jsoup.txt": return "MIT License（jsoup）";
            case "MIT-slf4j.txt": return "MIT License（SLF4J）";
            case "bizudmincho-OFL.txt": return "SIL Open Font License 1.1（BIZ UD明朝）";
            case "shipporimincho-OFL.txt": return "SIL Open Font License 1.1（しっぽり明朝）";
            default: return file.replaceFirst("\\.txt$", "");
        }
    }

    /**
     * The texts are wrapped at about 80 columns for a terminal; on a phone the screen wraps them again, and the
     * lines come out ragged. A line is joined to the one before when both are plain text of one paragraph:
     * not blank, not indented, not a list item or a rule, and the one before long enough to have been wrapped. Rules of = or -
     * are cut short.
     */
    static String reflow(String text) {
        StringBuilder out = new StringBuilder();
        String prev = null;
        for (String l : text.replace("\r\n", "\n").split("\n", -1)) {
            String line = l.matches("\\s*[=-]{8,}\\s*") ? l.trim().substring(0, 8) : l;
            boolean plain = !line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.matches("([-*•]|\\d+\\.|\\(?[a-z0-9]\\)) .*")
                    && !line.matches("[=-]{8}");
            // Only a long line was wrapped; a short one (a title, a copyright line) ends where it ends.
            boolean joins = plain && prev != null && prev.trim().length() >= 60 && !prev.endsWith(":");
            if (prev != null) out.append(joins ? " " : "\n");
            out.append(joins ? line.trim() : line);
            prev = line;
        }
        return out.toString();
    }

    void showList() {
        if (shown != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(toList);
        shown = null;
        title.setText("ライセンス");
        body.removeAllViews();
        TextView intro = text("たてがき帖はフリーソフトウェアです。GNU GPL v3 のもとで、ソースコードを公開しています。", 14, fg);
        intro.setPadding(0, dp(12), 0, dp(4));
        body.addView(intro);
        TextView src = text(SOURCE, 14, accent);
        src.setMinHeight(dp(TOUCH_DP));
        src.setGravity(Gravity.CENTER_VERTICAL);
        src.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE))); } catch (Exception ignored) { }
        });
        body.addView(src);
        for (String[] e : entries()) {
            View rule = new View(this);
            rule.setBackgroundColor(line);
            body.addView(rule, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
            TextView r = text(e[0], 15, fg);
            r.setMinHeight(dp(TOUCH_DP));
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(4), 0, dp(4));
            r.setOnClickListener(v -> showText(e[1], e[0]));
            body.addView(r);
        }
    }

    void showText(String asset, String name) {
        if (shown == null) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, toList);
        shown = asset;
        title.setText(name);
        body.removeAllViews();
        String s;
        try (InputStream in = getAssets().open(asset)) {
            s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            s = "読み込めませんでした: " + asset;
        }
        TextView t = text(reflow(s), 13, fg);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(12), 0, 0);
        body.addView(t);
        ((ScrollView) body.getParent().getParent()).scrollTo(0, 0);
    }
}
