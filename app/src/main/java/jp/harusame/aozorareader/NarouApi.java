package jp.harusame.aozorareader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Searching works with the official なろう小説API (なろうデベロッパー): one request per search or per page of
 * results, asking only for the fields the list shows. Episode text never comes from here.
 */
final class NarouApi {
    static final int PAGE = 20;

    static final class Novel {
        String ncode, title, writer, story;
        int genre, episodes, globalPoint;
        long length;
        boolean shortStory, finished;
    }

    static final class Result {
        int all;
        final List<Novel> novels = new ArrayList<>();
    }

    /** @param q the API's own parameters (word, order, type, genre, length, stop, …); st is 1-based */
    static Result search(Map<String, String> q, int st) throws Exception {
        Map<String, String> p = new LinkedHashMap<>(q);
        p.put("out", "json");
        p.put("of", "t-n-w-s-g-nt-e-ga-l-gp");
        p.put("lim", String.valueOf(PAGE));
        p.put("st", String.valueOf(st));
        StringBuilder u = new StringBuilder("https://api.syosetu.com/novelapi/api/?");
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            u.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8")).append('&');
        }
        JSONArray a = new JSONArray(new String(Net.get(u.toString()), StandardCharsets.UTF_8));
        Result r = new Result();
        // The first element is {"allcount": n}; works follow.
        r.all = a.length() > 0 ? a.getJSONObject(0).optInt("allcount") : 0;
        for (int i = 1; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Novel n = new Novel();
            n.ncode = o.optString("ncode").toLowerCase(java.util.Locale.ROOT);
            n.title = o.optString("title");
            n.writer = o.optString("writer");
            n.story = o.optString("story");
            n.genre = o.optInt("genre");
            n.shortStory = o.optInt("noveltype") == 2;
            n.finished = o.optInt("end", 1) == 0;
            n.episodes = n.shortStory ? 1 : o.optInt("general_all_no");
            n.length = o.optLong("length");
            n.globalPoint = o.optInt("global_point");
            r.novels.add(n);
        }
        return r;
    }

    static String genreName(int g) {
        switch (g) {
            case 101: return "異世界〔恋愛〕";
            case 102: return "現実世界〔恋愛〕";
            case 201: return "ハイファンタジー";
            case 202: return "ローファンタジー";
            case 301: return "純文学";
            case 302: return "ヒューマンドラマ";
            case 303: return "歴史";
            case 304: return "推理";
            case 305: return "ホラー";
            case 306: return "アクション";
            case 307: return "コメディー";
            case 401: return "VRゲーム";
            case 402: return "宇宙";
            case 403: return "空想科学";
            case 404: return "パニック";
            case 9901: return "童話";
            case 9902: return "詩";
            case 9903: return "エッセイ";
            case 9904: return "リプレイ";
            case 9801: return "ノンジャンル";
            default: return "その他";
        }
    }
}
