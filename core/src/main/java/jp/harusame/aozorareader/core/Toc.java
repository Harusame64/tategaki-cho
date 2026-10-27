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
	/** a chapter heading no episode of this page took (after its last one, or carried over a page with none),
	 * which opens the first episode of the next page; or null */
	public final String trailingChapter;

	private Toc(List<Entry> entries, int pages, String trailingChapter) {
		this.entries = Collections.unmodifiableList(entries);
		this.pages = pages;
		this.trailingChapter = trailingChapter;
	}

	private static final Pattern EPISODE = Pattern.compile("/n\\w+/(\\d+)/?$");
	private static final Pattern PAGE = Pattern.compile("[?&]p=(\\d+)");

	public static Toc fromNarou(String html, String url) {
		return fromNarou(html, url, null);
	}

	/**
	 * @param carry the page before's {@link #trailingChapter}: a heading that opens this page's first episode (unless
	 *     the page has a heading of its own before it), and is handed on again by a page with no episode
	 */
	public static Toc fromNarou(String html, String url, String carry) {
		Document doc = Jsoup.parse(html, url);
		List<Entry> out = new ArrayList<>();
		Element list = doc.selectFirst(".p-eplist");
		String chapter = carry;
		if (list != null) {
			for (Element e : list.children()) {
				if (e.hasClass("p-eplist__chapter-title")) {
					// A heading with no text is not one: "" would end the chapter before it in the 目次.
					String t = e.text().trim();
					if (!t.isEmpty()) chapter = t;
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
		return new Toc(out, pages, chapter);
	}
}
