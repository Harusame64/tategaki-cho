package jp.harusame.aozorareader;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.harusame.aozorareader.core.KakuyomuWork;

/**
 * The works the user follows, where they are in each, and how many episodes each has. Kept as one JSON
 * file in the app's storage. Titles and episode counts come from the official なろうデベロッパー API;
 * episode text is never fetched from here.
 */
final class Shelf {
    static final String TAG = "AozoraReader";
    static final String USER_AGENT = "Tategakicho/0.1 (Android; personal vertical reader)";
    static final Pattern NCODE = Pattern.compile("ncode\\.syosetu\\.com/(n\\d{4}[a-z]{1,3})", Pattern.CASE_INSENSITIVE);
    static final Pattern EPISODE = Pattern.compile("ncode\\.syosetu\\.com/(n\\d{4}[a-z]{1,3})/(\\d{1,9})(?!\\d)/?", Pattern.CASE_INSENSITIVE);
    /** カクヨム works are kept under "kakuyomu-&lt;work id&gt;"; see KakuyomuSite. */
    static final String KK = "kakuyomu-";
    static final Pattern KK_WORK = Pattern.compile("kakuyomu(?:\\.jp/works|:/)/(\\d{6,30})(?!\\d)");
    /** an older, positional form (the n-th of the work's list), still accepted on the way in and resolved to an id */
    static final Pattern KK_EPISODE = Pattern.compile("^kakuyomu://(\\d{6,30})/(\\d{1,9})$");
    /** a カクヨム episode inside the app: kakuyomu://&lt;work&gt;/e/&lt;episode id&gt;, the same episode whatever the list does */
    static final Pattern KK_ID_EPISODE = Pattern.compile("^kakuyomu://(\\d{6,30})/e/(\\d{1,30})$");
    /** 青空文庫 works are kept under "aozora-&lt;work id&gt;"; see AozoraSite. */
    static final String AO = "aozora-";
    // Bounded digit runs: a longer number is not a work, and must not overflow a parse.
    static final Pattern AO_WORK = Pattern.compile("(?:aozora\\.gr\\.jp/cards/\\d{1,9}/card(\\d{1,9})\\.html|^aozora://(\\d{1,9})(?!\\d))");
    static final Pattern AO_EPISODE = Pattern.compile("^aozora://(\\d{1,9})/(\\d{1,9})$");
    static final Pattern KK_REAL_EPISODE = Pattern.compile("kakuyomu\\.jp/works/\\d{6,}/episodes/(\\d{6,})");

    static final class Work {
        String ncode;
        String title;
        String author;
        /** episodes the work has, from the API; 0 until known */
        int episodes;
        boolean shortStory;
        boolean finished;
        /** episodes the user has seen listed, for the "new" count */
        int seen;
        /** the episode and page last open; 0 when not started */
        /** the place: the episode's number (for カクヨム, its number in the list as last seen, shown only) */
        int lastEpisode;
        /** カクヨム: the place's episode id, which is what is opened (lastEpisode only names it) */
        String lastEpisodeId;
        int lastPage;
        /** pages of the episode last open, as laid out then; 0 when unknown */
        int lastPages;
        /** the work's page on its site, when it cannot be made from its key (青空文庫's 図書カード) */
        String page;
        long added;
        /** when the work was last read; 0 when never */
        long lastRead;
        /**
         * カクヨム: the ids of the episodes not yet seen listed (usually few), so new ones stay new whatever the
         * list does; null for a work saved before this was kept, whose first `seen` episodes count as seen.
         */
        String unseenIds;
        /** read from a shelf saved by a build that kept the seen ids instead (2026-10-25); turned into unseenIds on the next remap */
        String legacySeenIds;
        /** the user's tags for the work (読んでる, 異世界 …), in the order given */
        final List<String> tags = new ArrayList<>();
        /**
         * when a check first found the work gone from its site (deleted or private); 0 while it is there. In the
         * store build, for なろう, when its page first said so (an API that leaves it out is not enough).
         */
        long missingSince;
        /** store build, なろう: when the work's page was last asked whether it is there */
        long pageCheckedAt;
        /** store build: the kept text was dropped as gone; its page is not asked again unless the API lists it */
        boolean dropped;

        int newEpisodes() { return Math.max(0, episodes - seen); }

