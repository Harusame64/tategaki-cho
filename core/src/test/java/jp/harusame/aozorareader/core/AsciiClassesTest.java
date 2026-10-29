package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Regex classes whose meaning changes on the phone. A JVM, where these tests run, reads \d, \s, \w (and \b, the POSIX
 * classes) as ASCII; Android's ICU reads them as Unicode, so \d also takes full-width digits and \w kanji and kana
 * (#11, internal #5). A pattern with them passes here and does something else on the phone, so core writes the ranges
 * out ([0-9], [a-zA-Z0-9_], [ \t\n\x0B\f\r]); the app writes digits out, and may keep \s where it means any space.
 */
class AsciiClassesTest {
    static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    static List<String> found(Path root, String classes) throws Exception {
        Pattern bad = Pattern.compile("\\\\\\\\(?:[" + classes + "]|p\\{(?:Lower|Upper|ASCII|Alpha|Digit|Alnum|Punct|Graph|Print|Blank|Cntrl|XDigit|Space)\\})");
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = LITERAL.matcher(lines.get(i));
                    while (m.find()) if (bad.matcher(m.group(1)).find()) out.add(root.relativize(p) + ":" + (i + 1) + " " + m.group());
                }
            }
        }
        return out;
    }

    @Test
    void coreWritesItsClassesOut() throws Exception {
        assertEquals(List.of(), found(Path.of("src/main/java"), "dDsSwWbB"));
    }

    @Test
    void theAppWritesItsDigitsOut() throws Exception {
        assertEquals(List.of(), found(Path.of("../app/src/main/java"), "dDwWbB"));
    }

    /** The scan sees a class where it is written, inside a character class and next to other escapes. */
    @Test
    void theScanSeesThem() throws Exception {
        Path dir = Files.createTempDirectory("ascii");
        Files.writeString(dir.resolve("A.java"), "class A { String a = \"[\\\\d,]+\", b = \"x\\\\.\\\\w\", c = \"\\\\p{Alpha}\", d = \"[0-9]\"; }\n");
        List<String> f = found(dir, "dDsSwWbB");
        assertEquals(3, f.size(), f.toString());                                   // [0-9] is not one
        assertTrue(f.get(0).contains("\\\\d") && f.get(1).contains("\\\\w") && f.get(2).contains("Alpha"), f.toString());
    }
}
