package jp.harusame.aozorareader.core;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * What the reader shows beside the text so the work is always credited: the work, its author, and where
 * it came from. Read from the episode page's announce box and pager.
 */
public final class EpisodeInfo {
	public final String url;
	public final String workTitle;
	public final String workUrl;
	public final String author;
	/** null when the author has no page linked */
	public final String authorUrl;
	/** "1/391" as the page shows it, or null */
	public final String number;
	public final String prevUrl;
	public final String nextUrl;

	private EpisodeInfo(String url, String workTitle, String workUrl, String author, String authorUrl,
			String number, String prevUrl, String nextUrl) {
		this.url = url;
		this.workTitle = workTitle;
		this.workUrl = workUrl;
		this.author = author;
		this.authorUrl = authorUrl;
		this.number = number;
		this.prevUrl = prevUrl;
		this.nextUrl = nextUrl;
	}

	/** For a site whose episode pages do not carry all of this (カクヨム: the work's list does). */
	public static EpisodeInfo of(String url, String workTitle, String workUrl, String author, String authorUrl,
			String number, String prevUrl, String nextUrl) {
		return new EpisodeInfo(url, workTitle, workUrl, author, authorUrl, number, prevUrl, nextUrl);
	}

	/** A なろう episode page's own title (as its 目次 lists it), or null. */
	public static String narouTitle(String html) {
		Element t = Jsoup.parse(html).selectFirst(".p-novel__title, .novel_subtitle");
		return t == null ? null : t.text().trim();
	}

	public static EpisodeInfo fromNarou(String html, String url) {
		Document doc = Jsoup.parse(html, url);
		String workTitle = null, workUrl = null, author = null, authorUrl = null;
		Element box = doc.selectFirst(".c-announce:not(.c-announce--note)");
		if (box != null) {
			for (Element a : box.select("a[href]")) {
				String href = a.absUrl("href");
				if (workUrl == null && href.matches("https://ncode\\.syosetu\\.com/n[a-zA-Z0-9_]+/?")) {
					workUrl = href;
					workTitle = a.text().trim();
				} else if (authorUrl == null && href.startsWith("https://mypage.syosetu.com/")) {
					authorUrl = href;
					author = a.text().trim();
				}
			}
			if (author == null) {
				// An author without a mypage link is the plain text after 作者：, up to the next element: a name
				// with a space in it (山田 太郎) is kept whole.
				for (org.jsoup.nodes.TextNode t : box.textNodes()) {
					String s = t.getWholeText().replace('\u00a0', ' ');
					int i = s.indexOf("作者：");
					if (i < 0) continue;
					String rest = s.substring(i + 3).trim();
					author = rest.isEmpty() ? null : rest;
					break;
				}
			}
		}
		String prev = null, next = null;
		for (Element a : doc.select(".c-pager a[href]")) {
			String label = a.text().trim();
			if (next == null && label.equals("次へ")) next = a.absUrl("href");
			else if (prev == null && label.equals("前へ")) prev = a.absUrl("href");
		}
		Element num = doc.selectFirst(".p-novel__number");
		return new EpisodeInfo(url, workTitle, workUrl, author, authorUrl,
				num == null ? null : num.text().trim(), prev, next);
	}
}
