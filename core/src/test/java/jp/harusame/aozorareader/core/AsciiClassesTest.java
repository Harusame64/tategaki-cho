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
    /**
     * Anywhere in the source, not only inside a quoted literal on one line: a text block, or the escape split over two
     * literals joined with + ("\\\\" + "d"), builds the same pattern (codex on #21).
     */
    static List<String> found(Path root, String classes) throws Exception {
        String posix = "p\\{(?:Lower|Upper|ASCII|Alpha|Digit|Alnum|Punct|Graph|Print|Blank|Cntrl|XDigit|Space)\\}";
        Pattern bad = Pattern.compile("\\\\\\\\(?:[" + classes + "]|" + posix + ")|\\\\\\\\\"\\s*\\+\\s*\"(?:[" + classes + "]|" + posix + ")");
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(p);
                Matcher m = bad.matcher(text);
                while (m.find()) {
                    int line = 1 + (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                    out.add(root.relativize(p) + ":" + line + " " + m.group());
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

    /** The scan sees a class where it is written: in a literal, a class, a text block, or split over two literals. */
    @Test
    void theScanSeesThem() throws Exception {
        Path dir = Files.createTempDirectory("ascii");
        Files.writeString(dir.resolve("A.java"), "class A { String a = \"[\\\\d,]+\", b = \"x\\\\.\\\\w\", c = \"\\\\p{Alpha}\", d = \"[0-9]\";\n"
                + "  String e = \"\"\"\n    \\\\s+\n    \"\"\";\n  String f = \"\\\\\" + \"d+\"; }\n");
        List<String> f = found(dir, "dDsSwWbB");
        assertEquals(5, f.size(), f.toString());                                   // [0-9] is not one
        assertTrue(f.get(0).contains("d") && f.get(1).contains("w") && f.get(2).contains("Alpha") && f.get(3).startsWith("A.java:3 ")
                && f.get(4).startsWith("A.java:5 "), f.toString());
    }
}
