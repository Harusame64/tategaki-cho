package jp.harusame.aozorareader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Formatting on several threads at once (read-ahead beside an episode being opened) gives what one at a time gives.
 * It holds without a lock because each call gets its own converter (the web converter shares only its site
 * definition, never changed after it is read); this test catches state that comes to be shared.
 */
class ConcurrentFormatTest {
    static final String URL = "https://ncode.syosetu.com/n0000aa/1/";

    @Test
    void twoThreadsGetTheirOwnText() throws Exception {
        String base = Files.readString(Path.of("src/test/resources/narou-episode-sample.html"));
        String a = base, b = base.replace("電波の届かない駅", "Englishの駅 ABC 123").replace("７時", "8時");
        File data = new File("src/main/resources");
        String wantA = String.join("", new EpisodeFormatter(data).toXhtmlSections(a, URL));
        String wantB = String.join("", new EpisodeFormatter(data).toXhtmlSections(b, URL));
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<String> bad = Collections.synchronizedList(new ArrayList<>());
        Thread[] ts = new Thread[4];
        for (int t = 0; t < ts.length; t++) {
            boolean useA = t % 2 == 0;
            ts[t] = new Thread(() -> {
                try {
                    for (int i = 0; i < 25; i++) {
                        String got = String.join("", new EpisodeFormatter(data).toXhtmlSections(useA ? a : b, URL));
                        if (!got.equals(useA ? wantA : wantB)) bad.add(got);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(0, bad.size(), bad.isEmpty() ? "" : bad.get(0));
    }
}
