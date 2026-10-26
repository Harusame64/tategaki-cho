package jp.harusame.aozorareader.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Properties;

import com.github.hmdev.converter.AozoraEpub3Converter;
import com.github.hmdev.info.BookInfo;
import com.github.hmdev.pipeline.ConversionSettings;

/** Aozora-format text to vertical XHTML body fragments, one per section, with aozora's own formatter. */
public final class AozoraFormatter {
	private final String dataDir;
	private final int removeEmptyLines;
	private final boolean forceIndent;

	/** @param dataDir where the chuki tables are, ending with '/'; tables missing there are read from the classpath */
	public AozoraFormatter(String dataDir) {
		this(dataDir, 0);
	}

	/**
	 * @param removeEmptyLines blank lines taken off each run of them (aozora's 空行除去): 1 drops the single blank
	 *        line web novels put between paragraphs and leaves one of two
	 */
	public AozoraFormatter(String dataDir, int removeEmptyLines) {
		this(dataDir, removeEmptyLines, false);
	}

	/**
	 * @param forceIndent a full-width space put before each paragraph that starts without one (aozora's 強制字下げ);
	 *        lines opening with 「『（ and the like, or already indented, are left as they are
	 */
	public AozoraFormatter(String dataDir, int removeEmptyLines, boolean forceIndent) {
		this.dataDir = dataDir;
		this.removeEmptyLines = removeEmptyLines;
		this.forceIndent = forceIndent;
	}

	public List<String> toXhtmlSections(String aozoraText) throws Exception {
		StringWriter buffer = new StringWriter();
		XhtmlSections out = new XhtmlSections(buffer, dataDir + "gaiji/");
		AozoraEpub3Converter converter = new AozoraEpub3Converter(out, dataDir);
		// the CLI's defaults (an empty ini), as AozoraEpub3 sets them before getBookInfo
		ConversionSettings.fromProps(new Properties()).applyTo(converter);
		converter.setRemoveEmptyLine(removeEmptyLines, 0);
		converter.setForceIndent(forceIndent);
		converter.vertical = true;
		BookInfo info = converter.getBookInfo(null, new BufferedReader(new StringReader(aozoraText)), null, BookInfo.TitleType.NONE, false);
		info.vertical = true;
		BufferedWriter bw = new BufferedWriter(buffer);
		converter.convertTextToEpub3(bw, new BufferedReader(new StringReader(aozoraText)), info);
		List<String> sections = out.finish(bw);
		// What the reader shows: structure and ruby only (see SafeXhtml).
		sections.replaceAll(SafeXhtml::clean);
		return sections;
	}
}
