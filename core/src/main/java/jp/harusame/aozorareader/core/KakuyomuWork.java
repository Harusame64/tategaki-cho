package jp.harusame.aozorareader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * A カクヨム work as its page describes it: title, author, whether it is finished, and every public episode in
 * order with its chapter. カクヨム has no public API, so this one page stands in for both the work's details
 * and its 目次. Read from the page's __NEXT_DATA__ (Apollo state).
 */
public final class KakuyomuWork {
	public static final class Episode {
		public final String id;
		public final String title;
		/** the chapter this episode opens; "" when it opens a section with no chapter (the one before ends here);
		 * or null when it continues the one before */
		public final String chapter;

		Episode(String id, String title, String chapter) {
			this.id = id;
			this.title = title;
			this.chapter = chapter;
		}
	}

	public final String id;
	public final String title;
	public final String author;
	/** the author's page, or null */
	public final String authorUrl;
	public final boolean finished;
	public final List<Episode> episodes;

	/** An episode id as カクヨム writes it: digits only. */
	public static final java.util.regex.Pattern EPISODE_ID = java.util.regex.Pattern.compile("[0-9]{1,30}");

	private KakuyomuWork(String id, String title, String author, String authorUrl, boolean finished, List<Episode> episodes) {
		this.id = id;
		this.title = title;
		this.author = author;
		this.authorUrl = authorUrl;
		this.finished = finished;
		this.episodes = Collections.unmodifiableList(episodes);
	}

	/** @return null when the page carries no work */
	public static KakuyomuWork fromPage(String html) {
		Element data = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__");
		if (data == null) return null;
		JSONObject next = new JSONObject(data.data());
		JSONObject props = next.optJSONObject("props");
		JSONObject page = props == null ? null : props.optJSONObject("pageProps");
		JSONObject state = page == null ? null : page.optJSONObject("__APOLLO_STATE__");
		JSONObject query = next.optJSONObject("query");
		String workId = query == null ? null : query.optString("workId", null);
		if (state == null || workId == null) return null;
		JSONObject work = state.optJSONObject("Work:" + workId);
		if (work == null) return null;
		String author = null, authorUrl = null;
		JSONObject user = deref(state, work.optJSONObject("author"));
		if (user != null) {
			author = user.optString("activityName", null);
			String name = user.optString("name", null);
			if (name != null) authorUrl = "https://kakuyomu.jp/users/" + name;
		}
		List<Episode> eps = new ArrayList<>();
		JSONArray toc = work.optJSONArray("tableOfContentsV2");
		if (toc == null) toc = work.optJSONArray("tableOfContents");
		if (toc != null) {
			for (int i = 0; i < toc.length(); i++) {
				JSONObject ch = deref(state, toc.optJSONObject(i));
				if (ch == null) continue;
				JSONObject chapter = deref(state, ch.optJSONObject("chapter"));
				// A section with no chapter still ends the chapter before it: its first episode says so with "".
				String heading = chapter == null ? "" : chapter.optString("title", "");
				JSONArray us = ch.optJSONArray("episodeUnions");
				if (us == null) continue;
				for (int j = 0; j < us.length(); j++) {
					JSONObject e = deref(state, us.optJSONObject(j));
					if (e == null || !"Episode".equals(e.optString("__typename"))) continue;
					// The id becomes part of a file name (k<id>.html): only the digits カクヨム uses are taken, so
					// a page cannot name a path ("../"). The chapter heading goes to the first episode kept.
					String id = e.optString("id");
					if (!EPISODE_ID.matcher(id).matches()) continue;
					eps.add(new Episode(id, e.optString("title"), heading));
					heading = null;
				}
			}
		}
		return new KakuyomuWork(workId, work.optString("title"), author, authorUrl,
				"COMPLETED".equals(work.optString("serialStatus")), eps);
	}

	private static JSONObject deref(JSONObject state, JSONObject ref) {
		if (ref == null) return null;
		String key = ref.optString("__ref", null);
		return key == null ? ref : state.optJSONObject(key);
	}
}
