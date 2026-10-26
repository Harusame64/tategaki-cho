package jp.harusame.aozorareader;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
                File tmp = new File(file(dir).getPath() + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    out.write(all.toString().getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                Files.move(tmp.toPath(), file(dir).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
            for (Iterator<String> w = day.keys(); w.hasNext(); ) pages += Math.max(1, day.optJSONObject(w.next()).optInt("pages"));
            out.put(d.getDayOfMonth(), pages);
        }
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
