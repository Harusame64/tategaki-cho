package jp.harusame.aozorareader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 読みさし: the works being read, most recently read first, at most {@link #MAX}. Like the recent apps of the
 * system: a work comes in when it is read (its {@code lastRead}), and goes out when swept away; sweeping only takes it
 * out of 読みさし, never off the shelf, and reading it again brings it back (2026-11-04/05, the user). Drawn by
 * assets/yomisashi.js, from the reader's bars and over the shelf ({@link YomisashiOverlay}).
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

    /** A paragraph: {@code <p>} or {@code <p ...>}, not {@code <path>}, {@code <pre>} or {@code <param>}. */
    private static final java.util.regex.Pattern PARA = java.util.regex.Pattern.compile("<p(?:\\s[^>]*)?>(.*?)</p>", java.util.regex.Pattern.DOTALL);
    /** A site's text: なろう's js-novel-text (its 前書き and 後書き are the same class, marked --preface, --afterword), カクヨム's widget-episodeBody. */
    private static final java.util.regex.Pattern BODY = java.util.regex.Pattern.compile("class=\"((?:[^\"]* )?(?:js-novel-text|widget-episodeBody)(?: [^\"]*)?)\"");
    private static final java.util.regex.Pattern DIV = java.util.regex.Pattern.compile("<(/?)div[\\s>]");
    /** Excerpts by file and place: the sheet is drawn each time it opens, for up to twenty works (review). */
    private static final java.util.Map<String, String> EXCERPTS = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) { return size() > 64; }
            });

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
            double at = !next && w.lastPages > 1 ? (double) w.lastPage / (w.lastPages - 1) : 0;
            String key = f.getPath() + "|" + f.lastModified() + "|" + f.length() + "|" + ep + "|" + at;
            String kept = EXCERPTS.get(key);
            if (kept != null) return kept;
            String html = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            if (Shelf.isAozora(w.ncode) || Guide.is(w.ncode)) {
                JSONArray secs = new JSONArray(html);
                html = secs.optString(Math.max(0, Math.min(secs.length() - 1, ep - 1)), "");
            } else {
                html = siteText(html);
            }
            String out = excerptAt(html, at);
            EXCERPTS.put(key, out);
            return out;
        } catch (Exception e) {
            Log.w(Shelf.TAG, "読みさし: no excerpt for " + w.ncode, e);
            return "";
        }
    }

    /**
     * Only the text of a site's page: the element holding it, to its closing tag (not the 後書き, the site's footer
     * or anything after), skipping a 前書き before it; "" when the page has none.
     */
    static String siteText(String html) {
        java.util.regex.Matcher b = BODY.matcher(html);
        while (b.find()) {
            String cls = b.group(1);
            if (cls.contains("--preface") || cls.contains("--afterword")) continue;
            int open = html.lastIndexOf('<', b.start());
            if (open < 0) return "";
            int depth = 0;
            java.util.regex.Matcher d = DIV.matcher(html);
            d.region(open, html.length());
            while (d.find()) {
                depth += d.group(1).isEmpty() ? 1 : -1;
                if (depth == 0) return html.substring(open, d.start());
            }
            return html.substring(open);
        }
        return "";
    }

    /** About ninety characters from the paragraph at the share read ({@code at}, 0 to 1), without ruby readings. */
    static String excerptAt(String html, double at) {
        List<String> ps = new ArrayList<>();
        java.util.regex.Matcher m = PARA.matcher(html);
        while (m.find()) {
            String t = m.group(1).replaceAll("(?s)<r[tp]>.*?</r[tp]>", "").replaceAll("<[^>]+>", "")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&#39;", "'").replace("&nbsp;", " ").trim().replaceAll("^[\u3000 ]+", "");
            if (!t.isEmpty()) ps.add(t);
        }
        if (ps.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (int i = Math.min(ps.size() - 1, (int) (at * ps.size())); i < ps.size() && b.length() < 90; i++) {
            if (b.length() > 0) b.append('\u3000');
            b.append(ps.get(i));
        }
        return b.length() > 90 ? b.substring(0, 90) : b.toString();
    }

    /**
     * Opens a work where a tap on its 読みさし card says (see {@link #start}); its new episodes count as seen.
     * On the caller's background thread; {@code then} runs on the UI thread after.
     */
    static void open(android.app.Activity a, String key, Runnable then) {
        open(a, key, true, then);
    }

    /**
     * Opens a work, its new episodes counted as seen: where 読みさし says ({@code next}: past the last page of an
     * older episode, the next one), or where it was left (the shelf). The one way both open a work.
     */
    static void open(android.app.Activity a, String key, boolean next, Runnable then) {
        try {
            Shelf s = Shelf.update(a, sh -> {
                Shelf.Work cur = sh.find(key);
                if (cur == null) return;
                cur.seen = cur.episodes;
                if (Shelf.isKakuyomu(cur.ncode)) KakuyomuSite.seeAll(a, cur);
            });
            Shelf.Work w = s.find(key);
            if (w == null) return;
            Object[] at = next ? start(w) : new Object[]{w.startUrl(), w.lastPage, w.lastPages};
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

    /** What the page draws: {view, max, items: [{key, title, author, sub, text, pct, fresh, current}]}. */
    static JSONObject json(Context c, String currentKey) {
        JSONObject o = new JSONObject();
        JSONArray items = new JSONArray();
        for (Shelf.Work w : list(c)) {
            // One work that cannot be drawn is left out and logged, not the whole of 読みさし (review).
            try {
                double inEp = w.lastPages > 1 ? (double) w.lastPage / (w.lastPages - 1) : 0;
                double pct = w.episodes > 0 ? (Math.max(0, w.lastEpisode - 1) + inEp) / w.episodes : inEp;
                items.put(new JSONObject()
                        .put("key", w.ncode)
                        .put("title", w.title == null || w.title.isEmpty() ? w.ncode : w.title)
                        .put("author", w.author == null ? "" : w.author)
                        .put("sub", where(w))
                        .put("text", excerpt(c, w))
                        .put("pct", Math.max(0, Math.min(1, pct)))
                        .put("fresh", fresh(w))
                        .put("current", w.ncode.equals(currentKey)));
            } catch (Exception e) {
                Log.w(Shelf.TAG, "読みさし: " + w.ncode + " not drawn", e);
            }
        }
        try {
            o.put("view", view(c)).put("max", PLACES).put("items", items);
        } catch (Exception e) {
            Log.w(Shelf.TAG, "読みさし", e);
        }
        return o;
    }
}
