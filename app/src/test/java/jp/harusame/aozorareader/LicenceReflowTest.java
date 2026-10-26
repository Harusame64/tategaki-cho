package jp.harusame.aozorareader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The licence screen joins a terminal-wrapped paragraph into one line, and leaves everything else as it is. */
class LicenceReflowTest {
    @Test
    void aWrappedParagraphBecomesOneLine() {
        String gpl = "  The GNU General Public License is a free, copyleft license for\n"
                + "software and other kinds of works.\n"
                + "\n"
                + "  The licenses for most software and other practical works are designed\n"
                + "to take away your freedom to share and change the works.";
        assertEquals("  The GNU General Public License is a free, copyleft license for software and other kinds of works.\n"
                + "\n"
                + "  The licenses for most software and other practical works are designed to take away your freedom to share and change the works.",
                LicenceActivity.reflow(gpl));
    }

    @Test
    void titlesListsAndRulesStayOnTheirLines() {
        String notice = "たてがき帖 (tategaki-cho)\n"
                + "Copyright (C) 2026 Harusame64\n"
                + "================================================================================\n"
                + "Licence texts:\n"
                + "  Apache License 2.0 ........ licenses/Apache-2.0.txt\n"
                + "1. Apache Commons\n"
                + "   - commons-lang3 3.20.0         Copyright 2001-2025 The Apache Software Foundation\n"
                + "- a list item that follows a long line of plain text, which must not be joined\n"
                + "- the next item";
        assertEquals("たてがき帖 (tategaki-cho)\n"
                + "Copyright (C) 2026 Harusame64\n"
                + "========\n"
                + "Licence texts:\n"
                + "  Apache License 2.0 ........ licenses/Apache-2.0.txt\n"
                + "1. Apache Commons\n"
                + "   - commons-lang3 3.20.0         Copyright 2001-2025 The Apache Software Foundation\n"
                + "- a list item that follows a long line of plain text, which must not be joined\n"
                + "- the next item",
                LicenceActivity.reflow(notice));
    }
}
