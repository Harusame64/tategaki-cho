package jp.harusame.aozorareader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;

import org.junit.jupiter.api.Test;

/** The network door lets out https to the four hosts only. */
class NetTest {
    @Test
    void theFourHostsOverHttps() throws Exception {
        assertTrue(Net.allowed(new URL("https://ncode.syosetu.com/n9999zx/1/")));
        assertTrue(Net.allowed(new URL("https://api.syosetu.com/novelapi/api/")));
        assertTrue(Net.allowed(new URL("https://kakuyomu.jp/works/1")));
        assertTrue(Net.allowed(new URL("https://www.aozora.gr.jp/index_pages/x.zip")));
    }

    @Test
    void nothingElse() throws Exception {
        assertFalse(Net.allowed(new URL("http://ncode.syosetu.com/n9999zx/1/")), "plain http");
        assertFalse(Net.allowed(new URL("https://ncode.syosetu.com.example.com/")), "a look-alike host");
        assertFalse(Net.allowed(new URL("https://example.com/ncode.syosetu.com/")), "the host in the path");
        assertFalse(Net.allowed(new URL("https://novel18.syosetu.com/n0000aa/")), "another なろう host");
    }

    @Test
    void aRefusedHostIsNeverRequested() {
        assertThrows(IOException.class, () -> Net.get("https://example.com/"));
    }

    @Test
    void redirectsStayAmongTheHosts() throws Exception {
        URL from = new URL("https://kakuyomu.jp/rankings/all/daily");
        assertTrue(Net.redirect(from, "/rankings/all/daily?work_variation=long", 0).toString()
                .equals("https://kakuyomu.jp/rankings/all/daily?work_variation=long"), "a relative location");
        assertThrows(IOException.class, () -> Net.redirect(from, "https://evil.example/", 0), "another host");
        assertThrows(IOException.class, () -> Net.redirect(from, "http://kakuyomu.jp/", 0), "down to http");
        assertThrows(IOException.class, () -> Net.redirect(from, "/x", Net.MAX_REDIRECTS), "too many hops");
        assertThrows(IOException.class, () -> Net.redirect(from, null, 0), "no location");
    }
}
