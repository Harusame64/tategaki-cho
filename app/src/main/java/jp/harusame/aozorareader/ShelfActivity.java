package jp.harusame.aozorareader;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The bookshelf: the works the user follows, with where they are and how many episodes are new.
 * On top, the work last read, to go on with it; below, every work in the chosen order, with how far in
 * it the user is. A work is added by sharing its なろう page to the app or pasting its URL (＋); a long
 * press removes it and its kept episodes.
 */
public class ShelfActivity extends ThemedActivity {
    /** How long a check of the episode counts stays fresh before opening the shelf asks again. */
    static final long CHECK_EVERY_MS = 60 * 60 * 1000L;
    final ExecutorService work = Executors.newSingleThreadExecutor();
    Shelf shelf;
    LinearLayout list, resume, selectBar, tags;
    TextView status, sortLabel, selectCount;
    EditText filter;
    /** works picked to take off together; null when not picking */
    java.util.Set<String> picked;
    static final String[] SORTS = {"最近読んだ順", "新着順", "追加した順"};
    int sort;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(20), pad, pad);
        sort = getSharedPreferences("reader", MODE_PRIVATE).getInt("shelfSort", 0);
        Guide.ensure(this);                                                 // the bundled book, on a new shelf
        // 本棚 ……… ⟳ ＋
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = text("本棚", 24, fg);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(h, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView find = iconButton("🔍");
        find.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        find.setContentDescription("本棚を検索");
        find.setOnClickListener(v -> {
            boolean on = filter.getVisibility() != View.VISIBLE;
            filter.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) filter.requestFocus(); else filter.setText("");
        });
        head.addView(find);
        TextView check = iconButton("⟳");
        check.setContentDescription("新着を確認");
        check.setOnClickListener(v -> { status.setText("新着を確認しています…"); refresh(true); });
        head.addView(check);
        TextView add = iconButton("＋");
        add.setContentDescription("作品を足す");
        add.setOnClickListener(this::addMenu);
        head.addView(add);
        TextView more = iconButton("⋮");
        more.setContentDescription("その他");
        more.setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(this, v);
            m.getMenu().add(0, 1, 0, "まとめて外す");
            m.getMenu().add(0, 2, 1, "保存容量の整理");
            m.getMenu().add(0, 3, 2, "読書の記録");
            m.getMenu().add(0, 4, 3, "新着の確認…");
            m.setOnMenuItemClickListener(i -> {
                if (i.getItemId() == 1) startPicking();
                else if (i.getItemId() == 2) storage();
                else if (i.getItemId() == 3) startActivity(new Intent(this, LogActivity.class));
                else checkSettings();
                return true;
            });
            m.show();
        });
        head.addView(more);
        root.addView(head);
        // 本棚を検索: narrows the list by title or author as it is typed.
        filter = new EditText(this);
        filter.setHint("作品名・作者名で絞り込む");
        filter.setSingleLine(true);
        filter.setTextColor(fg);
        filter.setHintTextColor(sub);
        filter.setVisibility(View.GONE);
        filter.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) { render(); }
        });
        root.addView(filter);
        // Tags: すべて, 読んでる, 読み終えた, 保留, and the user's own.
        HorizontalScrollView ts = new HorizontalScrollView(this);
        ts.setHorizontalScrollBarEnabled(false);
        tags = new LinearLayout(this);
        tags.setPadding(0, dp(10), 0, 0);
        ts.addView(tags);
        root.addView(ts);
        tag = getSharedPreferences("reader", MODE_PRIVATE).getString("shelfTag", null);
        // まとめて外す: N 作品を選択中 … すべて選ぶ / 外す / やめる
        selectBar = new LinearLayout(this);
        selectBar.setGravity(Gravity.CENTER_VERTICAL);
        selectBar.setPadding(dp(12), dp(8), dp(8), dp(8));
        selectBar.setBackground(round(card, dp(12)));
        selectCount = text("", 14, fg);
        selectBar.addView(selectCount, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView all = barButton("すべて選ぶ", fg);
        all.setOnClickListener(v -> {
            boolean every = picked.size() == shown().size();
            picked.clear();
            if (!every) for (Shelf.Work w : shown()) picked.add(w.ncode);
            render();
        });
        selectBar.addView(all);
        TextView drop = barButton("外す", 0xFFD0453A);
        drop.setOnClickListener(v -> askRemovePicked());
        selectBar.addView(drop);
        TextView cancel = barButton("やめる", sub);
        cancel.setOnClickListener(v -> { picked = null; render(); });
        selectBar.addView(cancel);
        selectBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        root.addView(selectBar, sp);
        resume = new LinearLayout(this);
        resume.setOrientation(LinearLayout.VERTICAL);
        root.addView(resume);
        sortLabel = text("", 14, sub);
        sortLabel.setPadding(0, dp(14), 0, dp(4));
        sortLabel.setMinHeight(dp(TOUCH_DP));
        sortLabel.setOnClickListener(this::sortMenu);
        root.addView(sortLabel);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        status = text("", 12, sub);
        status.setPadding(0, dp(14), 0, 0);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sv.addView(root);
        setContentWithTabs(sv, TAB_SHELF);
        shelf = Shelf.load(this);
        // The background check as the settings say (they may have come back with a backup, without the schedule).
        CheckWorker.apply(this);
        // 青空文庫 works' details were kept in episodes/ before; meta() moves each to works/ (backed up).
        BG.execute(() -> {
            for (Shelf.Work w : Shelf.load(this).works)
                if (Shelf.isAozora(w.ncode)) try { AozoraSite.meta(this, w.ncode); } catch (Exception ignored) { }
        });
        // Made again after the process ended, the launch intent is the old share: it is not added again.
        if (savedInstanceState == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0)
            handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    /** the shelf is in front (between onResume and onPause) */
    volatile boolean resumed;

    @Override
    protected void onPause() {
        resumed = false;
        FullSave.listener = null;
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        FullSave.listener = this::showSaving;
        // Not while a shared page is being added, nor once right after: that add has just asked the site. Decided
        // here, when the shelf comes forward, not when the worker gets to it (the add may be over by then).
        boolean skip = adding.get() > 0 || System.currentTimeMillis() - skipUntil < 0;
        skipUntil = 0;
        CheckWorker.clearNews(this);                                        // the shelf is in front: its news is seen
        ReaderActivity.run(work, () -> {
            Shelf s = Shelf.load(this);
            runOnUiThread(() -> { shelf = s; render(); });
            if (System.currentTimeMillis() - s.checked > CHECK_EVERY_MS && !skip) refresh(false);
        });
    }

    @Override
    protected void onDestroy() {
        work.shutdown();                                                // no interrupt: a worker may be reading the shelf
        super.onDestroy();
    }

    /** A なろう page shared from the browser adds its work. */
    void handleShare(Intent in) {
        if (in == null || !Intent.ACTION_SEND.equals(in.getAction())) return;
        setIntent(new Intent(this, ShelfActivity.class));
        addWork(in.getStringExtra(Intent.EXTRA_TEXT), true);
    }

    /** ＋: search or a ranking through the official API, or paste a URL. */
    void addMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "作品を探す");
        android.view.SubMenu rank = m.getMenu().addSubMenu(0, 2, 1, "ランキング");
        String[][] ranks = {{"日間", "dailypoint"}, {"週間", "weeklypoint"}, {"月間", "monthlypoint"},
                {"四半期", "quarterpoint"}, {"年間", "yearlypoint"}, {"累計", "hyoka"}};
        for (int i = 0; i < ranks.length; i++) rank.add(1, 10 + i, i, ranks[i][0]);
        m.getMenu().add(0, 3, 2, "URL を貼って足す");
        m.getMenu().add(0, 4, 3, "青空文庫から探す");
        if (shelf.find(Guide.KEY) == null) m.getMenu().add(0, 5, 4, "使い方の本を本棚に戻す");
        m.setOnMenuItemClickListener(i -> {
            int id = i.getItemId();
            if (id == 1) startActivity(new Intent(this, SearchActivity.class));
            else if (id >= 10) startActivity(new Intent(this, SearchActivity.class).putExtra("order", ranks[id - 10][1]));
            else if (id == 3) askUrl();
            else if (id == 4) startActivity(new Intent(this, AozoraActivity.class));
            else if (id == 5) BG.execute(() -> {
                try {
                    Guide.add(this);
                    Shelf s = Shelf.load(this);
                    runOnUiThread(() -> { shelf = s; render(); });
                } catch (Exception e) {
                    Log.e(Shelf.TAG, "guide", e);
                }
            });
            return true;
        });
        m.show();
    }

    void askUrl() {
        EditText e = new EditText(this);
        e.setHint("なろう・カクヨムの作品の URL");
        e.setSingleLine(true);
        // A なろう URL already on the clipboard is offered as it is.
        ClipboardManager cb = getSystemService(ClipboardManager.class);
        if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip().getItemCount() > 0) {
            CharSequence c = cb.getPrimaryClip().getItemAt(0).getText();
            if (c != null && Shelf.ncodeOf(c.toString()) != null) e.setText(c);
        }
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("作品の URL")
                .setMessage("作品のページか、話のページの URL")
                .setView(box)
                .setPositiveButton("足す", (d, i) -> addWork(e.getText().toString()))
                .setNegativeButton("やめる", null)
                .show();
    }

    void addWork(String text) {
        addWork(text, false);
    }

    /**
     * @param read open the work right away (a page shared to the app): at the episode shared, or where the
     *        work was left; a work already on the shelf opens at the episode shared too
     */
    /** shared pages being added; no check runs meanwhile */
    final java.util.concurrent.atomic.AtomicInteger adding = new java.util.concurrent.atomic.AtomicInteger();
    /**
     * A shared page was just added and opened: the shelf's next automatic check, on coming back from the reader
     * within a few minutes, is skipped once (the add has just asked the site).
     */
    volatile long skipUntil;

    void addWork(String text, boolean read) {
        adding.incrementAndGet();
        BG.execute(() -> {
            String msg;
            boolean ok = false;
            try {
                Shelf.Work w = Shelf.add(this, text);
                msg = w == null ? "もう本棚にあります" : "本棚に足しました：" + w.title;
                ok = true;
            } catch (Exception e) {
                msg = "足せませんでした（" + e.getMessage() + "）";
            }
            String kid = read ? Shelf.sharedKakuyomuId(text) : null;
            String kkey = Shelf.ncodeOf(text);
            if (kid != null && ok && Shelf.isKakuyomu(kkey)) {
                // An episode newer than the kept list: the list is read again once (the work is on the shelf).
                try { KakuyomuSite.listWith(this, kkey, kid); } catch (Exception e) { Log.w(ReaderActivity.TAG, "list for shared episode", e); }
            }
            int no = read ? Shelf.sharedEpisode(this, text) : 0;
            String key = Shelf.ncodeOf(text);
            Shelf s;
            try {
                s = Shelf.update(this, sh -> {
                    Shelf.Work x = key == null ? null : sh.find(key);
                    // By id when both have one; a place kept as a number by an earlier build compares numbers.
                    boolean other = kid != null && x != null && x.lastEpisodeId != null ? !kid.equals(x.lastEpisodeId)
                            : no != (x == null ? 0 : x.lastEpisode);
                    if (x != null && no > 0 && other) { x.lastEpisode = no; x.lastEpisodeId = kid; x.lastPage = 0; }
                });
            } catch (Exception e) {
                s = Shelf.load(this);
            }
            Shelf.Work w = ok && key != null ? s.find(key) : null;
            Shelf fs = s;
            String m = msg;
            if (ok && read) skipUntil = System.currentTimeMillis() + 5 * 60 * 1000;
            adding.decrementAndGet();
            runOnUiThread(() -> {
                shelf = fs;
                render();
                Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
                if (read && w != null) open(w);
            });
        });
    }

    /** One API request for every work on the shelf. */
    void refresh(boolean asked) {
        if (work.isShutdown()) return;                                      // the screen has gone
        ReaderActivity.run(work, () -> {
            Shelf s = Shelf.load(this);
            String msg;
            try {
                List<Shelf.Work> gone = s.refresh(new ArrayList<>(s.works), this);
                msg = gone.isEmpty() ? null : gone.size() + " 作品が見つかりません（削除か非公開）"
                        + (Shelf.DROP_GONE ? "。1日たっても見つからなければ、保存した話を消します" : "");
                // The shelf shows this check's news itself (also one a background round finished meanwhile),
                // when it is in front to show it.
                if (resumed) CheckWorker.clearNews(this);
            } catch (Exception e) {
                msg = asked ? "確認できませんでした（" + e.getMessage() + "）" : null;
            }
            String m = msg;
            runOnUiThread(() -> {
                shelf = s;
                render();
                if (m != null) Toast.makeText(this, m, Toast.LENGTH_LONG).show();
            });
        });
    }

    /** The works the list shows: those with the tag chosen, whose title, author or tags hold the words typed. */
    List<Shelf.Work> shown() {
        String q = filter == null ? "" : filter.getText().toString();
        List<Shelf.Work> out = new ArrayList<>();
        for (Shelf.Work w : shelf.works) if (Shelf.matches(w, q, tag)) out.add(w);
        return out;
    }

    /** the tag the list is narrowed to; null for every work */
    String tag;

    /** すべて, then each tag: a tap narrows the list to it. */
    void renderTags() {
        tags.removeAllViews();
        List<String> all = Shelf.allTags(shelf.works);
        if (tag != null && !all.contains(tag)) tag = null;
        tags.addView(tagChip("すべて", tag == null, () -> tag = null));
        for (String t : all) tags.addView(tagChip(t, t.equals(tag), () -> tag = t));
    }

    TextView tagChip(String label, boolean on, Runnable pick) {
        TextView c = text(label, 13, on ? 0xFFFFFFFF : fg);
        pill(c, on ? accent : card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        c.setLayoutParams(lp);
        c.setOnClickListener(v -> {
            pick.run();
            getSharedPreferences("reader", MODE_PRIVATE).edit().putString("shelfTag", tag).apply();
            render();
        });
        return c;
    }

    /** タグ: the work's tags to tick, and a new one to type. */
    void editTags(Shelf.Work w) {
        List<String> all = Shelf.allTags(shelf.works);
        boolean[] on = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) on[i] = w.tags.contains(all.get(i));
        EditText add = new EditText(this);
        add.setHint("新しいタグ");
        add.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), 0, dp(20), 0);
        box.addView(add, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("タグ：" + w.title)
                .setMultiChoiceItems(all.toArray(new String[0]), on, (d, i, checked) -> on[i] = checked)
                .setView(box)
                .setPositiveButton("決める", (d, x) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < all.size(); i++) if (on[i]) chosen.add(all.get(i));
                    String typed = add.getText().toString().trim();
                    // "すべて" is the chip for every work, not a tag.
                    if (!typed.isEmpty() && !typed.equals("すべて") && !chosen.contains(typed)) chosen.add(typed);
                    BG.execute(() -> {
                        try {
                            Shelf s = Shelf.update(this, sh -> {
                                Shelf.Work cur = sh.find(w.ncode);
                                if (cur != null) { cur.tags.clear(); cur.tags.addAll(chosen); }
                            });
                            runOnUiThread(() -> { shelf = s; render(); });
                        } catch (Exception e) {
                            Log.e(Shelf.TAG, "tags", e);
                        }
                    });
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        // Only a refusal turns it off: empty results mean the request was interrupted, not answered.
        if (code == 1 && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_DENIED) {
            // Without notifications the check would fetch with nothing to show: it is turned off again.
            getSharedPreferences("reader", MODE_PRIVATE).edit().putInt("checkHours", 0).apply();
            CheckWorker.apply(this);
            Toast.makeText(this, "通知が許可されなかったので、新着の確認は「しない」にしました", Toast.LENGTH_LONG).show();
        }
    }

    /** 新着の確認: how often to check in the background (off by default), and on Wi-Fi only or not. */
    void checkSettings() {
        android.content.SharedPreferences p = getSharedPreferences("reader", MODE_PRIVATE);
        String[] labels = {"しない", "3時間ごと", "6時間ごと", "12時間ごと", "24時間ごと"};
        int now = 0;
        for (int i = 0; i < CheckWorker.HOURS.length; i++) if (CheckWorker.HOURS[i] == p.getInt("checkHours", 0)) now = i;
        int[] pick = {now};
        boolean[] wifi = {p.getBoolean("checkWifi", false)};
        android.widget.CheckBox box = new android.widget.CheckBox(this);
        box.setText("Wi-Fi のときだけ");
        box.setChecked(wifi[0]);
        box.setOnCheckedChangeListener((b, on) -> wifi[0] = on);
        LinearLayout v = new LinearLayout(this);
        v.setPadding(dp(20), 0, dp(20), 0);
        v.addView(box);
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("新着の確認（アプリを閉じていても）")
                .setSingleChoiceItems(labels, now, (d, i) -> pick[0] = i)
                .setView(v)
                .setPositiveButton("決める", (d, i) -> {
                    p.edit().putInt("checkHours", CheckWorker.HOURS[pick[0]]).putBoolean("checkWifi", wifi[0]).apply();
                    CheckWorker.apply(this);
                    if (CheckWorker.HOURS[pick[0]] > 0
                            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    /**
     * 保存容量の整理: what the kept episodes take, per work and in all, and ways to free it — the episodes
     * read past, every episode of a work, and what works no longer on the shelf left behind.
     */
    void storage() {
        ReaderActivity.run(work, () -> {
            Shelf s = Shelf.load(this);
            List<Shelf.Kept> ks = s.kept();
            runOnUiThread(() -> showStorage(s, ks));
        });
    }

    void showStorage(Shelf s, List<Shelf.Kept> ks) {
        if (s.unread) {
            // Without the shelf every folder looks left behind: nothing is offered for deletion.
            Toast.makeText(this, "本棚を読めませんでした。もう一度開いてください", Toast.LENGTH_LONG).show();
            return;
        }
        long total = 0, orphan = 0;
        for (Shelf.Kept k : ks) { total += k.bytes; if (s.find(k.ncode) == null) orphan += k.bytes; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), 0);
        box.addView(text("保存した話の合計：" + size(total), 15, fg));
        TextView note = text("作品をタップすると、読み終えた話だけ、またはすべてを消せます。本棚からは外れません。", 12, sub);
        note.setPadding(0, dp(4), 0, dp(8));
        box.addView(note);
        AlertDialog[] dlg = new AlertDialog[1];
        ks.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        for (Shelf.Kept k : ks) {
            Shelf.Work w = s.find(k.ncode);
            if (w == null) continue;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(0, dp(10), 0, dp(10));
            TextView t = text(w.title, 14, fg);
            t.setMaxLines(1);
            t.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(t);
            r.addView(text((Shelf.isAozora(k.ncode) ? "本文　" : k.episodes + " 話　") + size(k.bytes), 12, sub));
            r.setOnClickListener(v -> {
                PopupMenu m = new PopupMenu(this, v);
                m.getMenu().add(0, 1, 0, "読み終えた話を消す");
                m.getMenu().add(0, 2, 1, "保存した話をすべて消す");
                m.setOnMenuItemClickListener(i -> {
                    boolean all = i.getItemId() == 2;
                    ReaderActivity.run(work, () -> {
                        Shelf cur = Shelf.load(this);
                        long freed = FullSave.prune(this, cur, k.ncode, all);
                        List<Shelf.Kept> again = cur.kept();
                        runOnUiThread(() -> {
                            Toast.makeText(this, size(freed) + " 空けました", Toast.LENGTH_SHORT).show();
                            dlg[0].dismiss();
                            showStorage(cur, again);
                        });
                    });
                    return true;
                });
                m.show();
            });
            box.addView(r);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        long orphanBytes = orphan;
        AlertDialog.Builder b = new AlertDialog.Builder(this, dialogTheme())
                .setTitle("保存容量の整理")
                .setView(sv)
                .setNegativeButton("閉じる", null)
                .setPositiveButton("読み終えた話をすべて消す", (d, i) -> ReaderActivity.run(work, () -> {
                    Shelf cur = Shelf.load(this);
                    long freed = 0;
                    for (Shelf.Kept k : cur.kept()) freed += FullSave.prune(this, cur, k.ncode, false);
                    long f = freed;
                    runOnUiThread(() -> Toast.makeText(this, size(f) + " 空けました", Toast.LENGTH_SHORT).show());
                }));
        if (orphanBytes > 0) b.setNeutralButton("残りを掃除（" + size(orphanBytes) + "）", (d, i) -> ReaderActivity.run(work, () -> {
            Shelf cur = Shelf.load(this);
            long freed = 0;
            for (Shelf.Kept k : cur.kept()) if (cur.find(k.ncode) == null) freed += FullSave.prune(this, cur, k.ncode, true);
            long f = freed;
            runOnUiThread(() -> Toast.makeText(this, size(f) + " 空けました", Toast.LENGTH_SHORT).show());
        }));
        dlg[0] = b.show();
    }

    static String size(long bytes) {
        if (bytes >= 1024L * 1024) return String.format(Locale.JAPAN, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.JAPAN, "%d KB", (bytes + 1023) / 1024);
    }

    void startPicking() {
        picked = new java.util.HashSet<>();
        render();
    }

    void askRemovePicked() {
        if (picked == null || picked.isEmpty()) {
            Toast.makeText(this, "外す作品を選んでください", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.Set<String> codes = new java.util.HashSet<>(picked);
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(codes.size() + " 作品を外します")
                .setMessage("本棚から外し、その作品の保存した話も消えます。")
                .setPositiveButton("外す", (d, i) -> BG.execute(() -> {
                    try {
                        Shelf s = Shelf.remove(this, codes);
                        runOnUiThread(() -> { shelf = s; picked = null; render(); });
                    } catch (Exception e) {
                        Log.e(Shelf.TAG, "remove", e);
                    }
                }))
                .setNegativeButton("やめる", null)
                .show();
    }

    TextView barButton(String label, int color) {
        TextView b = text(label, 14, color);
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        return b;
    }

    /** each row's meta line, so 全話を保存's count changes that line alone (a whole render would cut a long-press short) */
    final java.util.Map<String, TextView> savingMeta = new java.util.HashMap<>();

    void showSaving(String key, TextView m) {
        int[] p = FullSave.PROGRESS.get(key);
        String base = (String) m.getTag();
        m.setText(p == null ? base : base + (FullSave.STOP.contains(key) ? "　全話の保存を止めています…"
                : "　全話を保存中 " + (p[1] == 0 ? "…" : p[0] + " / " + p[1])));
    }

    void showSaving() {
        for (java.util.Map.Entry<String, TextView> e : savingMeta.entrySet()) showSaving(e.getKey(), e.getValue());
    }

    /** はじめかた: on a shelf with no work yet (the bundled book aside), the ways to put one there. */
    View startCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(round(card, dp(12)));
        TextView t = text("はじめかた", 16, fg);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        TextView note = text("作品を本棚に入れると、縦書きで読めます。ブラウザで小説家になろう・カクヨムの作品を開き、「共有」からたてがき帖を選んでも入ります。", 13, sub);
        note.setPadding(0, dp(6), 0, dp(8));
        box.addView(note);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        String[] labels = {"作品を探す", "ランキング", "青空文庫から探す", "URL を貼って足す"};
        for (int i = 0; i < labels.length; i++) {
            TextView b = text(labels[i], 15, accent);
            b.setPadding(0, dp(10), 0, dp(10));
            int n = i;
            b.setOnClickListener(v -> {
                if (n == 0) startActivity(new Intent(this, SearchActivity.class));
                else if (n == 1) startActivity(new Intent(this, SearchActivity.class).putExtra("order", "dailypoint"));
                else if (n == 2) startActivity(new Intent(this, AozoraActivity.class));
                else askUrl();
            });
            row.addView(b);
        }
        box.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        box.setLayoutParams(lp);
        return box;
    }

    void render() {
        renderTags();
        list.removeAllViews();
        savingMeta.clear();
        resume.removeAllViews();
        selectBar.setVisibility(picked == null ? View.GONE : View.VISIBLE);
        if (picked != null) selectCount.setText(picked.size() + " 作品を選択中");
        sortLabel.setText(SORTS[sort] + " ▾");
        sortLabel.setVisibility(shelf.works.isEmpty() ? View.GONE : View.VISIBLE);
        boolean onlyGuide = true;
        for (Shelf.Work w : shelf.works) if (!Guide.is(w.ncode)) { onlyGuide = false; break; }
        if (onlyGuide && picked == null) list.addView(startCard());
        // 続きを読む: the work read last.
        Shelf.Work last = null;
        for (Shelf.Work w : shelf.works) if (w.lastRead > 0 && (last == null || w.lastRead > last.lastRead)) last = w;
        boolean narrowing = filter.getText().toString().trim().length() > 0 || tag != null;
        if (last != null && picked == null && !narrowing) resume.addView(resumeCard(last));
        List<Shelf.Work> ws = shown();
        if (narrowing && ws.isEmpty()) {
            TextView t = text("当てはまる作品がありません", 14, sub);
            t.setPadding(0, dp(16), 0, 0);
            list.addView(t);
        }
        Comparator<Shelf.Work> byRead = Comparator.comparingLong(w -> -w.lastRead);
        Comparator<Shelf.Work> byAdded = Comparator.comparingLong(w -> -w.added);
        if (sort == 0) ws.sort(byRead.thenComparing(byAdded));
        else if (sort == 1) ws.sort(Comparator.comparingInt((Shelf.Work w) -> -w.newEpisodes()).thenComparing(byRead));
        else ws.sort(byAdded);
        for (Shelf.Work w : ws) list.addView(row(w));
        status.setText(shelf.checked == 0 ? "" : "新着の確認：" + android.text.format.DateFormat.format("M/d HH:mm", shelf.checked));
    }

    void sortMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        for (int i = 0; i < SORTS.length; i++) m.getMenu().add(0, i, i, SORTS[i]);
        m.setOnMenuItemClickListener(i -> {
            sort = i.getItemId();
            getSharedPreferences("reader", MODE_PRIVATE).edit().putInt("shelfSort", sort).apply();
            render();
            return true;
        });
        m.show();
    }

    View resumeCard(Shelf.Work w) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(round(card, dp(14)));
        TextView k = text("▶  続きを読む", 13, accent);
        k.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(k);
        TextView t = text(w.title, 17, fg);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, dp(6), 0, dp(2));
        c.addView(t);
        String where = w.shortStory ? "短編" : "第" + Math.max(1, w.lastEpisode) + "話";
        if (w.lastPages > 0) where += "　" + (w.lastPage + 1) + " / " + w.lastPages + " ページ";
        c.addView(text(where, 13, sub));
        c.setOnClickListener(v -> open(w));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        c.setLayoutParams(lp);
        return c;
    }

    View row(Shelf.Work w) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(14), 0, dp(14));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.TOP);
        TextView t = text(w.title, 16, fg);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (w.newEpisodes() > 0) {
            TextView n = text("新着 " + w.newEpisodes(), 12, 0xFFFFFFFF);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setPadding(dp(8), dp(2), dp(8), dp(2));
            n.setBackground(round(0xFFD0453A, dp(10)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            top.addView(n, lp);
        }
        r.addView(top);
        String meta = (w.author == null || w.author.isEmpty() ? "" : w.author + "　")
                + (Guide.is(w.ncode) ? "使い方" : Shelf.isAozora(w.ncode) ? "青空文庫" : w.shortStory ? "短編" : w.finished ? "完結" : "連載中");
        TextView m = text(meta, 13, sub);
        m.setTag(meta);
        savingMeta.put(w.ncode, m);
        showSaving(w.ncode, m);
        m.setPadding(0, dp(3), 0, dp(8));
        r.addView(m);
        if (!w.tags.isEmpty()) {
            TextView tg = text("#" + String.join("　#", w.tags), 12, accent);
            tg.setPadding(0, 0, 0, dp(6));
            r.addView(tg);
        }
        if (!w.shortStory && w.episodes > 0) {
            // How far in: the episode last open of all the episodes there are.
            LinearLayout prog = new LinearLayout(this);
            prog.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout bar = new LinearLayout(this);
            bar.setBackground(round(line, dp(3)));
            View done = new View(this);
            done.setBackground(round(accent, dp(3)));
            int read = Math.min(w.lastEpisode, w.episodes);
            bar.addView(done, new LinearLayout.LayoutParams(0, dp(5), read));
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, dp(5), w.episodes - read));
            prog.addView(bar, new LinearLayout.LayoutParams(0, dp(5), 1));
            String unit = Shelf.isAozora(w.ncode) ? "" : " 話";
            TextView n = text(read == 0 ? "未読・全 " + w.episodes + unit : read + " / " + w.episodes + unit, 12, sub);
            n.setPadding(dp(10), 0, 0, 0);
            prog.addView(n);
            r.addView(prog);
        }
        if (picked != null) {
            // Picking: a tap marks or unmarks the work instead of opening it.
            boolean on = picked.contains(w.ncode);
            r.setBackground(on ? round(card, dp(10)) : null);
            t.setText((on ? "☑ " : "☐ ") + w.title);
            r.setOnClickListener(v -> { if (!picked.remove(w.ncode)) picked.add(w.ncode); render(); });
        } else {
            r.setOnClickListener(v -> open(w));
            r.setOnLongClickListener(v -> { workMenu(v, w); return true; });
        }
        View sep = new View(this);
        sep.setBackgroundColor(line);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(r);
        box.addView(sep, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return box;
    }

    /** Opens a work where it was left; its new episodes count as seen. Not queued behind a check. */
    void open(Shelf.Work w) {
        BG.execute(() -> {
            try {
                Shelf s = Shelf.update(this, sh -> {
                    Shelf.Work cur = sh.find(w.ncode);
                    if (cur == null) return;
                    cur.seen = cur.episodes;
                    if (Shelf.isKakuyomu(cur.ncode)) KakuyomuSite.seeAll(this, cur);
                });
                Shelf.Work cur = s.find(w.ncode);
                if (cur == null) return;
                runOnUiThread(() -> startActivity(new Intent(this, ReaderActivity.class)
                        .putExtra("url", cur.startUrl()).putExtra("page", cur.lastPage)));
            } catch (Exception e) {
                Log.e(Shelf.TAG, "open", e);
            }
        });
    }

    /** Long press: share the work (LINE, X, …), open its page, or take it off the shelf. */
    void workMenu(View anchor, Shelf.Work w) {
        PopupMenu m = new PopupMenu(this, anchor, Gravity.END);
        boolean guide = Guide.is(w.ncode);                                  // bundled: no site to share or open
        if (!guide) m.getMenu().add(0, 1, 0, "共有");
        if (!guide) m.getMenu().add(0, 2, 1, "作品ページを開く");
        m.getMenu().add(0, 4, 2, "タグ…");
        if (FullSave.offered(w))
            m.getMenu().add(0, 5, 3, !FullSave.PROGRESS.containsKey(w.ncode) ? "全話を保存"
                    : FullSave.STOP.contains(w.ncode) ? "全話の保存を止めています…" : "全話の保存を止める")
                    .setEnabled(!FullSave.STOP.contains(w.ncode) || !FullSave.PROGRESS.containsKey(w.ncode));
        if (!guide) m.getMenu().add(0, 6, 4, "保存した話を消す");
        m.getMenu().add(0, 3, 5, "本棚から外す");
        m.setOnMenuItemClickListener(i -> {
            if (i.getItemId() == 1) {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Shelf.shareText(w));
                startActivity(Intent.createChooser(send, w.title));
            } else if (i.getItemId() == 2) {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Shelf.workUrl(w))));
            } else if (i.getItemId() == 4) {
                editTags(w);
            } else if (i.getItemId() == 5) {
                if (FullSave.PROGRESS.containsKey(w.ncode)) FullSave.stop(w.ncode); else askFullSave(w);
            } else if (i.getItemId() == 6) {
                askPrune(w);
            } else askRemove(w);
            return true;
        });
        m.show();
    }

    void askFullSave(Shelf.Work w) {
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(w.title)
                .setMessage("まだ保存していない話を、" + FullSave.GAP_MS / 1000 + " 秒ほどの間を空けて1話ずつ取りに行きます。"
                        + "アプリを閉じている間は止まり、開くと続きから進みます。長押しからいつでも止められます。")
                .setPositiveButton("保存する", (d, i) -> FullSave.start(this, w.ncode))
                .setNegativeButton("やめる", null)
                .show();
    }

    /** The work stays on the shelf, with its place; only the kept text goes (as 保存容量の整理 does). */
    void askPrune(Shelf.Work w) {
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(w.title)
                .setMessage("保存した話をすべて消します。本棚には残り、続きの位置も覚えています。読むときにまた取りに行きます。")
                .setPositiveButton("消す", (d, i) -> {
                    BG.execute(() -> {
                        try {
                            long freed = FullSave.prune(this, Shelf.load(this), w.ncode, true);
                            runOnUiThread(() -> Toast.makeText(this, size(freed) + " 空けました", Toast.LENGTH_SHORT).show());
                        } catch (Exception e) {
                            Log.e(Shelf.TAG, "prune", e);
                        }
                    });
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    void askRemove(Shelf.Work w) {
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(w.title)
                .setMessage("本棚から外します。この作品の保存した話も消えます。")
                .setPositiveButton("外す", (d, i) -> BG.execute(() -> {
                    try {
                        Shelf s = Shelf.remove(this, java.util.Collections.singleton(w.ncode));
                        runOnUiThread(() -> { shelf = s; render(); });
                    } catch (Exception e) {
                        Log.e(Shelf.TAG, "remove", e);
                    }
                }))
                .setNegativeButton("やめる", null)
                .show();
    }
}
