package jp.harusame.aozorareader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The reader page's slots are filled in one pass: values are never read again as slots or as regex text. */
class PageTemplateTest {
    @Test
    void aTitleHoldingASlotNameStaysAsWritten() {
        String t = "var INFO=%%INFO%%, START=%%START%%, SHARE=%%SHARE%%, PREFS=%%PREFS%%, AA=%%AA%%;<div>%%BOOK%%</div>";
        String out = ReaderActivity.fill(t, "{\"workTitle\":\"%%BOOK%%と$1\"}", "3", "-1.0", "null", "\"look\"", "<p class=\"ep\">本文 \\ $0</p>");
        assertEquals("var INFO={\"workTitle\":\"%%BOOK%%と$1\"}, START=3, SHARE=-1.0, PREFS=null, AA=\"look\";<div><p class=\"ep\">本文 \\ $0</p></div>", out);
    }
}