        /** What a check for new episodes owns: the work's details from its site. */
        void copyInfo(Work from) {
            title = from.title;
            author = from.author;
            episodes = from.episodes;
            shortStory = from.shortStory;
            finished = from.finished;
        }

        String startUrl() {
            if (shortStory) return "https://ncode.syosetu.com/" + ncode + "/";
            if (isKakuyomu(ncode) && lastEpisodeId != null) return kkEpisodeUrl(ncode, lastEpisodeId);
            return episodeUrl(ncode, Math.max(1, lastEpisode));
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject().put("ncode", ncode).put("title", title).put("author", author)
                    .put("episodes", episodes).put("short", shortStory).put("finished", finished).put("seen", seen)
                    .put("lastEpisode", lastEpisode).put("lastPage", lastPage).put("lastPages", lastPages).put("page", page == null ? "" : page).put("added", added)
                    .put("lastRead", lastRead).put("unseenIds", unseenIds == null ? JSONObject.NULL : unseenIds)
                    .put("tags", new JSONArray(tags));
            // Kept until a remap turns them into unseenIds; a save before that must not drop them.
            if (unseenIds == null && legacySeenIds != null) o.put("seenIds", legacySeenIds);
            if (lastEpisodeId != null) o.put("lastEpisodeId", lastEpisodeId);
            if (missingSince > 0) o.put("missingSince", missingSince);
            if (pageCheckedAt > 0) o.put("pageCheckedAt", pageCheckedAt);
            if (dropped) o.put("dropped", true);
            return o;
        }

        static Work fromJson(JSONObject o) {
            Work w = new Work();
            w.ncode = o.optString("ncode");
            w.title = o.optString("title", w.ncode);
            w.author = o.optString("author", "");
            w.episodes = o.optInt("episodes");
            w.shortStory = o.optBoolean("short");
            w.finished = o.optBoolean("finished");
            w.seen = o.optInt("seen");
            w.lastEpisode = o.optInt("lastEpisode");
            w.lastEpisodeId = o.optString("lastEpisodeId", null);
            w.lastPage = o.optInt("lastPage");
            w.lastPages = o.optInt("lastPages");
            w.page = o.optString("page", "");
            w.added = o.optLong("added");
            w.lastRead = o.optLong("lastRead");
            w.missingSince = o.optLong("missingSince");
            w.pageCheckedAt = o.optLong("pageCheckedAt");
            w.dropped = o.optBoolean("dropped");
            w.unseenIds = o.isNull("unseenIds") ? null : o.optString("unseenIds", null);
            // That build saved "" for "no ids kept": only a list of ids is taken.
            String legacy = o.optString("seenIds", "");
            if (w.unseenIds == null && !legacy.isEmpty()) w.legacySeenIds = legacy;
            JSONArray t = o.optJSONArray("tags");
            if (t != null) for (int i = 0; i < t.length(); i++) if (!t.optString(i).isEmpty()) w.tags.add(t.optString(i));
            return w;
        }
    }

    private final File file;
    private final File episodesDir;
    final List<Work> works = new ArrayList<>();
    long checked;

    private Shelf(Context c) {
        this(c.getFilesDir());
    }

    /** The shelf kept in dir (the app's files dir); a test gives it a temporary one. */
    Shelf(File dir) {
        file = new File(dir, "shelf.json");
        episodesDir = new File(dir, "episodes");
    }

    /**
     * The shelf as saved. A shelf.json that does not parse (cut short by a power loss) is set aside as
     * shelf.json.bad and the copy kept before the last save (shelf.json.bak) is read instead.
     */
    static Shelf load(Context c) {
        return load(c.getFilesDir());
    }

    /**
     * When shelf.json is missing although a copy exists, or does not parse, the copy is read and put back as
     * shelf.json (a bad file is kept aside as .bad), so the next save starts from it and not from nothing.
     */
    static Shelf load(File dir) {
        synchronized (Shelf.class) {
            Shelf s = new Shelf(dir);
            File bak = new File(s.file.getPath() + ".bak");
            int got = s.file.isFile() ? s.read(s.file) : (bak.isFile() ? BAD : OK);
            if (got == OK) return s;
            if (got == UNREAD) {
                // Not read (an I/O error): the file is not judged, and this shelf must not be saved over it.
                s.unread = true;
                return s;
            }
            Log.e(TAG, "shelf missing or unreadable; reading the copy before the last save");
            if (s.file.isFile()) s.file.renameTo(new File(s.file.getPath() + ".bad"));
            s.works.clear();
            s.checked = 0;
            int b = s.read(bak);
            if (b == OK) {
                try { Files.copy(bak.toPath(), s.file.toPath()); } catch (IOException e) { Log.e(TAG, "shelf not restored", e); }
            } else if (b == UNREAD) {
                s.unread = true;
            }
            return s;
        }
    }

