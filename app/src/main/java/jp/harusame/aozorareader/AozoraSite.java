package jp.harusame.aozorareader;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 青空文庫: works free to read and share. Its official list of works (a 2 MB zip of CSV) is fetched once and
 * kept as a compact index to browse and search on the phone; a work's text is its ruby text zip, fetched once
 * when first read and formatted into sections (改ページ), which the reader turns like episodes. Inside the app
 * a section is aozora://&lt;work id&gt;/&lt;n&gt;.
 */
final class AozoraSite {
    private AozoraSite() { }

    static final String LIST_URL = "https://www.aozora.gr.jp/index_pages/list_person_all_extended_utf8.zip";
    static final String RANKING_URL = "https://www.aozora.gr.jp/access_ranking/2022_xhtml.html";
    /** The kept list is fetched again when this old (new works are added every month or so). */
    static final long LIST_FRESH_MS = 30L * 24 * 60 * 60 * 1000;
    static final String RANKING_LABEL = "2022年 年間アクセス数（青空文庫の公開データ）";

    /** One work of the index. */
    static final class Book {
        String id, title, yomi, subtitle, ndc, kana, author, authorYomi, personId, translator, zip, card;
        boolean modern() { return "新字新仮名".equals(kana); }
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "aozora-index");
        d.mkdirs();
        return d;
    }

    static File indexFile(Context c) { return new File(dir(c), "index.tsv"); }

    static byte[] get(String url) throws IOException {
        return Net.get(url);
    }

    /** Fetches the official list and keeps it as index.tsv: one line a work, its author (not a translator) first. */
    static void refreshIndex(Context c) throws Exception {
        byte[] zip = get(LIST_URL);
        Map<String, Book> books = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.getName().endsWith(".csv")) continue;
                Reader r = new BufferedReader(new InputStreamReader(z, StandardCharsets.UTF_8));
                List<String> head = csvRow(r);
                if (head == null) break;
                Map<String, Integer> col = new HashMap<>();
                for (int i = 0; i < head.size(); i++) col.put(head.get(i).replace("﻿", ""), i);
                List<String> row;
                while ((row = csvRow(r)) != null) {
                    String id = at(row, col, "作品ID");
                    if (id.isEmpty() || !"なし".equals(at(row, col, "作品著作権フラグ"))) continue;
                    String role = at(row, col, "役割フラグ");
                    String name = (at(row, col, "姓") + " " + at(row, col, "名")).trim();
                    Book b = books.get(id);
                    if (b == null) {
                        b = new Book();
                        b.id = id;
                        b.title = at(row, col, "作品名");
                        b.yomi = at(row, col, "ソート用読み");
                        b.subtitle = at(row, col, "副題");
                        b.ndc = at(row, col, "分類番号").replace("NDC ", "");
                        b.kana = at(row, col, "文字遣い種別");
                        b.zip = at(row, col, "テキストファイルURL");
                        b.card = at(row, col, "図書カードURL");
                        books.put(id, b);
                    }
                    if ("著者".equals(role) && b.author == null) {
                        b.author = name;
                        b.authorYomi = at(row, col, "姓読みソート用") + at(row, col, "名読みソート用");
                        b.personId = at(row, col, "人物ID");
                    } else if ("翻訳者".equals(role) && b.translator == null) {
                        b.translator = name;
                    }
                }
            }
        }
        // A page that was not the list (an error page with 200) gives few or none: the kept list stays.
        int had = 0;
        if (indexFile(c).isFile()) try (java.util.stream.Stream<String> lines = Files.lines(indexFile(c).toPath())) { had = (int) lines.count(); }
        if (books.size() < 10_000 || books.size() < had / 2)
            throw new IOException("作品一覧が短すぎます（" + books.size() + " 件）");
        StringBuilder sb = new StringBuilder();
        for (Book b : books.values()) {
            if (b.zip == null || !b.zip.endsWith(".zip")) continue;
            if (b.author == null) { b.author = b.translator == null ? "" : b.translator; b.authorYomi = ""; b.personId = ""; }
            String[] f = {b.id, b.title, b.yomi, b.subtitle, b.ndc, b.kana, b.author, b.authorYomi, b.personId,
                    b.translator == null ? "" : b.translator, b.zip, b.card};
            for (int i = 0; i < f.length; i++) sb.append(i == 0 ? "" : "\t").append(f[i].replace('\t', ' ').replace('\n', ' '));
            sb.append('\n');
        }
        File tmp = new File(dir(c), "index.tsv.tmp");
        Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(indexFile(c))) throw new IOException("rename");
        Log.i(Shelf.TAG, "aozora index: " + books.size() + " works");
    }

    static String at(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? "" : row.get(i);
    }

    /** One CSV record (quoted fields may hold commas, quotes and line breaks), or null at the end. */
    static List<String> csvRow(Reader r) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false, any = false;
        int ch;
        while ((ch = r.read()) != -1) {
            any = true;
            char c = (char) ch;
            if (quoted) {
                if (c == '"') {
                    r.mark(1);
                    int n = r.read();
                    if (n == '"') f.append('"');
                    else { quoted = false; if (n != -1) r.reset(); }
                } else f.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { out.add(f.toString()); f.setLength(0); }
            else if (c == '\n') { out.add(f.toString()); return out; }
            else if (c != '\r') f.append(c);
        }
        if (!any) return null;
        out.add(f.toString());
        return out;
    }

    static List<Book> loadIndex(Context c) throws IOException {
        List<Book> out = new ArrayList<>();
        for (String line : Files.readAllLines(indexFile(c).toPath(), StandardCharsets.UTF_8)) {
            String[] f = line.split("\t", -1);
            if (f.length < 12) continue;
            Book b = new Book();
            b.id = f[0]; b.title = f[1]; b.yomi = f[2]; b.subtitle = f[3]; b.ndc = f[4]; b.kana = f[5];
            b.author = f[6]; b.authorYomi = f[7]; b.personId = f[8]; b.translator = f[9]; b.zip = f[10]; b.card = f[11];
            out.add(b);
        }
        return out;
    }

    /** The access ranking's works, by card URL number, in order (fetched once and kept). */
    static List<String[]> ranking(Context c) throws Exception {
        File f = new File(dir(c), "ranking.html");
        if (!f.isFile()) Files.write(f.toPath(), get(RANKING_URL));
        String html = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        List<String[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(html);
        Pattern card = Pattern.compile("card(\\d+)\\.html"), count = Pattern.compile(">\\s*(\\d{3,})\\s*<");
        while (m.find()) {
            Matcher cm = card.matcher(m.group(1));
            if (!cm.find()) continue;
            Matcher n = count.matcher(m.group(1));
            String hits = "";
            while (n.find()) hits = n.group(1);
            out.add(new String[]{cm.group(1), hits});
        }
        return out;
    }

    /** The work's details kept when it went onto the shelf: zip and card URLs, title and author. */
    /**
     * Where a shelved 青空文庫 work's details are kept: outside episodes/, which is a cache the storage screen
     * may empty and backup leaves out, since reading the work again needs its zip URL.
     */
    static File worksDir(Context c) {
        File d = new File(c.getFilesDir(), "works");
        d.mkdirs();
        return d;
    }

    static void saveMeta(Context c, String key, Book b) throws Exception {
        JSONObject o = new JSONObject().put("zip", b.zip).put("card", b.card).put("title", b.title)
                .put("subtitle", b.subtitle).put("author", b.author).put("translator", b.translator);
        Files.write(new File(worksDir(c), key + ".json").toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
    }

    static JSONObject meta(Context c, String key) throws Exception {
        File f = new File(worksDir(c), key + ".json");
        if (Guide.is(key) && !f.isFile())
            return new JSONObject().put("title", Guide.TITLE).put("author", "たてがき帖").put("card", "").put("zip", "");
        File old = new File(c.getFilesDir(), "episodes/" + key + "/meta.json");
        if (!f.isFile() && old.isFile()) old.renameTo(f);           // where it was kept before
        return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    static File sectionsFile(Context c, String key) {
        return new File(c.getFilesDir(), "episodes/" + key + "/sections.json");
    }

    /** The ruby text of the work: its zip fetched once, the .txt in it read as Shift_JIS (MS932). */
    static String text(Context c, String key) throws Exception {
        if (Guide.is(key)) return Guide.text(c);                       // bundled: nothing to fetch
        String zipUrl = meta(c, key).getString("zip");
        URL u = new URL(zipUrl);
        if (!"https".equals(u.getProtocol()) || !"www.aozora.gr.jp".equals(u.getHost()))
            throw new IllegalArgumentException("not a 青空文庫 file: " + zipUrl);
        byte[] zip = get(zipUrl);
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip), Charset.forName("MS932"))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                z.transferTo(b);
                return new String(b.toByteArray(), Charset.forName("MS932"));
            }
        }
        throw new IOException("zip has no text");
    }

    static List<String> loadSections(Context c, String key) throws Exception {
        JSONArray a = new JSONArray(new String(Files.readAllBytes(sectionsFile(c, key).toPath()), StandardCharsets.UTF_8));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    static void saveSections(Context c, String key, List<String> sections) throws Exception {
        JSONArray a = new JSONArray();
        for (String s : sections) a.put(s);
        File f = sectionsFile(c, key);
        f.getParentFile().mkdirs();
        // Whole or not at all: a file cut short would fail the work on every open.
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** 日本十進分類 to a shelf name, by its first digits (K is 児童書). */
    static String category(String ndc) {
        if (ndc == null || ndc.isEmpty()) return "その他";
        String n = ndc.split(" ")[0];
        if (n.startsWith("K")) return "児童書";
        if (n.startsWith("913")) return "小説・物語";
        if (n.startsWith("914")) return "随筆・評論";
        if (n.startsWith("911")) return "詩歌";
        if (n.startsWith("912")) return "戯曲";
        if (n.startsWith("91")) return "日本文学（その他）";
        if (n.startsWith("9")) return "海外文学（翻訳）";
        if (n.startsWith("1")) return "哲学・宗教";
        if (n.startsWith("2")) return "歴史・伝記";
        if (n.startsWith("3")) return "社会";
        if (n.startsWith("4")) return "自然科学";
        return "その他";
    }

    static final String[] CATEGORIES = {"小説・物語", "随筆・評論", "詩歌", "戯曲", "児童書", "日本文学（その他）",
            "海外文学（翻訳）", "哲学・宗教", "歴史・伝記", "社会", "自然科学", "その他"};
}
