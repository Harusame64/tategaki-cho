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

    @Test
    void aReadDayIsAlwaysMarkedAndItsDepthIsFixed(@TempDir File dir) {
        // An episode only opened (no page turned) still counts as a day read.
        ReadLog.record(dir, LocalDate.of(2026, 10, 8), "n9999zx", "試験作品", 2, 0);
        int p = ReadLog.month(dir, YearMonth.of(2026, 10)).get(8);
        assertEquals(1, ReadLog.depth(p), "the lightest mark, even as the month's only day");
        assertEquals(1, ReadLog.depth(4));
        assertEquals(2, ReadLog.depth(5));
        assertEquals(3, ReadLog.depth(20));
        assertEquals(4, ReadLog.depth(50));
    }

    @Test
    void aMonthIsSummedUp(@TempDir File dir) {
        ReadLog.record(dir, LocalDate.of(2026, 10, 1), "n9999zx", "試験作品", 3, 4);
        ReadLog.record(dir, LocalDate.of(2026, 10, 1), "n9999zx", "試験作品", 5, 4);   // 3〜5 that day
        ReadLog.record(dir, LocalDate.of(2026, 10, 2), "n9999zx", "試験作品", 5, 2);   // 5 again: counted once
        ReadLog.record(dir, LocalDate.of(2026, 10, 2), "n9999zx", "試験作品", 6, 2);
        ReadLog.record(dir, LocalDate.of(2026, 10, 2), "aozora-000773", "こころ", 4, 30); // sections, not episodes
        ReadLog.record(dir, LocalDate.of(2026, 10, 3), Guide.KEY, "使い方", 1, 2);       // the guide: left out
        ReadLog.record(dir, LocalDate.of(2026, 11, 1), "n9999zx", "試験作品", 7, 1);   // another month
        ReadLog.Summary s = ReadLog.summary(dir, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertEquals(2, s.days);
        assertEquals(2, s.works.size());
        assertEquals(4, s.episodes, "3, 4, 5, 6");
        assertEquals("試験作品", s.works.get(0).title, "on the most days, though こころ had more pages");
        assertEquals(2, s.works.get(0).days);
        assertEquals(4, s.works.get(0).episodes);
        assertEquals(0, s.works.get(1).episodes, "青空文庫's sections are not episodes");
        ReadLog.Summary sept = ReadLog.summary(dir, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertEquals(0, sept.days);
        assertTrue(sept.works.isEmpty());
    }

    @Test
    void theGuideBookIsNotLogged(@TempDir File dir) throws Exception {
        LocalDate d = LocalDate.of(2026, 10, 9);
        ReadLog.record(dir, d, Guide.KEY, "たてがき帖の使い方", 1, 3);
        assertTrue(ReadLog.day(dir, d).isEmpty());
        assertTrue(ReadLog.month(dir, YearMonth.of(2026, 10)).isEmpty(), "no mark for a day with only the guide");
        // A log written before the guide was left out: the guide is still not shown, the work is.
        java.nio.file.Files.write(ReadLog.file(dir).toPath(), (
                "{\"2026-10-10\":{\"" + Guide.KEY + "\":{\"first\":1,\"last\":1,\"pages\":2},\"n9999zx\":{\"t\":\"試験作品\",\"first\":3,\"last\":3,\"pages\":4}},"
                + "\"2026-10-11\":{\"" + Guide.KEY + "\":{\"first\":1,\"last\":1,\"pages\":2}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1, ReadLog.day(dir, LocalDate.of(2026, 10, 10)).size());
        Map<Integer, Integer> oct = ReadLog.month(dir, YearMonth.of(2026, 10));
        assertEquals(4, oct.get(10), "the guide's pages not counted");
        assertTrue(!oct.containsKey(11));
    }
}
