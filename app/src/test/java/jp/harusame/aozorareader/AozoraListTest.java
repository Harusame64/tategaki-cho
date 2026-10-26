package jp.harusame.aozorareader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** 青空文庫's official list: its CSV records, and the shelves made from 日本十進分類. */
class AozoraListTest {
    @Test
    void csvRecords() throws Exception {
        BufferedReader r = new BufferedReader(new StringReader(
                "\uFEFF作品ID,作品名\r\n\"000773\",\"こころ\"\r\n\"1\",\"a,b \"\"c\"\"\nd\"\n2,last"));
        assertEquals(Arrays.asList("\uFEFF作品ID", "作品名"), AozoraSite.csvRow(r));
        assertEquals(Arrays.asList("000773", "こころ"), AozoraSite.csvRow(r));
        assertEquals(Arrays.asList("1", "a,b \"c\"\nd"), AozoraSite.csvRow(r), "a quoted comma, doubled quotes and a line break");
        assertEquals(Arrays.asList("2", "last"), AozoraSite.csvRow(r), "the last record without a line break");
        assertNull(AozoraSite.csvRow(r));
    }

    @Test
    void shelvesFromTheClassification() {
        assertEquals("小説・物語", AozoraSite.category("913"));
        assertEquals("随筆・評論", AozoraSite.category("914"));
        assertEquals("児童書", AozoraSite.category("K913"));
        assertEquals("海外文学（翻訳）", AozoraSite.category("933"));
        assertEquals("その他", AozoraSite.category(""));
    }
}
