package jp.harusame.aozorareader;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Episodes kept in the app: where each is kept, and fetching one once. Used by the reader and by 全話を保存. */
final class Episodes {
    private Episodes() {
    }

    /**
     * One lock per episode: opening and read-ahead asking for the same one fetch it once (the second finds what
     * the first kept), while a slow fetch of one episode does not hold up another.
     */
    static final java.util.concurrent.ConcurrentHashMap<String, Object> FETCHING = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * One episode: read from the app's own cache, or fetched once and kept there. Only なろう's and カクヨム's
     * episode pages of works on the shelf.
     */
    static String get(Context c, String url) throws Exception {
        return get(c, url, null);
    }

    /** @param wanted asked when the request's turn comes; see {@link Net#get(String, java.util.function.BooleanSupplier)} */
    static String get(Context c, String url, java.util.function.BooleanSupplier wanted) throws Exception {
        // A kept episode is read without any lock (it is written whole, by a rename).
        File kept = cacheFile(c, url);
        if (kept.isFile()) {
            Log.i(Shelf.TAG, "cache hit " + url);
            return new String(Files.readAllBytes(kept.toPath()), StandardCharsets.UTF_8);
        }
        synchronized (FETCHING.computeIfAbsent(url, k -> new Object())) { return fetch(c, url, wanted); }
    }

    private static String fetch(Context c, String url, java.util.function.BooleanSupplier wanted) throws Exception {
        File cache = cacheFile(c, url);
        if (cache.isFile()) {
            Log.i(Shelf.TAG, "cache hit " + url);
            return new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8);
        }
        // Only works on the shelf are fetched.
        String key = Shelf.ncodeOf(url);
        if (key == null || Shelf.load(c).find(key) == null) throw new IllegalStateException("本棚にない作品です");
        String real = Shelf.isKakuyomu(key) ? KakuyomuSite.realUrl(c, url) : url;
        URL u = new URL(real);
        if (!ReaderActivity.allowed(u)) throw new IllegalArgumentException("not a なろう・カクヨム episode URL: " + url);
        byte[] bytes = Net.get(u.toString(), wanted);
        synchronized (Shelf.class) {
            // Taken off the shelf while it was being fetched: nothing is kept for it.
            if (Shelf.load(c).find(key) != null) {
                Atomic.write(cache, bytes);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** episodes/&lt;ncode&gt;/&lt;no&gt;.html (0 for a short story), so removing a work removes its folder. */
    static File cacheFile(Context c, String url) throws Exception {
        String[] kk = Shelf.kkEpisodeOf(url);
        if (kk != null) return new File(c.getFilesDir(), "episodes/" + kk[0] + "/k" + kk[1] + ".html");
        Object[] ep = Shelf.episodeOf(url);
        if (ep != null && Shelf.isAozora((String) ep[0])) return AozoraSite.sectionsFile(c, (String) ep[0]);
        if (ep != null && Shelf.isKakuyomu((String) ep[0])) {
            // Kept by the episode's id, so an episode added or removed before it does not shift what n holds.
            String id = KakuyomuSite.idOf(c, (String) ep[0], (Integer) ep[1]);
            if (id != null && !jp.harusame.aozorareader.core.KakuyomuWork.EPISODE_ID.matcher(id).matches()) id = null;   // a list kept before ids were checked
            return new File(c.getFilesDir(), "episodes/" + ep[0] + "/" + (id == null ? "pending-" + ep[1] : "k" + id) + ".html");
        }
        if (ep != null) return new File(c.getFilesDir(), "episodes/" + ep[0] + "/" + ep[1] + ".html");
        String ncode = Shelf.ncodeOf(url);
        if (ncode == null) throw new IllegalArgumentException("not a なろう URL: " + url);
        return new File(c.getFilesDir(), "episodes/" + ncode + "/0.html");
    }

    /**
     * なろう keeps episodes by number, so an episode put in between (割り込み投稿) or taken out moved every one after it
     * to another number while the kept files stayed: the kept 5.html showed under 5 while 5 was now another episode
     * (review on 0.1.6). With the whole 目次 read, each kept episode is found by its title, nearest its old number,
     * and renamed to where it is now; one no longer listed is dropped, or in the personal build (which keeps text a
     * work no longer shows) moved to gone/. Called under the shelf's lock.
     *
     * @return old number to new, for each that moved (0 when it is no longer listed); empty when nothing did
     */
    static java.util.Map<Integer, Integer> followNarou(Context c, String ncode, org.json.JSONArray entries) throws Exception {
        java.util.Map<Integer, Integer> moved = new java.util.TreeMap<>();
        File dir = new File(c.getFilesDir(), "episodes/" + ncode);
        File[] kept = dir.listFiles((d, n) -> n.matches("[0-9]{1,9}\\.html"));
        if (kept == null || kept.length == 0) return moved;
        java.util.Map<Integer, String> listed = new java.util.HashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            org.json.JSONObject e = entries.getJSONObject(i);
            listed.put(e.getInt("n"), e.optString("t").trim());
        }
        for (File f : kept) {
            int n = Integer.parseInt(f.getName().substring(0, f.getName().length() - 5));
            String title = jp.harusame.aozorareader.core.EpisodeInfo.narouTitle(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (title == null || title.equals(listed.get(n))) continue;
            // Nearest first: a title used twice (第1話 in two parts) is taken where the episode most likely went.
            int to = 0;
            for (int d = 1; d <= listed.size() && to == 0; d++) {
                if (title.equals(listed.get(n + d))) to = n + d;
                else if (title.equals(listed.get(n - d))) to = n - d;
            }
            moved.put(n, to);
        }
        if (moved.isEmpty()) return moved;
        // In two steps, so an episode moving to a number another one still holds does not overwrite it.
        for (int n : moved.keySet()) {
            Files.move(new File(dir, n + ".html").toPath(), new File(dir, n + ".html.moving").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        for (java.util.Map.Entry<Integer, Integer> m : moved.entrySet()) {
            File from = new File(dir, m.getKey() + ".html.moving");
            if (m.getValue() > 0) {
                Files.move(from.toPath(), new File(dir, m.getValue() + ".html").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } else if (FullSave.ENABLED) {
                File gone = new File(dir, "gone");
                gone.mkdirs();
                Files.move(from.toPath(), new File(gone, m.getKey() + "-" + System.currentTimeMillis() + ".html").toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } else {
                from.delete();
            }
        }
        Log.i(Shelf.TAG, "なろう episodes moved " + ncode + " " + moved);
        return moved;
    }
}
