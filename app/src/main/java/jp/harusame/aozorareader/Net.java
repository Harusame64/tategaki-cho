package jp.harusame.aozorareader;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The app's only door to the network. Every request, from any screen or worker, goes through here one at a
 * time and at least MIN_GAP_MS after the one before; only https to the four hosts below is allowed, and a
 * redirect is followed only when it stays among them.
 */
final class Net {
    private Net() { }

    static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "ncode.syosetu.com", "api.syosetu.com", "kakuyomu.jp", "www.aozora.gr.jp"));
    static final long MIN_GAP_MS = 1000;
    static final int MAX_REDIRECTS = 3;
    private static long last;

    /**
     * For the E2E tests only: answers requests from fixtures instead of the network (null for "HTTP 404"),
     * still behind the host check, and every request asked is listed in {@link #asked}. Nothing sets it in
     * the app itself.
     */
    static volatile java.util.function.Function<String, byte[]> fake;
    static final java.util.List<String> asked = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    static boolean allowed(URL u) {
        return "https".equals(u.getProtocol()) && HOSTS.contains(u.getHost());
    }

    /**
     * Where a redirect from u leads: resolved against u, and only to an allowed host over https, within
     * MAX_REDIRECTS hops.
     */
    static URL redirect(URL u, String location, int hop) throws IOException {
        if (location == null) throw new IOException("redirect without a location");
        if (hop >= MAX_REDIRECTS) throw new IOException("too many redirects");
        URL to = new URL(u, location);
        if (!allowed(to)) throw new IOException("許可していない宛先への転送: " + to);
        return to;
    }

    /** One GET; the body on 200, IOException("HTTP n") otherwise. */
    static byte[] get(String url) throws IOException {
        return get(url, null);
    }

    /** A request found no longer wanted when its turn came (see {@link #get(String, java.util.function.BooleanSupplier)}). */
    static final class NotWanted extends IOException {
        NotWanted() { super("中断"); }
    }

    /**
     * One GET, asked again whether it is still wanted once its turn comes: a request can wait here behind
     * another (one at a time, a second apart), and what asked for it may have been stopped meanwhile.
     */
    static synchronized byte[] get(String url, java.util.function.BooleanSupplier wanted) throws IOException {
        if (wanted != null && !wanted.getAsBoolean()) throw new NotWanted();
        URL u = new URL(url);
        for (int hop = 0; ; hop++) {
            if (!allowed(u)) throw new IOException("許可していない宛先: " + u.getHost());
            java.util.function.Function<String, byte[]> f = fake;
            if (f != null) {
                asked.add(u.toString());
                byte[] b = f.apply(u.toString());
                if (b == null) throw new IOException("HTTP 404");
                return b;
            }
            long wait = last + MIN_GAP_MS - System.currentTimeMillis();
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("中断"); }
                if (wanted != null && !wanted.getAsBoolean()) throw new NotWanted();
            }
            long t0 = System.nanoTime();
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setRequestProperty("User-Agent", Shelf.USER_AGENT);
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    u = redirect(u, c.getHeaderField("Location"), hop);
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                byte[] b;
                try (InputStream in = c.getInputStream()) { b = in.readAllBytes(); }
                Log.i(Shelf.TAG, "fetched " + u + " " + b.length + " bytes in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                return b;
            } finally {
                last = System.currentTimeMillis();
                c.disconnect();
            }
        }
    }
}
