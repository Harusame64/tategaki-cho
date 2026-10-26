package jp.harusame.aozorareader;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 探す: a search of なろう's works through the official API, with its filters (type, genre, length,
 * long-stopped works) and orders. Ordered by daily, weekly … points it is a ranking, filters and all.
 * A work found goes onto the shelf; nothing of its text is fetched from here.
 */
public class SearchActivity extends ThemedActivity {
    /** {label, API value} for each filter; the first of each is "no filter". */
    static final String[][] ORDERS = {{"累計ランキング", "hyoka"}, {"日間ランキング", "dailypoint"}, {"週間ランキング", "weeklypoint"},
            {"月間ランキング", "monthlypoint"}, {"四半期ランキング", "quarterpoint"}, {"年間ランキング", "yearlypoint"},
            {"ブックマーク数", "favnovelcnt"}, {"新着更新", "new"}, {"文字数が多い", "lengthdesc"}};
    static final String[][] TYPES = {{"すべて", ""}, {"完結済み（連載）", "er"}, {"連載中", "r"}, {"短編", "t"},
            {"完結済み＋短編", "ter"}};
    static final String[][] GENRES = {{"全ジャンル", ""}, {"恋愛（すべて）", "b1"}, {"異世界〔恋愛〕", "101"}, {"現実世界〔恋愛〕", "102"},
            {"ファンタジー（すべて）", "b2"}, {"ハイファンタジー", "201"}, {"ローファンタジー", "202"},
            {"文芸（すべて）", "b3"}, {"純文学", "301"}, {"ヒューマンドラマ", "302"}, {"歴史", "303"}, {"推理", "304"},
            {"ホラー", "305"}, {"アクション", "306"}, {"コメディー", "307"},
            {"SF（すべて）", "b4"}, {"VRゲーム", "401"}, {"宇宙", "402"}, {"空想科学", "403"}, {"パニック", "404"},
            {"その他", "b99"}, {"ノンジャンル", "b98"}};
    static final String[][] LENGTHS = {{"文字数：指定なし", ""}, {"〜3万字", "-30000"}, {"3〜10万字", "30000-100000"},
            {"10〜30万字", "100000-300000"}, {"30〜100万字", "300000-1000000"}, {"100万字〜", "1000000-"}};

