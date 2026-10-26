package jp.harusame.aozorareader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import jp.harusame.aozorareader.core.KakuyomuWork;

/**
 * カクヨム, which has no public API: a work is added only by sharing (or pasting) its page, and its one work
 * page gives its details and its episodes in order. Inside the app an episode is kakuyomu://&lt;work&gt;/e/&lt;id&gt;:
 * authors insert and delete episodes, so an episode is named by its id, and its number (第n話, n / N, the 目次)
 * is worked out from the work's kept list (episodes/&lt;key&gt;/kk.json) only to show it.
 */
final class KakuyomuSite {
    private KakuyomuSite() { }

    static String workPage(String workId) {
        return "https://kakuyomu.jp/works/" + workId;
    }

    /** One request for the work page. */
    static KakuyomuWork fetchWork(String workId) throws Exception {
        return fetchWork(workId, null);
    }

    /** @param wanted see {@link Net#get(String, java.util.function.BooleanSupplier)} */
    static KakuyomuWork fetchWork(String workId, java.util.function.BooleanSupplier wanted) throws Exception {
        String html = new String(Net.get(workPage(workId), wanted), StandardCharsets.UTF_8);
        KakuyomuWork w = KakuyomuWork.fromPage(html);
        if (w == null) throw new IOException("作品の情報がありません");
        return w;
    }

    static File listFile(Context c, String key) {
        return new File(c.getFilesDir(), "episodes/" + key + "/kk.json");
    }

    /** Keeps the work's list of episodes, for numbering and for the 目次. */
    static JSONObject save(Context c, String key, KakuyomuWork w) throws Exception {
        // Under the shelf's lock: the list and the places remapped from it change together.
        synchronized (Shelf.class) { return saveLocked(c, key, w); }
    }

    private static JSONObject saveLocked(Context c, String key, KakuyomuWork w) throws Exception {
        JSONObject before = load(c, key);
        JSONArray eps = new JSONArray();
        for (KakuyomuWork.Episode e : w.episodes) {
            JSONObject o = new JSONObject().put("id", e.id).put("t", e.title);
            if (e.chapter != null) o.put("c", e.chapter);
            eps.put(o);
        }
        JSONObject o = new JSONObject().put("at", System.currentTimeMillis()).put("title", w.title)
                .put("author", w.author == null ? "" : w.author).put("authorUrl", w.authorUrl == null ? "" : w.authorUrl)
                .put("finished", w.finished).put("eps", eps);
        File f = listFile(c, key);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        // Episodes are kept by their id (k<id>.html); files kept by position before that are dropped.
        File[] old = f.getParentFile().listFiles((d, n) -> n.matches("\\d+\\.html"));
        if (old != null) for (File x : old) x.delete();
        if (before != null) {
            // The shelf keeps カクヨム places as positions in this list: follow each episode to where it is now.
            JSONObject after = o;
            Shelf.update(c, s -> {
                Shelf.Work sw = s.find(key);
                if (sw != null) { remap(sw, before, after); Shelf.fromKakuyomu(sw, after); }
            });
        }
        return o;
    }

