package jp.harusame.aozorareader.core;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.github.hmdev.web.WebAozoraConverter;

/** One episode page's HTML, already fetched, to vertical XHTML sections: aozora's web extraction, then its formatter. */
public final class EpisodeFormatter {
	private final File dataDir;
	private final int removeEmptyLines;
	private final boolean forceIndent;
	private final boolean uprightLetters;

	/** @param dataDir holds the chuki tables and web/&lt;site&gt;/extract.txt */
	public EpisodeFormatter(File dataDir) {
		this(dataDir, 0);
	}

	/** @param removeEmptyLines see {@link AozoraFormatter#AozoraFormatter(String, int)} */
	public EpisodeFormatter(File dataDir, int removeEmptyLines) {
		this(dataDir, removeEmptyLines, false);
	}

	/** @param forceIndent see {@link AozoraFormatter#AozoraFormatter(String, int, boolean)} */
	public EpisodeFormatter(File dataDir, int removeEmptyLines, boolean forceIndent) {
		this(dataDir, removeEmptyLines, forceIndent, false);
	}

	/**
	 * @param uprightLetters every half-width letter full-width (upright in vertical text), English included;
	 *        false keeps narou.rb's rule (an English phrase or a word of 8+ letters stays half-width)
	 */
	public EpisodeFormatter(File dataDir, int removeEmptyLines, boolean forceIndent, boolean uprightLetters) {
		this.dataDir = dataDir;
		this.removeEmptyLines = removeEmptyLines;
		this.forceIndent = forceIndent;
		this.uprightLetters = uprightLetters;
	}

	public List<String> toXhtmlSections(String html, String url) throws Exception {
		WebAozoraConverter conv = WebAozoraConverter.createWebAozoraConverter(url, new File(dataDir, "web"));
		if (conv == null) throw new IOException("no site definition for " + url);
		// 挿絵 are not taken: the converter would fetch them itself, outside the app's network door.
		conv.skipImages = true;
		conv.getFormatSettings().setEnableAlphabetForceZenkaku(uprightLetters);
		Document doc = Jsoup.parse(html, url);
		StringWriter text = new StringWriter();
		conv.episodeToAozoraText(text, doc);
		return new AozoraFormatter(dataDir.getAbsolutePath() + "/", removeEmptyLines, forceIndent).toXhtmlSections(text.toString());
	}
}
