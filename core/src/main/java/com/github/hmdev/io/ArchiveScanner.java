package com.github.hmdev.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;



/**
 * Scans archive files once and extracts text/image entry information.
 */
final class ArchiveScanner {
	private ArchiveScanner() {
	}
	
	/**
	 * zip の中のテキストと画像を読む。テキストが 1 つ壊れていても、その 1 つだけを飛ばして残りを読む（internal #36。読み方は {@link ZipEntries}）
	 * @param skippedTexts 読めずに飛ばしたテキストの「名前 (理由)」を足す
	 */
	static void scanZip(Path zipPath, List<ArchiveCache.TextEntry> textEntries, List<String> imageEntries, List<String> skippedTexts) throws IOException {
		ZipEntries.forEach(zipPath, false, (entryName, opener) -> {
			if (entryName.endsWith("/")) return true;
			String entryExt = entryName.substring(entryName.lastIndexOf('.') + 1).toLowerCase();
			if ("txt".equalsIgnoreCase(entryExt)) {
				byte[] content;
				try (InputStream is = opener.open()) {
					content = ArchiveCache.readFully(is);
				} catch (IOException | RuntimeException e) {
					skipText(skippedTexts, entryName, e);
					return true;
				}
				textEntries.add(new ArchiveCache.TextEntry(entryName, content));
			} else if (isImageExtension(entryExt)) {
				imageEntries.add(entryName);
			}
			return true;
		});
	}

	/** 読めないテキストの名前と理由を控える（ログに出すのは ArchiveCache。画像の一覧を作るだけの読み方では出さない） */
	private static void skipText(List<String> skippedTexts, String entryName, Exception e) {
		if (skippedTexts != null) skippedTexts.add(entryName + " (" + e + ")");
	}

	private static boolean isImageExtension(String ext) {
		return "jpg".equalsIgnoreCase(ext) || "jpeg".equalsIgnoreCase(ext) || 
		       "png".equalsIgnoreCase(ext) || "gif".equalsIgnoreCase(ext) ||
		       "bmp".equalsIgnoreCase(ext);
	}
}
