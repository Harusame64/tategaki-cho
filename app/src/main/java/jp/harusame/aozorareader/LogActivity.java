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
 * 読書の記録: a month's calendar with a mark on each day something was read (deeper for more pages), and,
 * under it, what was read on the day tapped — the work and its episodes.
 */
public class LogActivity extends ThemedActivity {
    final ExecutorService work = Executors.newSingleThreadExecutor();
    YearMonth month = YearMonth.now();
    LocalDate picked = LocalDate.now();
    TextView title;
    GridLayout grid;
    LinearLayout dayList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme();
        if (savedInstanceState != null) {
            month = YearMonth.parse(savedInstanceState.getString("month", month.toString()));
            picked = LocalDate.parse(savedInstanceState.getString("picked", picked.toString()));
        }
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(16));
        // ← 読書の記録: reached from the shelf's ⋮, so it goes back there rather than showing the tabs.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView back = icon(R.drawable.ic_back, "戻る");
        back.setOnClickListener(v -> finish());
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
        android.widget.ImageView prev = icon(R.drawable.ic_prev, "前の月");
        prev.setOnClickListener(v -> { month = month.minusMonths(1); show(); });
        title = text("", 17, fg);
        title.setGravity(Gravity.CENTER);
        android.widget.ImageView next = icon(R.drawable.ic_next, "次の月");
        next.setOnClickListener(v -> { month = month.plusMonths(1); show(); });
        nav.addView(prev);
        nav.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(next);
        root.addView(nav);
        grid = new GridLayout(this);
        grid.setColumnCount(7);
        root.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        dayList = new LinearLayout(this);
        dayList.setOrientation(LinearLayout.VERTICAL);
        dayList.setPadding(0, dp(16), 0, 0);
        root.addView(dayList);
        TextView note = text("記録はこの端末の中だけにあります。", 11, sub);
        note.setPadding(0, dp(20), 0, 0);
        root.addView(note);
        sv.addView(root);
        setContentAlone(sv);
        show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("month", month.toString());
        out.putString("picked", picked.toString());
    }

    @Override
    protected void onDestroy() {
        work.shutdown();
        super.onDestroy();
    }

    void show() {
        YearMonth m = month;
        title.setText(m.getYear() + "年" + m.getMonthValue() + "月");
        try {
            work.execute(() -> {
                Map<Integer, Integer> pages = ReadLog.month(getFilesDir(), m);
                List<ReadLog.Entry> day = ReadLog.day(getFilesDir(), picked);
                runOnUiThread(() -> { if (!isDestroyed() && m.equals(month)) render(m, pages, day); });
            });
        } catch (java.util.concurrent.RejectedExecutionException gone) {
            // the screen has gone
        }
    }

    void render(YearMonth m, Map<Integer, Integer> pages, List<ReadLog.Entry> day) {
        grid.removeAllViews();
        int cell = (getResources().getDisplayMetrics().widthPixels - dp(32)) / 7;
        String[] names = {"日", "月", "火", "水", "木", "金", "土"};
        for (String n : names) grid.addView(cell(n, sub, 0, cell, false));
        int lead = m.atDay(1).getDayOfWeek().getValue() % 7;          // Sunday first
        for (int i = 0; i < lead; i++) grid.addView(cell("", fg, 0, cell, false));
        int most = 1;
        for (int p : pages.values()) most = Math.max(most, p);
        for (int d = 1; d <= m.lengthOfMonth(); d++) {
            LocalDate date = m.atDay(d);
            int p = pages.getOrDefault(d, 0);
            int depth = p == 0 ? 0 : 1 + 3 * p / most;
            // The number stays readable on a deep mark; the day picked is bold.
            TextView c = cell(String.valueOf(d), depth >= 3 ? onAccent : date.equals(picked) ? accent : fg, depth, cell, date.equals(LocalDate.now()));
            if (date.equals(picked)) c.setTypeface(Typeface.DEFAULT_BOLD);
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

    /** A day: its number on a mark as deep as its pages (0 none … 4 most), and a ring for today. */
    TextView cell(String label, int color, int depth, int size, boolean today) {
        TextView c = text(label, 14, color);
        c.setGravity(Gravity.CENTER);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = size;
        lp.height = size;
        c.setLayoutParams(lp);
        if (depth > 0 || today) {
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (depth > 0) d.setColor((Math.min(255, 50 + 50 * depth) << 24) | (accent & 0xFFFFFF));
            if (today) d.setStroke(dp(2), accent);
            android.graphics.drawable.InsetDrawable in = new android.graphics.drawable.InsetDrawable(d, dp(4));
            c.setBackground(in);
        }
        return c;
    }
}
