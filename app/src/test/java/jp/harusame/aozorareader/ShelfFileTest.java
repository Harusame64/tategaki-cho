package jp.harusame.aozorareader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** shelf.json survives a save cut short and a file that does not parse; writers do not save over each other. */
class ShelfFileTest {
    static Shelf.Work work(String key, int last) {
        Shelf.Work w = new Shelf.Work();
        w.ncode = key;
        w.title = key;
        w.lastEpisode = last;
        return w;
    }

    @Test
    void aMissingFileWithACopyIsReadFromTheCopy(@TempDir File dir) throws Exception {
        Shelf.update(dir, s -> s.works.add(work("n0001aa", 3)));
        Shelf.update(dir, s -> s.works.add(work("n0002bb", 1)));       // .bak now holds the first shelf
        new File(dir, "shelf.json").delete();                          // as if the app stopped mid-save
        Shelf s = Shelf.load(dir);
        assertEquals(1, s.works.size(), "the copy before the last save");
        assertTrue(new File(dir, "shelf.json").isFile(), "and it is put back");
        Shelf.update(dir, x -> x.works.add(work("n0003cc", 1)));
        assertEquals(2, Shelf.load(dir).works.size(), "the next save builds on it, not on nothing");
    }

    @Test
    void aFileThatDoesNotParseIsSetAsideAndTheCopyRead(@TempDir File dir) throws Exception {
        Shelf.update(dir, s -> s.works.add(work("n0001aa", 3)));
        Shelf.update(dir, s -> s.works.get(0).lastEpisode = 4);
        Files.write(new File(dir, "shelf.json").toPath(), "{\"works\":[{\"nco".getBytes(StandardCharsets.UTF_8));
        Shelf s = Shelf.load(dir);
        assertEquals(1, s.works.size());
        assertEquals(3, s.works.get(0).lastEpisode);
        assertTrue(new File(dir, "shelf.json.bad").isFile());
        assertEquals(1, Shelf.load(dir).works.size(), "a second load reads the restored file");
    }

    @Test
    void aNewShelfIsEmpty(@TempDir File dir) throws Exception {
        assertEquals(0, Shelf.load(dir).works.size());
    }

    @Test
    void twoWritersBothLand(@TempDir File dir) throws Exception {
        Shelf.update(dir, s -> s.works.add(work("n0001aa", 1)));
        // A check that read the shelf earlier merges into the latest one instead of saving its old copy.
        Shelf early = Shelf.load(dir);
        Shelf.update(dir, s -> s.works.add(work("n0002bb", 1)));
        Shelf.update(dir, s -> s.find("n0001aa").lastEpisode = 9);
        Shelf.update(dir, s -> { Shelf.Work w = s.find(early.works.get(0).ncode); w.title = "新しい題"; });
        Shelf after = Shelf.load(dir);
        assertEquals(2, after.works.size());
        assertEquals(9, after.find("n0001aa").lastEpisode);
        assertEquals("新しい題", after.find("n0001aa").title);
    }

    @Test
    void anInterruptedReaderDoesNotJudgeTheFile(@TempDir File dir) throws Exception {
        // A screen closing its worker interrupted it mid-read: a channel read threw, the good file was set aside
        // and an empty shelf saved over it.
        Shelf.update(dir, s -> s.works.add(work("n0001aa", 3)));
        Thread.currentThread().interrupt();
        Shelf s;
        try {
            s = Shelf.load(dir);
            Shelf.update(dir, x -> x.find("n0001aa").lastPage = 7);
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, s.works.size());
        assertTrue(!new File(dir, "shelf.json.bad").exists(), "not set aside");
        assertEquals(7, Shelf.load(dir).find("n0001aa").lastPage);
    }

    @Test
    void anUnreadShelfIsNeverSaved(@TempDir File dir) throws Exception {
        Shelf.update(dir, s -> s.works.add(work("n0001aa", 3)));
        Shelf s = Shelf.load(dir);
        s.unread = true;                                                 // as when the file could not be read
        s.works.clear();
        s.save();
        assertEquals(1, Shelf.load(dir).works.size());
    }

    @Test
    void writersOnManyThreadsAllLand(@TempDir File dir) throws Exception {
        int n = 8;
        Thread[] ts = new Thread[n];
        java.util.List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < n; i++) {
            String key = String.format("n%04daa", i);
            ts[i] = new Thread(() -> {
                try {
                    Shelf.update(dir, s -> s.works.add(work(key, 1)));
                    for (int k = 0; k < 20; k++) Shelf.update(dir, s -> s.find(key).lastPage++);
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        assertTrue(errors.isEmpty(), errors.toString());
        Shelf after = Shelf.load(dir);
        assertEquals(n, after.works.size());
        for (Shelf.Work w : after.works) assertEquals(20, w.lastPage, w.ncode);
    }

    @Test
    void aShelfThatCannotBeReadIsMarkedAndNeitherSavedNorPruned(@TempDir File dir) throws Exception {
        // A shelf.json that cannot be read (here: no read permission) is an I/O error, not a corrupt file.
        Shelf.update(dir, x -> x.works.add(work("n0001aa", 3)));
        File f = new File(dir, "shelf.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(f.setReadable(false) && !f.canRead(), "the test runs as a user permissions bind");
        new File(dir, "episodes/n0001aa").mkdirs();
        new File(dir, "episodes/n0001aa/1.html").createNewFile();
        Shelf s = Shelf.load(dir);
        assertTrue(s.unread);
        assertTrue(!new File(dir, "shelf.json.bad").exists(), "not judged corrupt");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> Shelf.update(dir, x -> x.works.clear()));
        assertEquals(0, s.prune(null, "n0001aa", true), "nothing deleted without knowing the shelf");
        assertTrue(new File(dir, "episodes/n0001aa/1.html").isFile());
        f.setReadable(true);
        assertEquals(1, Shelf.load(dir).works.size(), "the file was left as it was");
    }
}
