package jp.harusame.aozorareader;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 読書の記録: a month's calendar with a mark on each day something was read (deeper for more pages), the month
 * summed up, and what was read on the day tapped — the work and its episodes. From the month's summary, the
 * year: its twelve months and every work read in it.
 */
public class LogActivity extends ThemedActivity {
    final ExecutorService work = Executors.newSingleThreadExecutor();
    YearMonth month = YearMonth.now();
    LocalDate picked = LocalDate.now();
    TextView title;
    GridLayout grid;
    LinearLayout dayList, summary, legend;
    /** the year shown instead of the month (the month's year) */
    boolean yearView;
    /** Back from the year goes to the month, not off the screen. */
    final android.window.OnBackInvokedCallback toMonth = () -> showMonth(month);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme();
        if (savedInstanceState != null) {
            month = YearMonth.parse(savedInstanceState.getString("month", month.toString()));
            picked = LocalDate.parse(savedInstanceState.getString("picked", picked.toString()));
            yearView = savedInstanceState.getBoolean("year");
        }
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(16));
        // ← 読書の記録: reached from the shelf's ⋮, so it goes back there rather than showing the tabs.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView back = icon(R.drawable.ic_back, "戻る");
        back.setOnClickListener(v -> { if (yearView) showMonth(month); else finish(); });
        head.addView(back);
        TextView h = text("読書の記録", 22, fg);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(dp(8), 0, 0, 0);
        head.addView(h);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = -dp(12);                                    // the arrow, not its touch target, lines up with the text
        root.addView(head, hlp);
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, dp(12), 0, dp(8));
        android.widget.ImageView prev = icon(R.drawable.ic_prev, "前へ");
        prev.setOnClickListener(v -> { month = yearView ? month.minusYears(1) : month.minusMonths(1); show(); });
        title = text("", 17, fg);
        title.setGravity(Gravity.CENTER);
        android.widget.ImageView next = icon(R.drawable.ic_next, "次へ");
        next.setOnClickListener(v -> { month = yearView ? month.plusYears(1) : month.plusMonths(1); show(); });
        nav.addView(prev);
        nav.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(next);
        root.addView(nav);
        grid = new GridLayout(this);
        grid.setColumnCount(7);
        root.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        legend = legend();
        root.addView(legend, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setPadding(dp(14), dp(12), dp(14), dp(12));
        summary.setBackground(round(card, dp(12)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        root.addView(summary, slp);
        dayList = new LinearLayout(this);
        dayList.setOrientation(LinearLayout.VERTICAL);
        dayList.setPadding(0, dp(16), 0, 0);
        root.addView(dayList);
        TextView note = text("記録はこの端末の中だけにあります。", 11, sub);
        note.setPadding(0, dp(20), 0, 0);
        root.addView(note);
        sv.addView(root);
        setContentAlone(sv);
        if (yearView) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, toMonth);
        show();
    }

    void showYear() {
        if (!yearView) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, toMonth);
        yearView = true;
        show();
    }

    void showMonth(YearMonth m) {
        if (yearView) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(toMonth);
        yearView = false;
        month = m;
        show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("month", month.toString());
        out.putString("picked", picked.toString());
        out.putBoolean("year", yearView);
    }

    @Override
    protected void onDestroy() {
        work.shutdown();
        super.onDestroy();
    }

    void show() {
        if (yearView) { year(month.getYear()); return; }
        legend.setVisibility(android.view.View.VISIBLE);
        YearMonth m = month;
        title.setText(m.getYear() + "年" + m.getMonthValue() + "月");
        try {
            work.execute(() -> {
                Map<Integer, Integer> pages = ReadLog.month(getFilesDir(), m);
                ReadLog.Summary sum = ReadLog.summary(getFilesDir(), m.atDay(1), m.atEndOfMonth());
                List<ReadLog.Entry> day = ReadLog.day(getFilesDir(), picked);
                runOnUiThread(() -> { if (!isDestroyed() && !yearView && m.equals(month)) { render(m, pages, day); renderSummary(m, sum); } });
            });
        } catch (java.util.concurrent.RejectedExecutionException gone) {
            // the screen has gone
        }
    }

    void render(YearMonth m, Map<Integer, Integer> pages, List<ReadLog.Entry> day) {
        grid.removeAllViews();
        grid.setColumnCount(7);                                      // the year view leaves it at 4
        int cell = (getResources().getDisplayMetrics().widthPixels - dp(32)) / 7;
        String[] names = {"日", "月", "火", "水", "木", "金", "土"};
        for (String n : names) grid.addView(cell(n, sub, 0, cell, false));
        int lead = m.atDay(1).getDayOfWeek().getValue() % 7;          // Sunday first
        for (int i = 0; i < lead; i++) grid.addView(cell("", fg, 0, cell, false));
        for (int d = 1; d <= m.lengthOfMonth(); d++) {
            LocalDate date = m.atDay(d);
            Integer p = pages.get(d);
            int depth = p == null ? 0 : ReadLog.depth(p);
            // The number stays readable on a deep mark; the day picked has a ring, today is bold.
            TextView c = cell(String.valueOf(d), depth >= 3 ? onAccent : fg, depth, cell, date.equals(picked));
            if (date.equals(LocalDate.now())) c.setTypeface(Typeface.DEFAULT_BOLD);
            c.setOnClickListener(v -> { picked = date; show(); });
            grid.addView(c);
        }
        dayList.removeAllViews();
        TextView h = text(picked.getMonthValue() + "月" + picked.getDayOfMonth() + "日（" + "日月火水木金土".charAt(picked.getDayOfWeek().getValue() % 7) + "）", 15, fg);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        dayList.addView(h);
        if (day.isEmpty()) {
            TextView t = text("この日の記録はありません", 13, sub);
            t.setPadding(0, dp(6), 0, 0);
            dayList.addView(t);
        }
        for (ReadLog.Entry e : day) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(0, dp(10), 0, dp(10));
            TextView t = text(e.title, 15, fg);
            t.setMaxLines(2);
            r.addView(t);
            // 青空文庫 sections and short stories (episode 0) have no 第n話.
            String eps = Shelf.isAozora(e.key) || e.last <= 0 ? "" : e.first == e.last ? "第" + e.first + "話" : "第" + e.first + "〜" + e.last + "話";
            // An episode opened but not paged through counts no pages: then only the episodes are shown.
            String line = e.pages > 0 ? eps + (eps.isEmpty() ? "" : "　") + e.pages + " ページ" : eps;
            if (!line.isEmpty()) r.addView(text(line, 12, sub));
            dayList.addView(r);
        }
    }

    int markColor(int depth) {
        return (Math.min(255, 50 + 50 * depth) << 24) | (accent & 0xFFFFFF);
    }

    /** The year: a mark for each month as deep as the days read in it, the year summed up, and its works. */
    void year(int y) {
        legend.setVisibility(android.view.View.GONE);                // its marks are of pages; a month's are of days
        title.setText(y + "年");
        try {
            work.execute(() -> {
                ReadLog.Summary[] months = new ReadLog.Summary[12];
                for (int i = 0; i < 12; i++) months[i] = ReadLog.summary(getFilesDir(), YearMonth.of(y, i + 1).atDay(1), YearMonth.of(y, i + 1).atEndOfMonth());
                ReadLog.Summary all = ReadLog.summary(getFilesDir(), LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31));
                runOnUiThread(() -> { if (!isDestroyed() && yearView && month.getYear() == y) renderYear(y, months, all); });
            });
        } catch (java.util.concurrent.RejectedExecutionException gone) {
            // the screen has gone
        }
    }

    void renderYear(int y, ReadLog.Summary[] months, ReadLog.Summary all) {
        grid.removeAllViews();
        grid.setColumnCount(4);
        int w = (getResources().getDisplayMetrics().widthPixels - dp(32)) / 4;
        YearMonth now = YearMonth.now();
        for (int i = 0; i < 12; i++) {
            YearMonth m = YearMonth.of(y, i + 1);
            int days = months[i].days;
            int depth = days == 0 ? 0 : days < 5 ? 1 : days < 15 ? 2 : days < 25 ? 3 : 4;
            TextView c = text((i + 1) + "月\n" + (days == 0 ? "—" : days + "日"), 13, depth >= 3 ? onAccent : days == 0 ? sub : fg);
            c.setGravity(Gravity.CENTER);
            if (m.equals(now)) c.setTypeface(Typeface.DEFAULT_BOLD);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w;
            lp.height = dp(64);
            c.setLayoutParams(lp);
            android.graphics.drawable.GradientDrawable d = round(depth > 0 ? markColor(depth) : 0, dp(10));
            c.setBackground(new android.graphics.drawable.InsetDrawable(d, dp(3)));
            c.setOnClickListener(v -> showMonth(m));
            grid.addView(c);
        }
        renderSummary(null, all);
        dayList.removeAllViews();
        if (all.works.isEmpty()) return;
        TextView h = text("この年に読んだ作品", 15, fg);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        dayList.addView(h);
        for (ReadLog.WorkSum ws : all.works) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(0, dp(10), 0, dp(10));
            TextView t = text(ws.title, 15, fg);
            t.setMaxLines(2);
            r.addView(t);
            r.addView(text(ws.days + "日" + (ws.episodes > 0 ? "・" + ws.episodes + "話" : ""), 12, sub));
            dayList.addView(r);
        }
    }

    /**
     * 10月のまとめ (m) or 2026年のまとめ (m null, the year shown): the days read, the works and episodes, and the
     * work read on the most days. The month's leads on to its year.
     */
    void renderSummary(YearMonth m, ReadLog.Summary s) {
        summary.removeAllViews();
        TextView h = text(m == null ? month.getYear() + "年のまとめ" : m.getMonthValue() + "月のまとめ", 14, fg);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        summary.addView(h);
        if (s.days == 0) {
            summary.addView(text(m == null ? "この年の記録はありません" : "この月の記録はありません", 13, sub));
            if (m != null) summary.addView(toYear(m));
            return;
        }
        TextView n = text(s.days + "日・" + s.works.size() + "作品" + (s.episodes > 0 ? "・" + s.episodes + "話" : ""), 15, fg);
        n.setPadding(0, dp(4), 0, 0);
        summary.addView(n);
        ReadLog.WorkSum t = s.works.get(0);
        TextView top = text("いちばん読んだ作品：" + t.title + "（" + t.days + "日）", 13, sub);
        top.setMaxLines(2);
        top.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.setPadding(0, dp(2), 0, 0);
        summary.addView(top);
        if (m != null) summary.addView(toYear(m));
    }

    TextView toYear(YearMonth m) {
        TextView y = text(m.getYear() + "年のまとめを見る", 14, accent);
        y.setMinHeight(dp(TOUCH_DP));
        y.setGravity(Gravity.CENTER_VERTICAL);
        y.setOnClickListener(v -> showYear());
        return y;
    }

    /** 少ない ●●●● 多い: what the marks mean. */
    LinearLayout legend() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        r.setPadding(0, dp(4), 0, 0);
        r.addView(text("読んだ日　少ない", 11, sub));
        for (int d = 1; d <= 4; d++) {
            android.view.View dot = new android.view.View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(markColor(d));
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(12), dp(12));
            lp.leftMargin = dp(4);
            r.addView(dot, lp);
        }
        TextView more = text("多い", 11, sub);
        more.setPadding(dp(4), 0, 0, 0);
        r.addView(more);
        return r;
    }

    /** A day: its number on a mark as deep as its pages (0 none … 4 most), and a ring for the day picked. */
    TextView cell(String label, int color, int depth, int size, boolean ring) {
        TextView c = text(label, 14, color);
        c.setGravity(Gravity.CENTER);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = size;
        lp.height = size;
        c.setLayoutParams(lp);
        if (depth > 0 || ring) {
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (depth > 0) d.setColor(markColor(depth));
            if (ring) d.setStroke(dp(2), fg);
            android.graphics.drawable.InsetDrawable in = new android.graphics.drawable.InsetDrawable(d, dp(4));
            c.setBackground(in);
        }
        return c;
    }
}
