package jp.harusame.aozorareader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * よみかけ: the works being read, most recently read first, at most {@link #MAX}. Like the recent apps of the
 * system: a work comes in when it is read (its {@code lastRead}), and goes out when swept away; sweeping only takes it
 * out of this row, never off the shelf, and reading it again brings it back (2026-11-04, the user).
 *
 * <p>SPIKE: tried on the phone before it is settled. What is kept: the time each work was swept away, in the
 * reader's preferences ({@code yomikakeOut}); a work counts as out while that time is not older than its lastRead.
 */
final class Yomikake {
    static final int MAX = 6;
    private static final String OUT = "yomikakeOut";
    private static final String VIEW = "yomikakeView";

    private Yomikake() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("reader", Context.MODE_PRIVATE);
    }

    private static JSONObject out(Context c) {
        try {
            return new JSONObject(prefs(c).getString(OUT, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** The row, newest first. */
    static List<Shelf.Work> list(Context c) {
        JSONObject out = out(c);
        List<Shelf.Work> all = new ArrayList<>();
        for (Shelf.Work w : Shelf.load(c).works) {
            if (w.lastRead <= 0) continue;
            if (out.optLong(w.ncode, 0) >= w.lastRead) continue;
            all.add(w);
        }
        all.sort((a, b) -> Long.compare(b.lastRead, a.lastRead));
        return all.size() > MAX ? new ArrayList<>(all.subList(0, MAX)) : all;
    }

    static synchronized void drop(Context c, String key) {
        JSONObject out = out(c);
        try {
            out.put(key, System.currentTimeMillis());
        } catch (Exception ignored) {
            return;
        }
        prefs(c).edit().putString(OUT, out.toString()).apply();
    }

    /** "c" (the two-column sheet) or "a" (the large cards); the last one used. */
    static String view(Context c) {
        return "a".equals(prefs(c).getString(VIEW, "c")) ? "a" : "c";
    }

    static void setView(Context c, String v) {
        prefs(c).edit().putString(VIEW, "a".equals(v) ? "a" : "c").apply();
    }

    /**
     * Read to the last page of an episode that is no longer the newest: the next one has come out since, so the
     * work opens at its start (the user: "最終ページの場合は新着更新して欲しい"). なろう and 青空文庫 only for now;
     * カクヨム's places are ids and are left to {@link Shelf.Work#startUrl()}.
     */
    static boolean finishedWithMore(Shelf.Work w) {
        return !w.shortStory && !Shelf.isKakuyomu(w.ncode) && w.lastEpisode > 0 && w.episodes > w.lastEpisode
                && w.lastPages > 0 && w.lastPage >= w.lastPages - 1;
    }

    /**
     * Has something new to read: new episodes, or a newer one past the last page read. Not 青空文庫 nor the guide:
     * their sections are a fixed book, and the next one is not news.
     */
    static boolean fresh(Shelf.Work w) {
        if (Shelf.isAozora(w.ncode) || Guide.is(w.ncode)) return false;
        return finishedWithMore(w) || w.newEpisodes() > 0;
    }

    /** Where the work was left, as a card says it. */
    static String where(Shelf.Work w) {
        if (w.shortStory) return "短編";
        if (Shelf.isAozora(w.ncode) || Guide.is(w.ncode)) return Math.max(1, w.lastEpisode) + " / " + Math.max(1, w.episodes);
        if (finishedWithMore(w)) return w.lastEpisode + "話を読み終え";
        return Math.max(1, w.lastEpisode) + " / " + Math.max(1, w.episodes) + " 話";
    }

    /** {url, page, pages} to open the work at. */
    static Object[] start(Shelf.Work w) {
        if (finishedWithMore(w)) return new Object[]{Shelf.episodeUrl(w.ncode, w.lastEpisode + 1), 0, 0};
        return new Object[]{w.startUrl(), w.lastPage, w.lastPages};
    }

    /** What the page draws: {view, max, items: [{key, title, sub, pct, fresh, current}]}. */
    static JSONObject json(Context c, String currentKey) {
        JSONObject o = new JSONObject();
        try {
            JSONArray items = new JSONArray();
            for (Shelf.Work w : list(c)) {
                String sub = where(w);
                double inEp = w.lastPages > 1 ? (double) w.lastPage / (w.lastPages - 1) : 0;
                double pct = w.episodes > 0 ? (Math.max(0, w.lastEpisode - 1) + inEp) / w.episodes : inEp;
                items.put(new JSONObject()
                        .put("key", w.ncode)
                        .put("title", w.title == null || w.title.isEmpty() ? w.ncode : w.title)
                        .put("author", w.author == null ? "" : w.author)
                        .put("sub", sub)
                        .put("pct", Math.max(0, Math.min(1, pct)))
                        .put("fresh", fresh(w))
                        .put("current", w.ncode.equals(currentKey)));
            }
            o.put("view", view(c)).put("max", MAX).put("items", items);
        } catch (Exception ignored) {
        }
        return o;
    }
}
