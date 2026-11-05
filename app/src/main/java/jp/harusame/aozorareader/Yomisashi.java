package jp.harusame.aozorareader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 読みさし: the works being read, most recently read first, at most {@link #MAX}. Like the recent apps of the
 * system: a work comes in when it is read (its {@code lastRead}), and goes out when swept away; sweeping only takes it
 * out of 読みさし, never off the shelf, and reading it again brings it back (2026-11-04/05, the user). Drawn by
 * assets/yomisashi.js, from the reader's bars and over the shelf ({@link YomisashiActivity}).
 *
 * <p>What is kept: the time each work was swept away, in the reader's preferences ({@code yomisashiOut}); a work
 * counts as out while that time is not older than its lastRead.
 */
final class Yomisashi {
    /** At most this many works on the desk (2026-11-05, the user: "机ならスクロールで20個"); the sheet scrolls. */
    static final int MAX = 20;
    /** Places drawn at the least: the empty ones are shown up to this many, never twenty. */
    static final int PLACES = 6;
    private static final String OUT = "yomisashiOut";
    private static final String VIEW = "yomisashiView";

    private Yomisashi() {}

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

    private static final java.util.regex.Pattern PARA = java.util.regex.Pattern.compile(
            "<p( id=\"[Lp][0-9]+\")?[^>]*>(.*?)</p>", java.util.regex.Pattern.DOTALL);

    /**
     * A few lines of the text where the work was left, to tell the cards apart at a glance (the user: the cards'
     * empty space, "本文入れる？"). Only from what is kept on the phone: nothing is fetched for it, and a work whose
     * place is not kept (a new episode not fetched yet, say) has none.
     */
    static String excerpt(Context c, Shelf.Work w) {
        // Where a tap opens it: past the last page of an episode, the head of the next one, if it is kept.
        boolean next = finishedWithMore(w);
        int ep = next ? w.lastEpisode + 1 : w.lastEpisode;
        try {
            java.io.File f = Episodes.cacheFile(c, next ? Shelf.episodeUrl(w.ncode, ep) : w.startUrl());
            if (!f.isFile() || f.length() > 4_000_000) return "";
            String html = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            boolean site = !Shelf.isAozora(w.ncode) && !Guide.is(w.ncode);
            if (!site) {
                JSONArray secs = new JSONArray(html);
                int i = Math.max(0, Math.min(secs.length() - 1, ep - 1));
                html = secs.optString(i, "");
            }
            List<String> ps = new ArrayList<>();
            java.util.regex.Matcher m = PARA.matcher(html);
            while (m.find()) {
                if (site && m.group(1) == null) continue;              // the site's own paragraphs, not the text
                String t = m.group(2).replaceAll("(?s)<r[tp]>.*?</r[tp]>", "").replaceAll("<[^>]+>", "")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                        .replace("&#39;", "'").replace("&nbsp;", " ").trim().replaceAll("^[\u3000 ]+", "");
                if (!t.isEmpty()) ps.add(t);
            }
            if (ps.isEmpty()) return "";
            double at = !next && w.lastPages > 1 ? (double) w.lastPage / (w.lastPages - 1) : 0;
            StringBuilder b = new StringBuilder();
            for (int i = Math.min(ps.size() - 1, (int) (at * ps.size())); i < ps.size() && b.length() < 90; i++) {
                if (b.length() > 0) b.append('\u3000');
                b.append(ps.get(i));
            }
            return b.length() > 90 ? b.substring(0, 90) : b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Opens a work of 読みさし where a tap on its card says (see {@link #start}); its new episodes count as seen, as
     * when opened from the shelf. On the caller's background thread; {@code then} runs on the UI thread after.
     */
    static void open(android.app.Activity a, String key, Runnable then) {
        try {
            Shelf s = Shelf.update(a, sh -> {
                Shelf.Work cur = sh.find(key);
                if (cur == null) return;
                cur.seen = cur.episodes;
                if (Shelf.isKakuyomu(cur.ncode)) KakuyomuSite.seeAll(a, cur);
            });
            Shelf.Work w = s.find(key);
            if (w == null) return;
            Object[] at = start(w);
            a.runOnUiThread(() -> {
                a.startActivity(new android.content.Intent(a, ReaderActivity.class)
                        .putExtra("url", (String) at[0]).putExtra("page", (Integer) at[1]).putExtra("pages", (Integer) at[2]));
                if (then != null) then.run();
            });
        } catch (Exception e) {
            android.util.Log.e(Shelf.TAG, "open from 読みさし", e);
        }
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
                        .put("text", excerpt(c, w))
                        .put("pct", Math.max(0, Math.min(1, pct)))
                        .put("fresh", fresh(w))
                        .put("current", w.ncode.equals(currentKey)));
            }
            o.put("view", view(c)).put("max", PLACES).put("items", items);
        } catch (Exception ignored) {
        }
        return o;
    }
}
