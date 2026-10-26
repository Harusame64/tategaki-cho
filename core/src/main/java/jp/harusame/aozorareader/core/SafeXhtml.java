package jp.harusame.aozorareader.core;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * The formatter's output, cut down to what a vertical page needs before it reaches the reader's WebView.
 * Episode text is written by anyone: an author's literal "&lt;a onclick=…&gt;" comes out of the aozora
 * conversion as a real tag, and the page has script and a bridge to the app. Only text structure, ruby and
 * the formatter's classes are kept; ids, links, images, styles and every other attribute go.
 */
public final class SafeXhtml {
	private SafeXhtml() { }

	private static final Safelist ALLOWED = new Safelist()
			.addTags("div", "span", "p", "br", "ruby", "rb", "rt", "rp", "b", "i", "em", "strong", "sub", "sup",
					"hr", "ul", "ol", "li", "pre", "h1", "h2", "h3", "h4", "h5", "h6")
			// class only: an author's id="toc" would take the place of the page's own #toc, #info …
			.addAttributes(":all", "class");

	public static String clean(String fragment) {
		Document.OutputSettings out = new Document.OutputSettings().prettyPrint(false);
		return Jsoup.clean(fragment, "", ALLOWED, out);
	}
}
