package jp.harusame.aozorareader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one way the app writes a file: whole or not at all. The bytes go to <name>.tmp beside it, are synced, and the
 * file is renamed over the old one in one step, so a kill or a power loss mid-write leaves the old file, never one cut
 * short (a cut kk.json lost カクヨム offline and its remap; review on 0.1.6). AtomicWritesTest, in core's tests, keeps
 * every other writer out of the app.
 */
final class Atomic {
    private Atomic() { }

    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    static void write(File f, byte[] bytes) throws IOException {
        File target = f.getAbsoluteFile();
        target.getParentFile().mkdirs();
        // One writer of a file at a time, so two (the reader and the new-episode check) never share its .tmp; the name
        // stays the same, so a write cut short leaves one .tmp that the next write replaces (codex on #23: a name of
        // its own each time left one behind per kill).
        synchronized (LOCKS.computeIfAbsent(target.getPath(), k -> new Object())) {
            File tmp = new File(target.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
