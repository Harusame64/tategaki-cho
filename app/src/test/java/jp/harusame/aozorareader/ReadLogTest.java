package jp.harusame.aozorareader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The read log: episodes and pages per day and work, and a month's days for the calendar. */
class ReadLogTest {
    @Test
    void aDayKeepsTheEpisodesAndPages(@TempDir File dir) {
        LocalDate d = LocalDate.of(2026, 10, 25);
        ReadLog.record(dir, d, "n9999zx", "試験作品", 270, 0);
        ReadLog.record(dir, d, "n9999zx", "試験作品", 270, 1);
        ReadLog.record(dir, d, "n9999zx", "試験作品", 275, 1);
        ReadLog.record(dir, d, "aozora-000773", "こころ", 1, 3);
        List<ReadLog.Entry> day = ReadLog.day(dir, d);
        assertEquals(2, day.size());
        assertEquals("こころ", day.get(0).title, "most pages first");
        ReadLog.Entry m = day.get(1);
        assertEquals(270, m.first);
        assertEquals(275, m.last);
        assertEquals(2, m.pages);
    }

    @Test
    void aMonthHasOnlyItsOwnDays(@TempDir File dir) {
        ReadLog.record(dir, LocalDate.of(2026, 10, 6), "n9999zx", "試験作品", 1, 4);
        ReadLog.record(dir, LocalDate.of(2026, 10, 8), "n9999zx", "試験作品", 2, 0);
        ReadLog.record(dir, LocalDate.of(2026, 11, 1), "n9999zx", "試験作品", 3, 9);
        Map<Integer, Integer> oct = ReadLog.month(dir, YearMonth.of(2026, 10));
        assertEquals(2, oct.size());
        assertEquals(4, oct.get(6));
        assertEquals(1, oct.get(8), "an episode opened counts, even with no page turned");
        assertTrue(ReadLog.day(dir, LocalDate.of(2026, 9, 1)).isEmpty());
    }

    @Test
    void aLogThatDoesNotParseIsSetAsideNotWrittenOver(@TempDir File dir) throws Exception {
        byte[] cut = "{\"2026-10-01\":{\"n1\":{\"pa".getBytes();
        java.nio.file.Files.write(new File(dir, "readlog.json").toPath(), cut);
        ReadLog.record(dir, LocalDate.of(2026, 10, 25), "n9999zx", "試験作品", 1, 1);
        File[] bad = dir.listFiles((d, n) -> n.startsWith("readlog.json.bad-"));
        assertEquals(1, bad.length, "the old log kept aside for a person to look at");
        org.junit.jupiter.api.Assertions.assertArrayEquals(cut, java.nio.file.Files.readAllBytes(bad[0].toPath()), "as it was");
        assertEquals(1, ReadLog.day(dir, LocalDate.of(2026, 10, 25)).size(), "the new log goes on");
        // a second bad log does not replace the first
        java.nio.file.Files.write(new File(dir, "readlog.json").toPath(), "{".getBytes());
        Thread.sleep(5);
        ReadLog.record(dir, LocalDate.of(2026, 10, 26), "n9999zx", "試験作品", 1, 1);
        assertEquals(2, dir.listFiles((d, n) -> n.startsWith("readlog.json.bad-")).length);
    }
}