    final ExecutorService work = Executors.newSingleThreadExecutor();
    EditText word;
    int order, type, genre, length;
    boolean noStopped = true;
    LinearLayout results;
    TextView count, more;
    int next = 1, total;
    int gen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme();
        String o = getIntent().getStringExtra("order");
        for (int i = 0; i < ORDERS.length; i++) if (ORDERS[i][1].equals(o)) order = i;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(16), pad, pad);
        TextView h = text("作品を探す", 22, fg);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(h);

        LinearLayout q = new LinearLayout(this);
        q.setGravity(Gravity.CENTER_VERTICAL);
        q.setPadding(0, dp(10), 0, dp(4));
        word = new EditText(this);
        word.setHint("作品名・作者名・キーワード");
        word.setSingleLine(true);
        word.setTextColor(fg);
        word.setHintTextColor(sub);
        word.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        word.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEARCH || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { search(); return true; }
            return false;
        });
        q.addView(word, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView go = chip("検索");
        go.setOnClickListener(v -> search());
        q.addView(go);
        root.addView(q);

        // The filters, as chips that open their choices.
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(4), 0, dp(4));
        chips.addView(filter(ORDERS, () -> order, i -> order = i));
        chips.addView(filter(TYPES, () -> type, i -> type = i));
        chips.addView(filter(GENRES, () -> genre, i -> genre = i));
        chips.addView(filter(LENGTHS, () -> length, i -> length = i));
        TextView stop = chip("");
        Runnable markStop = () -> stop.setText(noStopped ? "長期停止を除く ✓" : "長期停止も含む");
        markStop.run();
        stop.setOnClickListener(v -> { noStopped = !noStopped; markStop.run(); search(); });
        chips.addView(stop);
        hs.addView(chips);
        root.addView(hs);

        count = text("", 12, sub);
        count.setPadding(0, dp(8), 0, dp(4));
        root.addView(count);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results);
        more = chip("もっと見る");
        more.setGravity(Gravity.CENTER);
        more.setVisibility(View.GONE);
        more.setOnClickListener(v -> page(false));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(12);
        root.addView(more, mp);
        TextView credit = text("作品の情報は「なろうデベロッパー」の小説 API を使っています。", 11, sub);
        credit.setPadding(0, dp(16), 0, 0);
        root.addView(credit);
        sv.addView(root);
        setContentWithTabs(sv, TAB_SEARCH);
        // Something to look at straight away: the ranking asked for, or the 累計 ranking; words narrow it.
        search();
    }

    /** Brought forward from a tab or a ranking menu: a ranking asked for is shown. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String o = intent.getStringExtra("order");
        if (o == null) return;
        setIntent(intent);
        for (int i = 0; i < ORDERS.length; i++) if (ORDERS[i][1].equals(o)) order = i;
        recreate();
    }

    @Override
    protected void onDestroy() {
        work.shutdown();                                                // no interrupt: a worker may be reading the shelf
        super.onDestroy();
    }

    interface IntGet { int get(); }
    interface IntSet { void set(int i); }

    TextView filter(String[][] choices, IntGet get, IntSet set) {
        TextView c = chip(choices[get.get()][0] + " ▾");
        c.setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(this, v);
            for (int i = 0; i < choices.length; i++) m.getMenu().add(0, i, i, choices[i][0]);
            m.setOnMenuItemClickListener(it -> {
                set.set(it.getItemId());
                c.setText(choices[get.get()][0] + " ▾");
                search();
                return true;
            });
            m.show();
        });
        return c;
    }

    TextView chip(String label) {
        TextView c = text(label, 14, fg);
        c.setPadding(dp(12), dp(8), dp(12), dp(8));
        c.setBackground(round(card, dp(18)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    Map<String, String> query() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("word", word.getText().toString().trim());
        p.put("order", ORDERS[order][1]);
        p.put("type", TYPES[type][1]);
        String g = GENRES[genre][1];
        if (g.startsWith("b")) p.put("biggenre", g.substring(1));
        else p.put("genre", g);
        p.put("length", LENGTHS[length][1]);
        if (noStopped) p.put("stop", "1");
        return p;
    }

    void search() {
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(word.getWindowToken(), 0);
        page(true);
    }

    /** One request: the first page of a new search, or the next page of this one. */
    void page(boolean fresh) {
        if (fresh) { next = 1; results.removeAllViews(); count.setText("探しています…"); }
        more.setVisibility(View.GONE);
        int g = fresh ? ++gen : gen;
        Map<String, String> q = query();
        int st = next;
        work.execute(() -> {
            if (g != gen) return;                                            // a newer search was asked for
            try {
                NarouApi.Result r = NarouApi.search(q, st);
                java.util.Set<String> onShelf = new java.util.HashSet<>();
                for (Shelf.Work w : Shelf.load(this).works) onShelf.add(w.ncode);
                runOnUiThread(() -> {
                    if (g != gen) return;
                    total = r.all;
                    for (NarouApi.Novel n : r.novels) results.addView(row(n, onShelf.contains(n.ncode)));
                    next = st + r.novels.size();
                    count.setText(total == 0 ? "見つかりませんでした" : String.format(Locale.JAPAN, "%,d 作品", total));
                    // The API serves up to the 2,000th result.
                    more.setVisibility(r.novels.size() == NarouApi.PAGE && next <= Math.min(total, 2000) ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (g == gen) count.setText("探せませんでした（" + e.getMessage() + "）"); });
            }
        });
    }

    View row(NarouApi.Novel n, boolean onShelf) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(14), 0, dp(14));
        TextView t = text(n.title, 16, fg);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(t);
        String meta = n.writer + "　" + NarouApi.genreName(n.genre) + "　"
                + (n.shortStory ? "短編" : (n.finished ? "完結" : "連載中") + "・全" + n.episodes + "話")
                + "　" + chars(n.length) + "　" + String.format(Locale.JAPAN, "%,dpt", n.globalPoint);
        TextView m = text(meta, 12, sub);
        m.setPadding(0, dp(3), 0, dp(6));
        r.addView(m);
        TextView story = text(n.story.trim(), 13, fg);
        story.setMaxLines(3);
        story.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(story);
        // A tap shows the whole synopsis, and folds it again.
        r.setOnClickListener(v -> story.setMaxLines(story.getMaxLines() == 3 ? Integer.MAX_VALUE : 3));
        LinearLayout acts = new LinearLayout(this);
        acts.setPadding(0, dp(10), 0, 0);
        TextView add = chip(onShelf ? "本棚にあります" : "＋ 本棚に入れる");
        add.setTextColor(onShelf ? sub : accent);
        if (!onShelf) add.setOnClickListener(v -> {
            add.setText("入れています…");
            add.setOnClickListener(null);
            BG.execute(() -> {
                String msg;
                try {
                    Shelf.Work w = Shelf.add(this, "https://ncode.syosetu.com/" + n.ncode + "/");
                    msg = w == null ? "もう本棚にあります" : "本棚に入れました";
                } catch (Exception e) {
                    msg = "入れられませんでした（" + e.getMessage() + "）";
                }
                String s = msg;
                runOnUiThread(() -> { add.setText(s); add.setTextColor(sub); });
            });
        });
        acts.addView(add);
        TextView page = chip("作品ページ");
        page.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://ncode.syosetu.com/" + n.ncode + "/"))));
        acts.addView(page);
        r.addView(acts);
        View sep = new View(this);
        sep.setBackgroundColor(line);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(r);
        box.addView(sep, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return box;
    }

    static String chars(long n) {
        if (n >= 10000) return String.format(Locale.JAPAN, "%.1f万字", n / 10000.0).replace(".0万", "万");
        return String.format(Locale.JAPAN, "%,d字", n);
    }
}
