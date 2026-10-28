package jp.harusame.aozorareader;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 「たてがき帖の使い方」: a book bundled with the app (assets/guide.txt, Aozora notation), on a new shelf from
 * the start. It is read as a 青空文庫 work under an id 青空文庫 never uses (000000), so it has sections, a 目次
 * and places like one, without any request: its text comes from the app itself.
 */
final class Guide {
    private Guide() {
    }

    static final String KEY = Shelf.AO + "000000";
    static final String TITLE = "たてがき帖の使い方";
    /** set once the book was put on the shelf, so taking it off is not undone on the next start */
    static final String SEEDED = "guideSeeded";
    /** the hash of the guide.txt its kept sections were formatted from */
    static final String FORMATTED = "guideFormatted";

    static boolean is(String key) {
        return KEY.equals(key);
    }

    static String text(Context c) throws Exception {
        try (InputStream in = c.getAssets().open("guide.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Puts the book on the shelf the first time the app starts. On every start, the sections kept from an older
     * guide.txt are dropped, so an update's new pages are formatted and shown, not the kept ones (codex on 0.1.4:
     * whoever opened the guide in 0.1.3 would not have seen the episode slider, 英字 or 端の誤タッチ).
     */
    static void ensure(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("reader", Context.MODE_PRIVATE);
        try {
            String now = Integer.toHexString(text(c).hashCode());
            if (!now.equals(p.getString(FORMATTED, null))) {
                java.io.File kept = AozoraSite.sectionsFile(c, KEY);
                if (!kept.isFile() || kept.delete()) p.edit().putString(FORMATTED, now).apply();
            }
        } catch (Exception e) {
            Log.w(Shelf.TAG, "guide text", e);
        }
        if (p.getBoolean(SEEDED, false)) return;
        try {
            add(c);
            p.edit().putBoolean(SEEDED, true).apply();
        } catch (Exception e) {
            Log.w(Shelf.TAG, "guide", e);
        }
    }

    /** Puts the book on the shelf (again); null when it is there already. */
    static Shelf.Work add(Context c) throws Exception {
        AozoraSite.Book b = new AozoraSite.Book();
        b.id = KEY.substring(Shelf.AO.length());
        b.title = TITLE;
        b.author = "たてがき帖";
        b.card = "";
        b.zip = "";
        return Shelf.addAozora(c, b);
    }
}
