package jp.harusame.aozorareader.core;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import com.github.hmdev.writer.ConverterOutput;

/**
 * Receives AozoraEpub3Converter's output as XHTML body fragments, one per section (page break),
 * instead of an EPUB. Images and gaiji fonts are not handled yet: the reader shows text only.
 */
final class XhtmlSections implements ConverterOutput {
	private final StringWriter buffer;
	private final List<String> sections = new ArrayList<>();
	private final List<String> chapters = new ArrayList<>();
	private final String gaijiFontPath;
	private int mark = 0;
	private boolean started = false;

	/** @param gaijiFontPath a folder for gaiji fonts, ending with '/'; it need not exist (the reader has none).
	 *  Not "": the converter lists the folder, and Path.of("") is the working directory ("/" on Android, unreadable). */
	XhtmlSections(StringWriter buffer, String gaijiFontPath) {
		this.buffer = buffer;
		this.gaijiFontPath = gaijiFontPath;
	}

	@Override public void nextSection(BufferedWriter bw, int lineNum, int pageType, int imagePageType, String srcImageFilePath) throws IOException {
		bw.flush();
		if (started) cut();
		started = true;
		mark = buffer.getBuffer().length();
	}

	/** The text after the last page break ends the last section. */
	List<String> finish(BufferedWriter bw) throws IOException {
		bw.flush();
		cut();
		return sections;
	}

	private void cut() {
		String body = buffer.getBuffer().substring(mark);
		if (!body.isBlank()) sections.add(body);
		mark = buffer.getBuffer().length();
	}

	List<String> chapters() { return chapters; }

	@Override public void addChapter(String chapterId, String name, int chapterLevel) { if (name != null) chapters.add(name); }
	@Override public String getGaijiFontPath() { return gaijiFontPath; }
	@Override public String getImageFilePath(String srcImageFileName, int lineNum) { return null; }
	@Override public int getImageIndex() { return -1; }
	@Override public boolean isCoverImage() { return false; }
	@Override public int getImagePageType(String srcFilePath, int tagLevel, int lineNum, boolean hasCaption) { return 0; }
	@Override public double getImageWidthRatio(String srcFilePath, boolean hasCaption) { return 0; }
	@Override public void addGaijiFont(String className, File gaijiFile) {}
}
