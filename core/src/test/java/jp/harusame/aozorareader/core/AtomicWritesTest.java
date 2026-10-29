package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The app writes files through Atomic only (whole or not at all). A file written in place and cut short by a kill or a
 * power loss broke what it held: カクヨム offline and its remap (kk.json), a 青空文庫 work (works/<key>.json), the
 * episode slider (toc.json); review on 0.1.6. Every other way to write is kept out of the app's sources.
 */
class AtomicWritesTest {
    static final Pattern WRITER = Pattern.compile("Files\\.(?:write|writeString|newOutputStream|newBufferedWriter|copy)\\(|"
            + "new (?:java\\.io\\.)?(?:FileOutputStream|FileWriter|PrintWriter|RandomAccessFile)\\(|openFileOutput\\(|\\.renameTo\\(");

    @Test
    void theAppWritesThroughAtomicOnly() throws Exception {
        List<String> out = new ArrayList<>();
        Path root = Path.of("../app/src/main/java");
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java") && !f.endsWith("Atomic.java"))::iterator) {
                String text = Files.readString(p);
                Matcher m = WRITER.matcher(text);
                while (m.find()) {
                    String line = text.substring(text.lastIndexOf('\n', m.start()) + 1, text.indexOf('\n', m.start()));
                    // Moving a broken file aside (.bad) or an old one to its new name loses nothing if it is cut short.
                    if (line.contains(".bad") || line.contains("old.renameTo(f)")) continue;
                    out.add(root.relativize(p) + ": " + line.trim());
                }
            }
        }
        assertEquals(List.of(), out);
    }
}