    static final int OK = 0, BAD = 1, UNREAD = 2;
    /** the file could not be read (not: did not parse); saving this shelf is refused */
    boolean unread;

    /**
     * OK, BAD when the file does not parse, UNREAD when it could not be read. Read through a FileInputStream,
     * which an interrupted thread (a screen closing its worker) still reads; a channel would refuse it.
     */
    private int read(File f) {
        byte[] bytes;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            Log.e(TAG, "shelf not read", e);
            return UNREAD;
        }
        try {
            fromJson(new String(bytes, StandardCharsets.UTF_8));
            return OK;
        } catch (Exception e) {
            works.clear();
            return BAD;
        }
    }

    void fromJson(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        checked = o.optLong("checked");
        JSONArray a = o.optJSONArray("works");
        if (a != null) for (int i = 0; i < a.length(); i++) works.add(Work.fromJson(a.getJSONObject(i)));
    }

    String toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Work w : works) a.put(w.toJson());
        return new JSONObject().put("checked", checked).put("works", a).toString();
    }

    /** Written whole to a tmp file and synced, the old file kept as shelf.json.bak, then renamed into place. */
    void save() {
        if (unread) { Log.e(TAG, "shelf not saved: it was not read"); return; }
        synchronized (Shelf.class) {
            try {
                File tmp = new File(file.getPath() + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    out.write(toJson().getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                // The copy is made first and the new file renamed over the old in one step: shelf.json is never
                // missing, whenever the app stops.
                if (file.isFile()) Files.copy(file.toPath(), new File(file.getPath() + ".bak").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Log.e(TAG, "shelf not saved", e);
            }
        }
    }

    interface Change { void apply(Shelf s) throws Exception; }

    /**
     * Load, change and save as one step, so two writers (the reader saving its place, a check for new
     * episodes, an add from another screen) cannot save over each other. Nothing slow belongs inside.
     */
    static Shelf update(Context c, Change change) throws Exception {
        return update(c.getFilesDir(), change);
    }

    static Shelf update(File dir, Change change) throws Exception {
        synchronized (Shelf.class) {
            Shelf s = load(dir);
            if (s.unread) throw new IOException("本棚を読めませんでした");
            change.apply(s);
            s.save();
            return s;
        }
    }

    Work find(String ncode) {
        for (Work w : works) if (w.ncode.equals(ncode)) return w;
        return null;
    }

    /** Removes the work and every episode kept for it. */
    /** Takes works off the shelf and deletes everything kept for them. */
    static Shelf remove(Context c, java.util.Collection<String> keys) throws Exception {
        for (String k : keys) FullSave.stop(k);                             // also a fetch of it waiting its turn
        Shelf s = update(c, sh -> { for (String k : keys) { Work w = sh.find(k); if (w != null) sh.works.remove(w); } });
        for (String k : keys) {
            deleteTree(new File(s.episodesDir, k));
            new File(AozoraSite.worksDir(c), k + ".json").delete();
        }
        return s;
    }

    /** What the kept episodes of one folder under episodes/ take: how many and how many bytes. */
    static final class Kept {
        final String ncode;
        int episodes;
        long bytes;

        Kept(String ncode) { this.ncode = ncode; }
    }

    /** Every folder of kept episodes, on the shelf or not (a work taken off by an older version leaves one). */
    List<Kept> kept() {
        List<Kept> out = new ArrayList<>();
        File[] dirs = episodesDir.listFiles(File::isDirectory);
        if (dirs == null) return out;
        for (File d : dirs) {
            Kept k = new Kept(d.getName());
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) {
                k.bytes += f.length();
                if (f.getName().endsWith(".html")) k.episodes++;
            }
            out.add(k);
        }
        return out;
    }

    /**
     * Deletes the episodes of a work read past: those before the one last open, but the one just before it,
     * which turning back reaches without a fetch. With all, every kept episode and the 目次 go; a カクヨム
     * work keeps its list (kk.json), and a 青空文庫 work's details live elsewhere (AozoraSite.worksDir), so
     * either can still be read.
     * @return bytes freed
     */
    long prune(Context c, String ncode, boolean all) {
        if (unread) return 0;                                               // which works are on the shelf is not known
        File d = new File(episodesDir, ncode);
        Work w = find(ncode);
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        Map<String, Integer> kkNo = new java.util.HashMap<>();
        if (isKakuyomu(ncode)) {
            org.json.JSONObject list = KakuyomuSite.load(c, ncode);
            org.json.JSONArray eps = list == null ? null : list.optJSONArray("eps");
            if (eps != null) for (int i = 0; i < eps.length(); i++) kkNo.put("k" + eps.optJSONObject(i).optString("id") + ".html", i + 1);
        }
        long freed = 0;
        for (File f : fs) {
            String name = f.getName();
            if (w != null && name.equals("kk.json")) continue;
            boolean drop;
            if (all || w == null) drop = true;
            else if (!name.endsWith(".html") || w.lastEpisode <= 0) drop = false;
            else {
                Integer no = isKakuyomu(ncode) ? kkNo.get(name) : number(name);
                // カクヨム: where the place's episode is in the list now; nothing is dropped if it is not there.
                Integer at = !isKakuyomu(ncode) ? Integer.valueOf(w.lastEpisode)
                        : w.lastEpisodeId != null ? kkNo.get("k" + w.lastEpisodeId + ".html") : Integer.valueOf(w.lastEpisode);
                drop = no != null && at != null && no < at - 1;
            }
            if (drop) { freed += f.length(); f.delete(); }
        }
        if (w == null) d.delete();
        return freed;
    }

    /** n of "n.html", or null. */
    static Integer number(String name) {
        Matcher m = Pattern.compile("(\\d{1,9})\\.html").matcher(name);
        return m.matches() ? Integer.valueOf(m.group(1)) : null;
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** The work a URL names: a なろう ncode, or kakuyomu-&lt;work id&gt;; null for anything else. */
    static String ncodeOf(String text) {
        if (text == null) return null;
        Matcher m = NCODE.matcher(text);
        if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        Matcher k = KK_WORK.matcher(text);
        if (k.find()) return KK + k.group(1);
        Matcher a = AO_WORK.matcher(text);
        if (a.find()) return AO + aozoraId(a.group(1) != null ? a.group(1) : a.group(2));
        return null;
    }

    /** 青空文庫's work ids are six digits; its card URLs drop the leading zeros. */
    static String aozoraId(String n) {
        return String.format(Locale.ROOT, "%06d", Integer.parseInt(n));
    }

    /** Whether a なろう work's own page answers that it is not there (404 or 410); any other answer or failure is "there". */
    static boolean pageGone(String ncode) {
        try {
            Net.get("https://ncode.syosetu.com/" + ncode + "/");
            return false;
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            return m.equals("HTTP 404") || m.equals("HTTP 410");
        }
    }

    /** whether a work gone from its site has its kept text dropped (the store build; see app/build.gradle) */
    static final boolean DROP_GONE = BuildConfig.DROP_GONE;
    /** how long a work must stay gone, from the check that first found it so, before that */
    static final long GONE_CONFIRM_MS = 24L * 60 * 60 * 1000;

    static boolean isAozora(String key) {
        return key != null && key.startsWith(AO);
    }

    /** Tags offered from the start, before any work has them. */
    static final List<String> DEFAULT_TAGS = java.util.Arrays.asList("読んでる", "読み終えた", "保留");

    /** The tags to offer: the defaults first, then every other tag in use, in the order first met. */
    static List<String> allTags(List<Work> works) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(DEFAULT_TAGS);
        for (Work w : works) out.addAll(w.tags);
        out.remove("すべて");                                             // the chip for every work, never a tag
        return new ArrayList<>(out);
    }

    /**
     * Whether a work is listed: it has the tag chosen (null for all), and its title, author or a tag holds
     * every word typed.
     */
    static boolean matches(Work w, String words, String tag) {
        if (tag != null && !w.tags.contains(tag)) return false;
        String hay = ((w.title == null ? "" : w.title) + " " + (w.author == null ? "" : w.author) + " " + String.join(" ", w.tags))
                .toLowerCase(Locale.ROOT);
        for (String t : (words == null ? "" : words).trim().toLowerCase(Locale.ROOT).split("[\\s　]+"))
            if (!t.isEmpty() && !hay.contains(t)) return false;
        return true;
    }

    static boolean isKakuyomu(String key) {
        return key != null && key.startsWith(KK);
    }

    /** {work, episode number} of a なろう episode URL or an app-side kakuyomu://&lt;work&gt;/&lt;n&gt;, or null */
    static Object[] episodeOf(String url) {
        if (url == null) return null;
        Matcher m = EPISODE.matcher(url);
        if (m.find()) return new Object[]{m.group(1).toLowerCase(Locale.ROOT), Integer.parseInt(m.group(2))};
        Matcher k = KK_EPISODE.matcher(url);
        if (k.find()) return new Object[]{KK + k.group(1), Integer.parseInt(k.group(2))};
        Matcher a = AO_EPISODE.matcher(url);
        return a.find() ? new Object[]{AO + a.group(1), Integer.parseInt(a.group(2))} : null;
    }

    static String kkEpisodeUrl(String key, String id) {
        return "kakuyomu://" + key.substring(KK.length()) + "/e/" + id;
    }

    /** {work key, episode id} of kakuyomu://&lt;work&gt;/e/&lt;id&gt;, or null */
    static String[] kkEpisodeOf(String url) {
        Matcher k = url == null ? null : KK_ID_EPISODE.matcher(url);
        return k != null && k.find() ? new String[]{KK + k.group(1), k.group(2)} : null;
    }

    /** The same episode: the same work and number, or (カクヨム) the same work and id. */
    static boolean sameEpisode(String a, String b) {
        String[] p = kkEpisodeOf(a), q = kkEpisodeOf(b);
        if (p != null || q != null) return p != null && q != null && p[0].equals(q[0]) && p[1].equals(q[1]);
        Object[] x = episodeOf(a), y = episodeOf(b);
        return x != null && y != null && x[0].equals(y[0]) && x[1].equals(y[1]);
    }

    static String episodeUrl(String key, int no) {
        if (isKakuyomu(key)) return "kakuyomu://" + key.substring(KK.length()) + "/" + no;
        if (isAozora(key)) return "aozora://" + key.substring(AO.length()) + "/" + no;
        return "https://ncode.syosetu.com/" + key + "/" + no + "/";
    }

    /** The work's own page on its site. */
    static String workUrl(Work w) {
        return w.page != null && !w.page.isEmpty() ? w.page : workUrl(w.ncode);
    }

    /** The work's own page on its site; "" for the bundled guide book, which has none. */
    static String workUrl(String key) {
        if (Guide.is(key)) return "";
        if (isKakuyomu(key)) return KakuyomuSite.workPage(key.substring(KK.length()));
        if (isAozora(key)) return "https://www.aozora.gr.jp/";
        return "https://ncode.syosetu.com/" + key + "/";
    }

    /**
     * Adds a work named by a なろう URL (or ncode) and asks the API for its title once.
     * @return the work added, or null when it is already on the shelf
     * @throws Exception when the text names no work, or the API does not know it
     */
    static Work add(Context c, String text) throws Exception {
        String ncode = ncodeOf(text);
        if (ncode == null && text != null && text.trim().matches("(?i)n\\d{4}[a-z]{1,3}")) ncode = text.trim().toLowerCase(Locale.ROOT);
        if (ncode == null) throw new IllegalArgumentException("なろう・カクヨムの作品の URL ではありません");
        if (load(c).find(ncode) != null) return null;
        if (isAozora(ncode)) {
            // A 図書カード URL: the work is taken from the kept list of 青空文庫, which has what reading it needs.
            String id = ncode.substring(AO.length());
            if (AozoraSite.indexFile(c).isFile())
                for (AozoraSite.Book b : AozoraSite.loadIndex(c)) if (b.id.equals(id)) return addAozora(c, b);
            throw new IllegalArgumentException("青空文庫の作品は「青空文庫」のタブから入れてください");
        }
        // The site is asked first, outside the shelf's lock; then the work is put on the latest shelf.
        Work w = new Work();
        w.ncode = ncode;
        w.title = ncode;
        w.added = System.currentTimeMillis();
        if (isKakuyomu(ncode)) {
            org.json.JSONObject list = KakuyomuSite.save(c, ncode, KakuyomuSite.fetchWork(ncode.substring(KK.length())));
            fromKakuyomu(w, list);
            Matcher e = KK_REAL_EPISODE.matcher(text);
            int at = e.find() ? KakuyomuSite.numberOf(list, e.group(1)) : 0;
            // Only a listed episode becomes the place (one not listed has no number or neighbours to go on from).
            if (at > 0) { w.lastEpisodeId = e.group(1); w.lastEpisode = at; }
            KakuyomuSite.seeAll(c, w);
        } else {
            Map<String, Work> got = narouInfo(java.util.Collections.singletonList(ncode));
            Work info = got.get(ncode);
            if (info == null) throw new IOException("見つかりません");
            w.copyInfo(info);
            Object[] ep = text == null ? null : episodeOf(text);
            if (ep != null) w.lastEpisode = (Integer) ep[1];
        }
        w.seen = w.episodes;
        boolean[] added = {false};
        update(c, s -> { if (s.find(w.ncode) == null) { s.works.add(w); added[0] = true; } });
        return added[0] ? w : null;
    }

    /** What sharing a work sends: its title, author and page, to recommend it to someone. */
    static String shareText(Work w) {
        return "『" + w.title + "』" + (w.author == null || w.author.isEmpty() ? "" : " " + w.author)
                + "\n" + workUrl(w);
    }

    /** Puts a 青空文庫 work from the index on the shelf; its text is fetched when first read. */
    static Work addAozora(Context c, AozoraSite.Book b) throws Exception {
        String key = AO + b.id;
        AozoraSite.saveMeta(c, key, b);
        if (load(c).find(key) != null) return null;
        Work w = new Work();
        w.ncode = key;
        w.title = b.subtitle == null || b.subtitle.isEmpty() ? b.title : b.title + "　" + b.subtitle;
        w.author = b.translator == null || b.translator.isEmpty() ? b.author : b.author + "（" + b.translator + " 訳）";
        w.finished = true;
        w.page = b.card;
        w.added = System.currentTimeMillis();
        boolean[] added = {false};
        update(c, s -> { if (s.find(key) == null) { s.works.add(w); added[0] = true; } });
        return added[0] ? w : null;
    }

    /** The episode a shared URL points at (なろう's number, or カクヨム's place in the kept list), or 0. */
    static int sharedEpisode(Context c, String text) {
        if (text == null) return 0;
        Object[] ep = episodeOf(text);
        if (ep != null) return (Integer) ep[1];
        Matcher e = KK_REAL_EPISODE.matcher(text);
        String key = ncodeOf(text);
        if (e.find() && isKakuyomu(key)) {
            org.json.JSONObject list = KakuyomuSite.load(c, key);
            if (list != null) return KakuyomuSite.numberOf(list, e.group(1));
        }
        return 0;
    }

    /** The episode id of a shared カクヨム episode URL, or null. */
    static String sharedKakuyomuId(String text) {
        Matcher e = text == null ? null : KK_REAL_EPISODE.matcher(text);
        return e != null && e.find() ? e.group(1) : null;
    }

    static void fromKakuyomu(Work w, org.json.JSONObject list) {
        w.title = list.optString("title", w.title);
        w.author = list.optString("author", w.author);
        w.finished = list.optBoolean("finished");
        w.shortStory = false;
        w.episodes = list.optJSONArray("eps") == null ? 0 : list.optJSONArray("eps").length();
    }

    /** A check for new episodes is running (the shelf's or the background one): a second is not started. */
    static final java.util.concurrent.atomic.AtomicBoolean CHECKING = new java.util.concurrent.atomic.AtomicBoolean();

    private static final Object CHECK_DONE = new Object();
    /** checks that finished and saved their result; a waiter takes the result when this moved */
    static final java.util.concurrent.atomic.AtomicLong CHECKS_DONE = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Asks the sites about the given works — なろう in one API request, カクヨム one work page each — and puts
     * what they say on the latest shelf (this one is brought up to date with it). 青空文庫 works are finished
     * and are not asked about. One check runs at a time; this one waits for a running one and takes its result.
     * @return the works the sites no longer have (deleted or made private)
     */
    List<Work> refresh(List<Work> all, Context ctx) throws Exception {
        return refresh(all, ctx, true, 0, null);
    }

    /**
     * @param waitForRunning when another check is running: true waits for it and takes its result (the shelf's
     *        own check, so what it shows is that check's), false does nothing (the background check)
     * @param unlessCheckedWithin once this check has its turn, it does nothing when the shelf (read again then)
     *        was checked less than this long ago; 0 always checks
     * @param withTurn run after the check, before the turn is let go (the background check's news: so it is
     *        always ordered before a waiting shelf clears it); null for none
     * @return null when nothing was asked (another check ran, or one ran within unlessCheckedWithin)
     */
    List<Work> refresh(List<Work> all, Context ctx, boolean waitForRunning, long unlessCheckedWithin, Runnable withTurn) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 10 * 60 * 1000;
        while (true) {
            // Counted before trying for the turn: a check that saves in between still counts as finished.
            long done = CHECKS_DONE.get();
            if (CHECKING.compareAndSet(false, true)) break;
            if (!waitForRunning) return null;
            synchronized (CHECK_DONE) {
                while (CHECKING.get() && android.os.SystemClock.elapsedRealtime() < until) CHECK_DONE.wait(1000);
            }
            // The count first, then the shelf: a check counts only after it saved, so a moved count means the
            // shelf read next holds at least that check's result.
            long now = CHECKS_DONE.get();
            Shelf fresh = load(ctx);
            if (!fresh.unread && now != done) {
                // The running check finished: its result is this one's.
                works.clear();
                works.addAll(fresh.works);
                checked = fresh.checked;
                return new ArrayList<>();
            }
            // It ended without finishing (a dead network, a failing site): this check takes its own turn.
            if (android.os.SystemClock.elapsedRealtime() >= until) throw new IOException("確認が終わりません");
        }
        try {
            if (unlessCheckedWithin > 0) {
                // Read again with the turn held: a check that ended just before is not repeated.
                Shelf fresh = load(ctx);
                if (fresh.unread || System.currentTimeMillis() - fresh.checked < unlessCheckedWithin) return null;
                works.clear();
                works.addAll(fresh.works);
                checked = fresh.checked;
                all = new ArrayList<>(fresh.works);
            }
            List<Work> missing = check(all, ctx);
            if (withTurn != null) withTurn.run();
            return missing;
        } finally {
            CHECKING.set(false);
            synchronized (CHECK_DONE) { CHECK_DONE.notifyAll(); }
        }
    }

    private List<Work> check(List<Work> all, Context ctx) throws Exception {
        List<String> narou = new ArrayList<>();
        Map<String, Work> got = new java.util.HashMap<>();
        List<String> gone = new ArrayList<>();
        for (Work w : all) {
            if (isAozora(w.ncode)) continue;
            if (!isKakuyomu(w.ncode)) { narou.add(w.ncode); continue; }
            try {
                KakuyomuWork page = KakuyomuSite.fetchWork(w.ncode.substring(KK.length()));
                org.json.JSONObject list = null;
                synchronized (Shelf.class) {
                    // Taken off while its page was being read: nothing is kept for it.
                    if (load(ctx).find(w.ncode) != null) list = KakuyomuSite.save(ctx, w.ncode, page);
                }
                if (list == null) continue;
                Work t = new Work();
                fromKakuyomu(t, list);
                got.put(w.ncode, t);
            } catch (IOException e) {
                if (String.valueOf(e.getMessage()).contains("HTTP 404")) gone.add(w.ncode);
                else Log.w(TAG, "kakuyomu " + w.ncode, e);
            } catch (Exception e) {
                // A page laid out differently stops this work's check, not the others'.
                Log.w(TAG, "kakuyomu " + w.ncode, e);
            }
        }
        // The API answers up to 500 works a request.
        for (int i = 0; i < narou.size(); i += 500) {
            List<String> part = narou.subList(i, Math.min(narou.size(), i + 500));
            Map<String, Work> n = narouInfo(part);
            got.putAll(n);
            for (String k : part) if (!n.containsKey(k)) gone.add(k);
        }
        long now = System.currentTimeMillis();
        // The store build drops the kept text of a work gone from its site only on two answers from the work's own
        // page, a day or more apart, that it is not there: one failing answer must not cost the text. カクヨム's
        // "gone" is already its page's 404. なろう's API also leaves out a work its author took out of search, which
        // is still read on its page, so for なろう the page is asked: when the API first leaves the work out (at most
        // once a day while the page is there), and again a day after the page first said it was gone.
        Map<String, Boolean> pageSaysGone = new java.util.HashMap<>();
        if (DROP_GONE) {
            Shelf before = load(ctx);
            for (String k : gone) {
                Work w = before.find(k);
                if (w == null || isKakuyomu(k) || w.dropped) continue;
                // a day after the page was last asked (for a work it said was gone, that is when it first said so)
                if (now - w.pageCheckedAt >= GONE_CONFIRM_MS) pageSaysGone.put(k, pageGone(k));
            }
        }
        List<String> drop = new ArrayList<>();
        Shelf fresh = update(ctx, s -> {
            for (Map.Entry<String, Work> e : got.entrySet()) {
                Work w = s.find(e.getKey());
                if (w != null) { w.copyInfo(e.getValue()); w.missingSince = 0; w.dropped = false; }
            }
            for (String k : gone) {
                Work w = s.find(k);
                if (w == null) continue;
                if (!DROP_GONE) { if (w.missingSince == 0) w.missingSince = now; continue; }   // marked only
                if (w.dropped) continue;                                     // done: nothing left to drop
                boolean goneNow;
                if (isKakuyomu(k)) goneNow = true;
                else {
                    Boolean said = pageSaysGone.get(k);
                    if (said == null) continue;                              // not asked this time: as it was
                    w.pageCheckedAt = now;
                    goneNow = said;
                }
                if (!goneNow) w.missingSince = 0;                            // still read on its page
                else if (w.missingSince == 0) w.missingSince = now;
                else if (now - w.missingSince >= GONE_CONFIRM_MS) drop.add(k);
            }
            s.checked = now;
        });
        // The work stays on the shelf, with its place. Marked dropped only once its text is gone: cut short before
        // that, the next check a day on asks again and drops it then.
        for (String k : drop) {
            FullSave.prune(ctx, fresh, k, true);
            update(ctx, s -> { Work w = s.find(k); if (w != null) w.dropped = true; });
        }
        if (!drop.isEmpty()) fresh = load(ctx);                                // with the dropped marks
        CHECKS_DONE.incrementAndGet();
        works.clear();
        works.addAll(fresh.works);
        checked = fresh.checked;
        // What the shelf reports as gone. The store build reports only works its site's page says are gone: a なろう
        // work out of search is left out of the API but is still read on its page.
        List<Work> missing = new ArrayList<>();
        for (String k : gone) { Work w = find(k); if (w != null && (!DROP_GONE || w.missingSince > 0)) missing.add(w); }
        return missing;
    }

    /** なろう works' details from the official API, in one request (up to 500), by ncode. */
    static Map<String, Work> narouInfo(List<String> ncodes) throws Exception {
        Map<String, Work> out = new java.util.HashMap<>();
        StringBuilder codes = new StringBuilder();
        for (String k : ncodes) codes.append(codes.length() == 0 ? "" : "-").append(k);
        JSONArray a = new JSONArray(new String(Net.get("https://api.syosetu.com/novelapi/api/?out=json&of=n-t-w-ga-nt-e&lim=500&ncode=" + codes),
                StandardCharsets.UTF_8));
        // The first element is {"allcount": n}; works follow.
        for (int i = 1; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Work w = new Work();
            w.ncode = o.optString("ncode").toLowerCase(Locale.ROOT);
            w.title = o.optString("title", w.ncode);
            w.author = o.optString("writer", "");
            w.shortStory = o.optInt("noveltype") == 2;
            w.episodes = w.shortStory ? 1 : o.optInt("general_all_no");
            w.finished = o.optInt("end", 1) == 0;
            out.put(w.ncode, w);
        }
        Log.i(TAG, "API: " + (a.length() - 1) + " of " + ncodes.size() + " work(s)");
        return out;
    }
}
