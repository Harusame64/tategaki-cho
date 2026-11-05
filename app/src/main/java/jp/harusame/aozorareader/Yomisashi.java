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
 * <p>What is kept: for each work swept away, its lastRead then, in the reader's preferences ({@code yomisashiOut});
 * it is out while its lastRead is still that one. Not a time compared with lastRead: a clock set back kept a work
 * read again out (review).
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
        Shelf shelf = Shelf.load(c);
        for (Shelf.Work w : shelf.works) {
            if (w.lastRead <= 0) continue;
            if (out.has(w.ncode) && out.optLong(w.ncode, -1) == w.lastRead) continue;
            all.add(w);
        }
        if (!shelf.unread) prune(c, shelf, out);
        all.sort((a, b) -> Long.compare(b.lastRead, a.lastRead));
        return all.size() > MAX ? new ArrayList<>(all.subList(0, MAX)) : all;
    }

    /**
     * Forgets the times kept for works read again since (they are back) or gone from the shelf: else one is kept for
     * every work ever taken out, and read again with each list (review).
     */
    private static synchronized void prune(Context c, Shelf shelf, JSONObject out) {
        List<String> stale = new ArrayList<>();
        for (java.util.Iterator<String> i = out.keys(); i.hasNext(); ) {
            String k = i.next();
            Shelf.Work w = shelf.find(k);
            if (w == null || w.lastRead != out.optLong(k, -1)) stale.add(k);
        }
        if (stale.isEmpty()) return;
        JSONObject now = out(c);                                            // as written now, not as read before
        for (String k : stale) {
            Shelf.Work w = shelf.find(k);
            if (w == null || w.lastRead != now.optLong(k, -1)) now.remove(k);
        }
        prefs(c).edit().putString(OUT, now.toString()).apply();
    }

    static synchronized void drop(Context c, String key) {
        Shelf.Work w = Shelf.load(c).find(key);
        if (w == null) return;
        JSONObject out = out(c);
        try {
            out.put(key, w.lastRead);
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
        if (Shelf.isAozora(w.ncode)) return false;
        return finishedWithMore(w) || w.newEpisodes() > 0;
    }

    /** Where the work was left, as a card says it. */
    static String where(Shelf.Work w) {
        if (w.shortStory) return "短編";
        if (Shelf.isAozora(w.ncode)) return Math.max(1, w.lastEpisode) + " / " + Math.max(1, w.episodes);
        if (finishedWithMore(w)) return w.lastEpisode + " 話まで読み終えた";
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
            boolean book = Shelf.isAozora(w.ncode);
            if (!f.isFile() || f.length() > (book ? 8_000_000 : 4_000_000)) return "";
            double at = !next && w.lastPages > 1 ? (double) w.lastPage / (w.lastPages - 1) : 0;
            String key = f.getPath() + "|" + f.lastModified() + "|" + f.length() + "|" + ep + "|" + at;
            String kept = EXCERPTS.get(key);
            if (kept != null) return kept;
            String html;
            if (book) {
                JSONArray secs = sections(f);
                html = secs.optString(Math.max(0, Math.min(secs.length() - 1, ep - 1)), "");
            } else {
                html = siteText(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            }
            String out = excerptAt(html, at);
            EXCERPTS.put(key, out);
            return out;
        } catch (Exception | OutOfMemoryError e) {
            // A book too big for the phone's memory has no excerpt, and the app goes on (an Error passed this by).
            if (e instanceof OutOfMemoryError) SECTIONS.clear();
            Log.w(Shelf.TAG, "読みさし: no excerpt for " + w.ncode, e);
            return "";
        }
    }

    /**
     * The last 青空文庫 book's sections, read once while the file is unchanged: a whole book for one section, each
     * time the place moved (review). One book only, as it is kept in memory until another comes.
     */
    private static final java.util.Map<String, JSONArray> SECTIONS = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, JSONArray>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, JSONArray> e) { return size() > 1; }
            });

    /** The sheet is closed: the book kept for it is let go. */
    static void forget() { SECTIONS.clear(); }

    private static JSONArray sections(java.io.File f) throws Exception {
        String key = f.getPath() + "|" + f.lastModified() + "|" + f.length();
        JSONArray a = SECTIONS.get(key);
        if (a == null) {
            a = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            SECTIONS.put(key, a);
        }
        return a;
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

    private static final java.util.regex.Pattern ENTITY = java.util.regex.Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});");

    /** HTML's character references, each once (&amp;lt; is the text "&lt;"): numbers, and the names a page uses. */
    static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        java.util.regex.Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String e = m.group(1), r = null;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
            } catch (IllegalArgumentException ignored) {
            }
            if (r == null) switch (e) {
                case "amp": r = "&"; break;
                case "lt": r = "<"; break;
                case "gt": r = ">"; break;
                case "quot": r = "\""; break;
                case "apos": r = "'"; break;
                case "nbsp": r = " "; break;
                case "hellip": r = "\u2026"; break;
                case "mdash": r = "\u2014"; break;
                case "ndash": r = "\u2013"; break;
                default: r = m.group();                                    // not one it knows: as written
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** About ninety characters from the paragraph at the share read ({@code at}, 0 to 1), without ruby readings. */
    static String excerptAt(String html, double at) {
        List<String> ps = new ArrayList<>();
        java.util.regex.Matcher m = PARA.matcher(html);
        while (m.find()) {
            String t = unescape(m.group(1).replaceAll("(?s)<r[tp]>.*?</r[tp]>", "").replaceAll("<[^>]+>", ""))
                    .trim().replaceAll("^[\u3000 ]+", "");
            if (!t.isEmpty()) ps.add(t);
        }
        if (ps.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (int i = Math.min(ps.size() - 1, (int) (at * ps.size())); i < ps.size() && b.length() < 90; i++) {
            if (b.length() > 0) b.append('\u3000');
            b.append(ps.get(i));
        }
        if (b.length() <= 90) return b.toString();
        int cut = 90;
        if (Character.isHighSurrogate(b.charAt(cut - 1))) cut--;              // not half of 𠮟 or an emoji (review)
        return b.substring(0, cut);
    }

    /**
     * Opens a work where a tap on its 読みさし card says (see {@link #start}); its new episodes count as seen.
     * On the caller's background thread; {@code then} runs on the UI thread after.
     */
    static boolean open(android.app.Activity a, String key, java.util.function.BooleanSupplier wanted, Runnable then) {
        return open(a, key, true, wanted, then);
    }

    /**
     * Opens a work, its new episodes counted as seen: where 読みさし says ({@code next}: past the last page of an
     * older episode, the next one), or where it was left (the shelf). The one way both open a work.
     * @param wanted still wanted when the reader is to start: not once the sheet was closed, or the screen left,
     *        after the tap (Back pressed while it was on its way); null for always
     * @return false when nothing was opened (the work is gone from the shelf, or the shelf could not be read)
     */
    static boolean open(android.app.Activity a, String key, boolean next, java.util.function.BooleanSupplier wanted, Runnable then) {
        try {
            if (wanted != null && !wanted.getAsBoolean()) return true;       // closed already: nothing, and not failed
            Shelf.Work w = Shelf.load(a).find(key);
            if (w == null) return false;
            Object[] at = next ? start(w) : new Object[]{w.startUrl(), w.lastPage, w.lastPages};
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed() || (wanted != null && !wanted.getAsBoolean())) return;
                // Its new episodes count as seen here, once the open is sure and before the reader starts: not before
                // the check above (an open called off left its 新着 cleared), and not after the start (a quick Back to
                // the shelf drew it still new) — the gate asked for each in turn. A small write on this thread.
                // Only the episodes there were when it was read above: ones a check found since stay new.
                int known = w.episodes;
                try {
                    Shelf.update(a, sh -> {
                        Shelf.Work cur = sh.find(key);
                        if (cur == null) return;
                        cur.seen = Math.max(cur.seen, Math.min(cur.episodes, known));
                        if (Shelf.isKakuyomu(cur.ncode) && cur.episodes == known) KakuyomuSite.seeAll(a, cur);
                    });
                } catch (Exception e) {
                    android.util.Log.w(Shelf.TAG, "seen", e);
                }
                a.startActivity(new android.content.Intent(a, ReaderActivity.class)
                        .putExtra("url", (String) at[0]).putExtra("page", (Integer) at[1]).putExtra("pages", (Integer) at[2]));
                if (then != null) then.run();
            });
            return true;
        } catch (Exception e) {
            android.util.Log.e(Shelf.TAG, "open from 読みさし", e);
            return false;
        }
    }


    /** {url, page, pages} to open the work at. */
    static Object[] start(Shelf.Work w) {
        if (finishedWithMore(w)) return new Object[]{Shelf.episodeUrl(w.ncode, w.lastEpisode + 1), 0, 0};
        return new Object[]{w.startUrl(), w.lastPage, w.lastPages};
    }

    /** The check ⟳ starts: on a thread of its own, so a slow site holds up neither turning nor read-ahead. */
    private static final java.util.concurrent.ExecutorService CHECKER = java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * ⟳: the shelf's own check (新着を確認) of the whole shelf, not only of the works here — a check stamps the
     * shelf as checked, which would be untrue for the works left out. {@code done} gets what to say, on the
     * checker's thread.
     */
    static void check(Context ctx, java.util.function.Consumer<String> done) {
        Context c = ctx.getApplicationContext();                           // not the screen: a check may take minutes
        CHECKER.execute(() -> {
            String msg;
            try {
                Shelf s = Shelf.load(c);
                List<Shelf.Work> gone = s.refresh(new ArrayList<>(s.works), c);
                msg = gone.isEmpty() ? "新着を確認しました（" + android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()) + "）"
                        : Shelf.goneMessage(gone.size());
            } catch (Exception e) {
                Log.w(Shelf.TAG, "読みさし: check", e);
                msg = Shelf.checkFailedMessage(e);
            }
            done.accept(msg);
        });
    }

    /** What the page draws: {view, max, items: [{key, title, sub, text, pct, fresh, current}]}. */
    static JSONObject json(Context c, String currentKey) {
        return json(c, currentKey, true);
    }

    /** As {@link #json(Context, String)}; without the cards' text when {@code withText} is false (drawn first). */
    static JSONObject json(Context c, String currentKey, boolean withText) {
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
                        .put("sub", where(w))
                        .put("text", withText ? excerpt(c, w) : "")
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
