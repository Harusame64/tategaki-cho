package jp.harusame.aozorareader;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 青空文庫を探す: the official list of works, kept on the phone, browsed by popularity (the published access
 * ranking), by author (五十音), by kind (日本十進分類) or searched by title and author. By default only works in
 * 新字新仮名 are listed; a chip shows every kind of kana usage. A work goes onto the shelf and is read from there.
 */
public class AozoraActivity extends ThemedActivity {
    static final int PAGE = 50;
    static final String[] ROWS = {"あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ"};
    static final String ROW_CHARS = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん";

    final ExecutorService work = Executors.newSingleThreadExecutor();
    List<AozoraSite.Book> books;
    Set<String> onShelf = new HashSet<>();
    boolean modernOnly = true;
    LinearLayout body, tabs;
    TextView status, modern;
    EditText query;
    int tab;
    /** what "もっと見る" continues */
    List<AozoraSite.Book> listing;
    int shown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = sidePad(16);
        root.setPadding(pad, dp(16), pad, dp(16));
        TextView h = text("青空文庫", 22, fg);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(h);
        TextView about = text("著作権の切れた作品などを、誰でも自由に読めるように公開している電子図書館です。", 12, sub);
        about.setPadding(0, dp(2), 0, dp(10));
        root.addView(about);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        String[] names = {"人気", "作家", "分類", "検索"};
        for (int i = 0; i < names.length; i++) {
            int t = i;
            TextView c = chip(names[i]);
            c.setOnClickListener(v -> show(t));
            tabs.addView(c);
        }
        modern = chip("");
        modern.setOnClickListener(v -> { modernOnly = !modernOnly; markTabs(); show(tab); });
        tabs.addView(modern);
        hs.addView(tabs);
        root.addView(hs);
        status = text("", 12, sub);
        status.setPadding(0, dp(10), 0, dp(4));
        root.addView(status);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);
        sv.addView(root);
        setContentWithTabs(sv, TAB_AOZORA);
        markTabs();
        load();
    }

    @Override
    protected void onDestroy() {
        work.shutdown();                                                // no interrupt: a worker may be reading the shelf
        super.onDestroy();
    }

    /** The index: kept, or fetched once (about 2 MB) and kept. */
    void load() {
        status.setText("作品一覧を読み込んでいます…");
        work.execute(() -> {
            try {
                java.io.File idx = AozoraSite.indexFile(this);
                boolean stale = idx.isFile() && System.currentTimeMillis() - idx.lastModified() > AozoraSite.LIST_FRESH_MS;
                if (!idx.isFile() || stale) {
                    runOnUiThread(() -> status.setText(stale ? "青空文庫の作品一覧を新しくしています（約 2 MB、月に1回）…"
                            : "青空文庫の作品一覧を取得しています（約 2 MB、初回だけ）…"));
                    try {
                        AozoraSite.refreshIndex(this);
                        new java.io.File(AozoraSite.dir(this), "ranking.html").delete();
                    } catch (Exception e) {
                        if (!stale) throw e;                                 // an old list still serves
                    }
                }
                List<AozoraSite.Book> bs = AozoraSite.loadIndex(this);
                Set<String> s = new HashSet<>();
                for (Shelf.Work w : Shelf.load(this).works) s.add(w.ncode);
                runOnUiThread(() -> { books = bs; onShelf = s; show(0); });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("作品一覧を取得できませんでした（" + e.getMessage() + "）"));
            }
        });
    }

    void markTabs() {
        for (int i = 0; i < 4; i++) {
            TextView c = (TextView) tabs.getChildAt(i);
            c.setTextColor(i == tab ? accent : fg);
            c.setTypeface(i == tab ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        modern.setText(modernOnly ? "新字新仮名だけ ✓" : "旧字・旧仮名も");
    }

    boolean listed(AozoraSite.Book b) {
        return !modernOnly || b.modern();
    }

    void show(int t) {
        if (books == null || isDestroyed()) return;
        gen++;
        tab = t;
        markTabs();
        body.removeAllViews();
        if (t == 0) popular();
        else if (t == 1) authors();
        else if (t == 2) categories();
        else search();
    }

    /** the listing asked for last; an older one arriving late is dropped */
    int gen;

    void popular() {
        status.setText("ランキングを読み込んでいます…");
        if (work.isShutdown()) return;
        int g = ++gen;
        work.execute(() -> {
            try {
                List<String[]> r = AozoraSite.ranking(this);
                Map<Integer, AozoraSite.Book> byId = new HashMap<>();
                for (AozoraSite.Book b : books) byId.put(Integer.parseInt(b.id), b);
                List<AozoraSite.Book> out = new ArrayList<>();
                Map<AozoraSite.Book, String> hits = new HashMap<>();
                Map<AozoraSite.Book, Integer> rankOf = new HashMap<>();
                int rank = 0;
                for (String[] x : r) {
                    rank++;
                    AozoraSite.Book b = byId.get(Integer.parseInt(x[0]));
                    // The rank is the ranking's own, also when works above it are not listed here.
                    if (b == null || hits.containsKey(b)) continue;
                    hits.put(b, x[1]);
                    rankOf.put(b, rank);
                    if (listed(b)) out.add(b);
                }
                runOnUiThread(() -> {
                    if (tab != 0 || g != gen) return;
                    status.setText(AozoraSite.RANKING_LABEL);
                    for (int i = 0; i < out.size(); i++) {
                        String n = hits.get(out.get(i));
                        body.addView(row(out.get(i), rankOf.get(out.get(i)) + "位" + (n.isEmpty() ? "" : String.format(Locale.JAPAN, "　%,d", Long.parseLong(n)))));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("ランキングを取得できませんでした（" + e.getMessage() + "）"));
            }
        });
    }

    void authors() {
        status.setText("作家の読みの頭で選んでください");
        LinearLayout kana = new LinearLayout(this);
        kana.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = null;
        for (int i = 0; i < ROWS.length; i++) {
            if (i % 5 == 0) { line = new LinearLayout(this); kana.addView(line); }
            int row = i;
            TextView c = chip(ROWS[i] + "行");
            c.setOnClickListener(v -> authorsOf(row));
            line.addView(c);
        }
        body.addView(kana);
    }

    void authorsOf(int row) {
        body.removeAllViews();
        int from = ROW_CHARS.indexOf(ROWS[row]), to = row + 1 < ROWS.length ? ROW_CHARS.indexOf(ROWS[row + 1]) : ROW_CHARS.length();
        String chars = ROW_CHARS.substring(from, to);
        Map<String, List<AozoraSite.Book>> by = new LinkedHashMap<>();
        List<AozoraSite.Book> sorted = new ArrayList<>(books);
        sorted.sort((a, b) -> a.authorYomi.compareTo(b.authorYomi));
        for (AozoraSite.Book b : sorted) {
            if (!listed(b) || b.authorYomi.isEmpty() || chars.indexOf(b.authorYomi.charAt(0)) < 0) continue;
            by.computeIfAbsent(b.author, k -> new ArrayList<>()).add(b);
        }
        status.setText(ROWS[row] + "行の作家　" + by.size() + " 人");
        TextView back = chip("← 五十音");
        back.setOnClickListener(v -> show(1));
        body.addView(back);
        for (Map.Entry<String, List<AozoraSite.Book>> e : by.entrySet()) {
            TextView a = text(e.getKey() + "　（" + e.getValue().size() + "）", 16, fg);
            a.setPadding(0, dp(12), 0, dp(12));
            a.setOnClickListener(v -> {
                List<AozoraSite.Book> ws = new ArrayList<>(e.getValue());
                ws.sort((x, y) -> x.yomi.compareTo(y.yomi));
                body.removeAllViews();
                status.setText(e.getKey() + "　" + ws.size() + " 作品");
                TextView b2 = chip("← " + ROWS[row] + "行の作家");
                b2.setOnClickListener(v2 -> authorsOf(row));
                body.addView(b2);
                startListing(ws);
            });
            body.addView(a);
            body.addView(rule());
        }
    }

    void categories() {
        Map<String, List<AozoraSite.Book>> by = new LinkedHashMap<>();
        for (String c : AozoraSite.CATEGORIES) by.put(c, new ArrayList<>());
        for (AozoraSite.Book b : books) if (listed(b)) by.get(AozoraSite.category(b.ndc)).add(b);
        status.setText("分類（日本十進分類から）");
        for (Map.Entry<String, List<AozoraSite.Book>> e : by.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            TextView a = text(e.getKey() + "　（" + e.getValue().size() + "）", 16, fg);
            a.setPadding(0, dp(12), 0, dp(12));
            a.setOnClickListener(v -> {
                List<AozoraSite.Book> ws = new ArrayList<>(e.getValue());
                ws.sort((x, y) -> x.yomi.compareTo(y.yomi));
                body.removeAllViews();
                status.setText(e.getKey() + "　" + ws.size() + " 作品（作品名の読み順）");
                TextView back = chip("← 分類");
                back.setOnClickListener(v2 -> show(2));
                body.addView(back);
                startListing(ws);
            });
            body.addView(a);
            body.addView(rule());
        }
    }

    void search() {
        status.setText("作品名・作家名で探す");
        LinearLayout q = new LinearLayout(this);
        q.setGravity(Gravity.CENTER_VERTICAL);
        query = new EditText(this);
        query.setHint("例：こころ　宮沢賢治");
        query.setSingleLine(true);
        query.setTextColor(fg);
        query.setHintTextColor(sub);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, id, e) -> { runSearch(); return true; });
        q.addView(query, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView go = chip("検索");
        go.setOnClickListener(v -> runSearch());
        q.addView(go);
        body.addView(q);
        query.requestFocus();
    }

    void runSearch() {
        InputMethodManager im = getSystemService(InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(query.getWindowToken(), 0);
        String[] words = query.getText().toString().trim().split("[\\s　]+");
        List<AozoraSite.Book> out = new ArrayList<>();
        for (AozoraSite.Book b : books) {
            if (!listed(b)) continue;
            String hay = b.title + " " + b.subtitle + " " + b.author.replace(" ", "") + " " + b.author + " " + b.translator;
            boolean all = true;
            for (String w : words) if (!w.isEmpty() && !hay.contains(w)) { all = false; break; }
            if (all && words.length > 0 && !words[0].isEmpty()) out.add(b);
        }
        out.sort((x, y) -> x.yomi.compareTo(y.yomi));
        while (body.getChildCount() > 1) body.removeViewAt(1);
        status.setText(out.isEmpty() ? "見つかりませんでした" : out.size() + " 作品");
        startListing(out);
    }

    void startListing(List<AozoraSite.Book> ws) {
        listing = ws;
        shown = 0;
        more();
    }

    /** The next PAGE works of the listing, then a もっと見る while any are left. */
    void more() {
        if (body.getChildCount() > 0 && "more".equals(body.getChildAt(body.getChildCount() - 1).getTag()))
            body.removeViewAt(body.getChildCount() - 1);
        int end = Math.min(listing.size(), shown + PAGE);
        for (int i = shown; i < end; i++) body.addView(row(listing.get(i), null));
        shown = end;
        if (shown < listing.size()) {
            TextView m = chip("もっと見る（残り " + (listing.size() - shown) + "）");
            m.setTag("more");
            m.setGravity(Gravity.CENTER);
            m.setOnClickListener(v -> more());
            body.addView(m);
        }
    }

    View row(AozoraSite.Book b, String rank) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        if (rank != null) r.addView(text(rank, 12, accent));
        TextView t = text(b.title + (b.subtitle.isEmpty() ? "" : "　" + b.subtitle), 16, fg);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(t);
        String meta = b.author + (b.translator.isEmpty() ? "" : "（" + b.translator + " 訳）") + "　" + AozoraSite.category(b.ndc)
                + (b.modern() ? "" : "　" + b.kana);
        TextView m = text(meta, 12, sub);
        m.setPadding(0, dp(3), 0, dp(8));
        r.addView(m);
        LinearLayout acts = new LinearLayout(this);
        String key = Shelf.AO + b.id;
        boolean has = onShelf.contains(key);
        TextView add = chip(has ? "本棚で読む" : "＋ 本棚に入れて読む");
        add.setTextColor(accent);
        add.setOnClickListener(v -> BG.execute(() -> {
            try {
                if (!has) Shelf.addAozora(this, b);
                Shelf s = Shelf.load(this);
                Shelf.Work w = s.find(key);
                runOnUiThread(() -> {
                    onShelf.add(key);
                    if (w != null) startActivity(new Intent(this, ReaderActivity.class)
                            .putExtra("url", Shelf.episodeUrl(key, Math.max(1, w.lastEpisode))).putExtra("page", w.lastPage));
                });
            } catch (Exception e) {
                runOnUiThread(() -> android.widget.Toast.makeText(this, "入れられませんでした（" + e.getMessage() + "）", android.widget.Toast.LENGTH_SHORT).show());
            }
        }));
        acts.addView(add);
        TextView card = chip("図書カード");
        card.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(b.card))));
        acts.addView(card);
        r.addView(acts);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(r);
        box.addView(rule());
        return box;
    }

    View rule() {
        View sep = new View(this);
        sep.setBackgroundColor(line);
        sep.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return sep;
    }

    TextView chip(String label) {
        TextView c = text(label, 14, fg);
        pill(c, card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }
}
