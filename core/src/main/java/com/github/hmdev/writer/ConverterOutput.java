package com.github.hmdev.writer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;

/**
 * aozora-reader-android: what AozoraEpub3Converter asks of the writer it fills.
 *
 * The converter took the concrete Epub3Writer, which reaches AWT and Swing (images, the progress
 * bar) that Android does not have. These are the only calls it makes; Epub3Writer implements them
 * as before, and the reader implements them to receive XHTML section by section.
 */
public interface ConverterOutput {
	String getGaijiFontPath();
	String getImageFilePath(String srcImageFileName, int lineNum) throws IOException;
	int getImageIndex();
	boolean isCoverImage();
	int getImagePageType(String srcFilePath, int tagLevel, int lineNum, boolean hasCaption);
	double getImageWidthRatio(String srcFilePath, boolean hasCaption);
	void addGaijiFont(String className, File gaijiFile);
	/** A page break: the text written to {@code bw} so far ends the current section. */
	void nextSection(BufferedWriter bw, int lineNum, int pageType, int imagePageType, String srcImageFilePath) throws IOException;
	void addChapter(String chapterId, String name, int chapterLevel);
	/** Lines converted so far (Epub3Writer moves its progress bar). */
	default void progress(int lineNum) {}
}
