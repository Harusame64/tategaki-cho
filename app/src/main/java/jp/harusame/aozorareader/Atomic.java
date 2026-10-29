package jp.harusame.aozorareader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * The one way the app writes a file: whole or not at all. The bytes go to a file of their own beside it, are synced,
 * and the file is renamed over the old one in one step, so a kill or a power loss mid-write leaves the old file, never
 * one cut short (a cut kk.json lost カクヨム offline and its remap; review on 0.1.6). AtomicWritesTest, in core's
 * tests, keeps every other writer out of the app.
 */
final class Atomic {
    private Atomic() { }

    static void write(File f, byte[] bytes) throws IOException {
        File dir = f.getAbsoluteFile().getParentFile();
        dir.mkdirs();
        // A name of its own: two writers of the same file (the reader and the new-episode check) never share one.
        File tmp = File.createTempFile(f.getName() + ".", ".tmp", dir);
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }
}
