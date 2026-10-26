package jp.harusame.aozorareader.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * One page of a work's 目次 (なろう shows 100 episodes a page): its chapters and episodes, and how many
 * pages the 目次 has, so the reader can jump to an episode by its title.
 */
public final class Toc {
	public static final class Entry {
		/** the chapter heading this entry opens, or null when it continues the one before */
		public final String chapter;
		public final int no;
		public final String title;

		Entry(String chapter, int no, String title) {
			this.chapter = chapter;
			this.no = no;
			this.title = title;
		}
	}

	public final List<Entry> entries;
	/** pages of the 目次 (1 when it has no pager) */
	public final int pages;

	private Toc(List<Entry> entries, int pages) {
		this.entries = Collections.unmodifiableList(entries);
		this.pages = pages;
	}

	private static final Pattern EPISODE = Pattern.compile("/n\\w+/(\\d+)/?$");
	private static final Pattern PAGE = Pattern.compile("[?&]p=(\\d+)");

	public static Toc fromNarou(String html, String url) {
		Document doc = Jsoup.parse(html, url);
		List<Entry> out = new ArrayList<>();
		Element list = doc.selectFirst(".p-eplist");
		if (list != null) {
			String chapter = null;
			for (Element e : list.children()) {
				if (e.hasClass("p-eplist__chapter-title")) {
					chapter = e.text().trim();
				} else if (e.hasClass("p-eplist__sublist")) {
					Element a = e.selectFirst("a.p-eplist__subtitle[href]");
					if (a == null) continue;
					Matcher m = EPISODE.matcher(a.attr("href"));
					if (!m.find()) continue;
					out.add(new Entry(chapter, Integer.parseInt(m.group(1)), a.text().trim()));
					chapter = null;
				}
			}
		}
		int pages = 1;
		for (Element a : doc.select(".c-pager a[href]")) {
			Matcher m = PAGE.matcher(a.attr("href"));
			if (m.find()) pages = Math.max(pages, Integer.parseInt(m.group(1)));
		}
		return new Toc(out, pages);
	}
}