    /**
     * The place's shown number follows its episode (a place kept as a number by an earlier build gets its id
     * here), and seen becomes the number of episodes of the new list that were in the old one up to seen, so
     * only episodes really new count as new.
     */
    static void remap(Shelf.Work w, JSONObject before, JSONObject after) {
        JSONArray b = before.optJSONArray("eps");
        if (b == null) return;
        if (w.lastEpisodeId == null && w.lastEpisode > 0 && w.lastEpisode <= b.length())
            w.lastEpisodeId = b.optJSONObject(w.lastEpisode - 1).optString("id", null);
        if (w.lastEpisodeId != null) {
            int n = numberOf(after, w.lastEpisodeId);
            if (n > 0) w.lastEpisode = n;
            else if (w.lastEpisode > 0) {
                // The place's episode is gone: lastEpisode becomes the slot where it was in the new list, just
                // after the nearest earlier episode still listed (its neighbours are worked out from that slot).
                int at = numberOf(before, w.lastEpisodeId);
                if (at <= 0) at = Math.min(w.lastEpisode, b.length() + 1);
                int slot = 1;
                for (int i = at - 1; i >= 1; i--) {
                    int m = numberOf(after, b.optJSONObject(i - 1).optString("id"));
                    if (m > 0) { slot = m + 1; break; }
                }
                w.lastEpisode = slot;
            }
        }
        // Not yet seen: those that were unseen and are still listed, and every episode the old list did not have.
        java.util.Set<String> had = new java.util.HashSet<>(), unseen = new java.util.HashSet<>();
        for (int i = 0; i < b.length(); i++) had.add(b.optJSONObject(i).optString("id"));
        if (w.unseenIds != null) {
            if (!w.unseenIds.isEmpty()) unseen.addAll(java.util.Arrays.asList(w.unseenIds.split(",")));
        } else if (w.legacySeenIds != null) {
            // Kept as seen ids by an earlier build: unseen are the old list's others.
            java.util.Set<String> seen = new java.util.HashSet<>(java.util.Arrays.asList(w.legacySeenIds.split(",")));
            for (int i = 0; i < b.length(); i++) { String id = b.optJSONObject(i).optString("id"); if (!seen.contains(id)) unseen.add(id); }
        } else {
            for (int i = Math.max(0, w.seen); i < b.length(); i++) unseen.add(b.optJSONObject(i).optString("id"));
        }
        JSONArray a = after.optJSONArray("eps");
        StringBuilder still = new StringBuilder();
        int count = 0, notSeen = 0;
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String id = a.optJSONObject(i).optString("id");
            count++;
            if (unseen.contains(id) || !had.contains(id)) { notSeen++; still.append(still.length() == 0 ? "" : ",").append(id); }
        }
        w.seen = count - notSeen;
        w.unseenIds = still.toString();
        w.legacySeenIds = null;
    }

    /**
     * Moves the place to episode id, given the list as it is now (read under the shelf's lock): a listed episode
     * takes its number; the place's own episode no longer listed keeps the slot remap keeps; another episode no
     * longer listed does not take the place (it has no number to show or neighbours to go to).
     * @return whether the place is this episode now
     */
    static boolean place(Shelf.Work w, String id, JSONObject listNow) {
        int n = listNow == null ? 0 : numberOf(listNow, id);
        if (n > 0) {
            w.lastEpisodeId = id;
            w.lastEpisode = n;
            return true;
        }
        return id.equals(w.lastEpisodeId);
    }

    /** Every episode of the kept list counts as seen (the work was opened, or just added). */
    static void seeAll(Context c, Shelf.Work w) {
        w.unseenIds = "";
    }

    static JSONObject load(Context c, String key) {
        try {
            File f = listFile(c, key);
            if (f.isFile()) return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The list, kept or (when it does not reach episode no, or is older than maxAge) read again. */
    static JSONObject list(Context c, String key, int no, long maxAge) throws Exception {
        JSONObject o = load(c, key);
        if (o != null && o.getJSONArray("eps").length() >= no && System.currentTimeMillis() - o.optLong("at") < maxAge) return o;
        return save(c, key, fetchWork(key.substring(Shelf.KK.length())));
    }

    /**
     * The list holding the episode id: kept, or read again once when it does not (an episode newer than the
     * list); a list that still does not hold it (the episode was deleted) is returned as it is.
     */
    static JSONObject listWith(Context c, String key, String id) throws Exception {
        JSONObject o = load(c, key);
        if (o != null && numberOf(o, id) > 0) return o;
        // Read again once per episode while the app runs (a deleted one would otherwise be asked for on every
        // open), and only for a work on the shelf.
        if (o != null && (ASKED.contains(key + "/" + id) || Shelf.load(c).find(key) == null)) return o;
        try {
            JSONObject got = save(c, key, fetchWork(key.substring(Shelf.KK.length())));
            ASKED.add(key + "/" + id);                                      // asked and answered: not again this run
            return got;
        } catch (Exception e) {
            if (o != null) return o;
            throw e;
        }
    }

    /** episodes (work/id) whose list was read again because the kept one did not have them */
    static final java.util.Set<String> ASKED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The id of episode no (1-based) of a list, or null. */
    static String idAt(JSONObject list, int no) {
        JSONArray eps = list.optJSONArray("eps");
        return eps == null || no < 1 || no > eps.length() ? null : eps.optJSONObject(no - 1).optString("id", null);
    }

    /** The real URL of kakuyomu://&lt;work&gt;/e/&lt;id&gt; (or of the older kakuyomu://&lt;work&gt;/&lt;n&gt;, from the list). */
    static String realUrl(Context c, String internal) throws Exception {
        String[] kk = Shelf.kkEpisodeOf(internal);
        if (kk != null) return workPage(kk[0].substring(Shelf.KK.length())) + "/episodes/" + kk[1];
        Object[] ep = Shelf.episodeOf(internal);
        if (ep == null) throw new IllegalArgumentException("not a カクヨム episode: " + internal);
        String key = (String) ep[0];
        int no = (Integer) ep[1];
        JSONObject o = list(c, key, no, Long.MAX_VALUE);
        JSONArray eps = o.getJSONArray("eps");
        if (no < 1 || no > eps.length()) throw new IOException("第" + no + "話はありません");
        return workPage(key.substring(Shelf.KK.length())) + "/episodes/" + eps.getJSONObject(no - 1).getString("id");
    }

    /** The id of episode n in the kept list, or null when the list does not reach it. */
    static String idOf(Context c, String key, int no) {
        JSONObject o = load(c, key);
        JSONArray eps = o == null ? null : o.optJSONArray("eps");
        return eps == null || no < 1 || no > eps.length() ? null : eps.optJSONObject(no - 1).optString("id", null);
    }

    /** The number (1-based) of an episode id in the work's list, or 0. */
    static int numberOf(JSONObject list, String episodeId) {
        JSONArray eps = list.optJSONArray("eps");
        if (eps == null) return 0;
        for (int i = 0; i < eps.length(); i++) if (episodeId.equals(eps.optJSONObject(i).optString("id"))) return i + 1;
        return 0;
    }
}
