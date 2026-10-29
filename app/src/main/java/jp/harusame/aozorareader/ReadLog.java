package jp.harusame.aozorareader;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What was read on which day, for the calendar ("あの頃読んでたな"): per day and work, the episodes read (first
 * and last) and how many pages were turned. Kept only on the phone, in readlog.json beside the shelf.
 */
final class ReadLog {
    private ReadLog() { }

    /** One work on one day. */
    static final class Entry {
        String key, title;
        int first, last, pages;
    }

    static File file(File dir) { return new File(dir, "readlog.json"); }

    /** The log; empty when there is none yet or (for reading only) it cannot be used. */
    private static JSONObject load(File dir) {
        try {
            return loadForWrite(dir);
        } catch (Exception e) {
            Log.w(Shelf.TAG, "read log unreadable", e);
            return new JSONObject();
        }
    }

    /**
     * The log to add to. A log that does not parse (cut short by a power loss) is set aside as
     * readlog.json.bad-&lt;time&gt; and a new one started; one that cannot be read throws, and nothing is written over it.
     */
    private static JSONObject loadForWrite(File dir) throws java.io.IOException {
        File f = file(dir);
        if (!f.isFile()) return new JSONObject();
        byte[] b;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { b = in.readAllBytes(); }
        try {
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (Exception bad) {
            Log.e(Shelf.TAG, "read log does not parse; kept aside as readlog.json.bad-<time>");
            // Its own name: a later bad log must not replace an earlier one (with years in it).
            f.renameTo(new File(dir, "readlog.json.bad-" + System.currentTimeMillis()));
            return new JSONObject();
        }
    }

    /** A page read: episode `episode` of the work, `pages` more pages turned (0 when only opened). */
    static void record(File dir, LocalDate day, String key, String title, int episode, int pages) {
        if (Guide.is(key)) return;                                       // the bundled guide book is not reading
        synchronized (ReadLog.class) {
            try {
                JSONObject all = loadForWrite(dir);
                JSONObject d = all.optJSONObject(day.toString());
                if (d == null) all.put(day.toString(), d = new JSONObject());
                JSONObject w = d.optJSONObject(key);
                if (w == null) {
                    d.put(key, w = new JSONObject().put("first", episode).put("last", episode).put("pages", 0));
                }
                if (title != null && !title.isEmpty()) w.put("t", title);
                if (episode > 0) {
                    w.put("first", w.optInt("first") <= 0 ? episode : Math.min(w.optInt("first"), episode));
                    w.put("last", Math.max(w.optInt("last"), episode));
                }
                w.put("pages", w.optInt("pages") + Math.max(0, pages));
                Atomic.write(file(dir), all.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(Shelf.TAG, "read log not written", e);
            }
        }
    }

    /** Pages turned on each day of the month (days with none are left out). */
    static Map<Integer, Integer> month(File dir, YearMonth m) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        JSONObject all;
        synchronized (ReadLog.class) { all = load(dir); }
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            LocalDate d;
            try { d = LocalDate.parse(k); } catch (Exception e) { continue; }
            if (!YearMonth.from(d).equals(m)) continue;
            JSONObject day = all.optJSONObject(k);
            int pages = 0;
            boolean read = false;
            for (Iterator<String> w = day.keys(); w.hasNext(); ) {
                String key = w.next();
                if (Guide.is(key)) continue;                                // logged before the guide was left out
                read = true;
                pages += Math.max(1, day.optJSONObject(key).optInt("pages"));
            }
            if (read) out.put(d.getDayOfMonth(), pages);
        }
        return out;
    }

    /** One work over a span of days: on how many days it was read, its episodes, its pages. */
    static final class WorkSum {
        String key, title;
        int days, episodes, pages;
    }

    /** A span of days summed up: the days something was read, and each work read, most days first. */
    static final class Summary {
        int days, episodes;
        final List<WorkSum> works = new ArrayList<>();
    }

    /**
     * The days from first to last (inclusive) summed up. Episodes are counted once each per work however many
     * days they were open, a day's span from its first to its last episode taken as read through; 青空文庫
     * sections and short stories are not episodes, and the bundled guide book is left out altogether.
     * Works are ordered by the days they were read on, then by pages.
     */
    static Summary summary(File dir, LocalDate first, LocalDate last) {
        Summary out = new Summary();
        JSONObject all;
        synchronized (ReadLog.class) { all = load(dir); }
        Map<String, WorkSum> works = new LinkedHashMap<>();
        Map<String, java.util.Set<Integer>> eps = new LinkedHashMap<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String k = it.next();
            LocalDate d;
            try { d = LocalDate.parse(k); } catch (Exception e) { continue; }
            if (d.isBefore(first) || d.isAfter(last)) continue;
            JSONObject day = all.optJSONObject(k);
            boolean read = false;
            for (Iterator<String> w = day.keys(); w.hasNext(); ) {
                String key = w.next();
                if (Guide.is(key)) continue;
                read = true;
                JSONObject e = day.optJSONObject(key);
                WorkSum ws = works.computeIfAbsent(key, x -> { WorkSum n = new WorkSum(); n.key = x; return n; });
                if (!e.optString("t").isEmpty() || ws.title == null) ws.title = e.optString("t", key);
                ws.days++;
                ws.pages += e.optInt("pages");
                java.util.Set<Integer> s = eps.computeIfAbsent(key, x -> new java.util.HashSet<>());
                int a = e.optInt("first"), b = e.optInt("last");
                if (!Shelf.isAozora(key) && a > 0 && b >= a) for (int n = a; n <= b; n++) s.add(n);
            }
            if (read) out.days++;
        }
        for (WorkSum ws : works.values()) {
            ws.episodes = eps.get(ws.key).size();
            out.episodes += ws.episodes;
            out.works.add(ws);
        }
        out.works.sort((a, b) -> a.days != b.days ? Integer.compare(b.days, a.days) : Integer.compare(b.pages, a.pages));
        return out;
    }

    /**
     * How deep a read day's mark is in the calendar (1 … 4), by the pages turned that day: fixed steps, so a day
     * reads the same in any month (a month's only day read is not its deepest).
     */
    static int depth(int pages) {
        return pages < 5 ? 1 : pages < 20 ? 2 : pages < 50 ? 3 : 4;
    }

    /** The works read on a day, most pages first. */
    static List<Entry> day(File dir, LocalDate day) {
        List<Entry> out = new ArrayList<>();
        JSONObject d;
        synchronized (ReadLog.class) { d = load(dir).optJSONObject(day.toString()); }
        if (d == null) return out;
        for (Iterator<String> it = d.keys(); it.hasNext(); ) {
            String k = it.next();
            if (Guide.is(k)) continue;
            JSONObject w = d.optJSONObject(k);
            Entry e = new Entry();
            e.key = k;
            e.title = w.optString("t", k);
            e.first = w.optInt("first");
            e.last = w.optInt("last");
            e.pages = w.optInt("pages");
            out.add(e);
        }
        out.sort((a, b) -> Integer.compare(b.pages, a.pages));
        return out;
    }
}
