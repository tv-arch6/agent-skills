package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

public class CigramHttpAndLogTest {
    private TestServer server;

    @Before public void setUp() throws Exception { server = new TestServer(); }

    @After public void tearDown() { server.stop(); }

    @Test public void logsNeverContainTokensKeysOrCredentials() {
        String url = "https://api.example.com/v1/play?id=5&api_key=SECRET123&token=TOP&x=1&Signature=ABCDEF&X-Amz-Credential=AKIA";
        String red = CigramLog.redact(url);
        assertFalse(red, red.contains("SECRET123") || red.contains("TOP") || red.contains("ABCDEF") || red.contains("AKIA"));
        assertTrue(red.contains("id=5") && red.contains("x=1"));
        assertFalse(CigramLog.redact("https://user:pass@host.example/a.m3u8").contains("pass"));
        assertFalse(CigramLog.redact("Authorization: Bearer abc.def.ghi").contains("abc.def.ghi"));
        assertEquals("", CigramLog.redact(null));
        CigramPlaybackSource s = new CigramPlaybackSource();
        s.providerId = "p";
        s.playbackUri = "https://cdn.example/a.m3u8?token=SECRET&expires=123";
        assertFalse(s.toString().contains("SECRET"));
    }

    @Test public void headerInjectionIsRefused() {
        assertFalse(CigramHttp.safeHeader("X-Test", "a\r\nInjected: 1"));
        assertFalse(CigramHttp.safeHeader("X Test", "a"));
        assertFalse(CigramHttp.safeHeader("", "a"));
        assertFalse(CigramHttp.safeHeader("A:B", "a"));
        assertTrue(CigramHttp.safeHeader("Referer", "https://site.example/"));
    }

    @Test public void streamLinkSyntaxMatchesTheExistingPlayerUtility() {
        assertEquals("https://h/x.m3u8", CigramStreamLink.cleanUrl("https://h/x.m3u8|User-Agent=VLC&Referer=https%3A%2F%2Fs%2F"));
        Map<String, String> h = CigramStreamLink.headersOf("https://h/x.m3u8|User-Agent=VLC&Referer=https%3A%2F%2Fs%2F&Bad=a%0D%0Ab");
        assertEquals("VLC", h.get("User-Agent"));
        assertEquals("https://s/", h.get("Referer"));
        assertFalse("a header carrying a line break is dropped", h.containsKey("Bad"));
        assertEquals("hls", CigramStreamLink.kindHint("https://h/a.m3u8?x=1"));
        assertEquals("dash", CigramStreamLink.kindHint("https://h/a.mpd"));
        assertEquals("mp4", CigramStreamLink.kindHint("https://h/a.mp4"));
        assertEquals("", CigramStreamLink.kindHint("https://h/watch?v=1"));
        CigramPlaybackSource s = new CigramPlaybackSource();
        s.playbackUri = "https://h/x.m3u8";
        s.headers.put("Referer", "https://s/ page");
        assertEquals("https://h/x.m3u8|Referer=https%3A%2F%2Fs%2F+page", s.toStreamLink());
        s.headers.clear();
        assertEquals("https://h/x.m3u8", s.toStreamLink());
    }

    @Test public void cancelEndsABlockedReadAtOnce() throws Exception {
        TestServer.Reply slow = TestServer.Reply.ok("application/octet-stream", new byte[10]);
        slow.delayMs = 4000;
        server.route("/slow", slow);
        final CigramCancel cancel = new CigramCancel();
        final long[] took = new long[1];
        final Throwable[] err = new Throwable[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                try { CigramHttp.execute(new CigramHttp.Request(server.base() + "/slow").timeouts(8000, 8000), cancel); }
                catch (Throwable e) { err[0] = e; }
                took[0] = System.currentTimeMillis() - t0;
            }
        });
        t.start();
        Thread.sleep(300);
        cancel.cancel();
        t.join(5000);
        assertTrue("took " + took[0], took[0] < 2000);
        assertEquals(CigramEngineException.CANCELLED, ((CigramEngineException) err[0]).kind);
    }

    @Test public void aRequestAfterCancelNeverReachesTheNetwork() throws Exception {
        server.route("/x", TestServer.Reply.ok("text/plain", new byte[1]));
        CigramCancel c = new CigramCancel();
        c.cancel();
        try {
            CigramHttp.execute(new CigramHttp.Request(server.base() + "/x"), c);
            fail();
        } catch (CigramEngineException e) {
            assertEquals(CigramEngineException.CANCELLED, e.kind);
        }
        assertEquals(0, server.hits("/x"));
    }

    @Test public void childCancelFollowsItsParent() {
        CigramCancel parent = new CigramCancel();
        CigramCancel child = parent.child();
        assertFalse(child.isCancelled());
        parent.cancel();
        assertTrue(child.isCancelled());
        assertTrue("a child made after cancel is born cancelled", parent.child().isCancelled());
    }

    @Test public void bodyIsReadOnlyUpToTheLimitSoHugeFilesCostNothing() throws Exception {
        server.route("/big", TestServer.Reply.ok("video/mp4", new byte[1_000_000]));
        CigramHttp.Request r = new CigramHttp.Request(server.base() + "/big").max(4096);
        r.identityEncoding = true;
        CigramHttp.Response res = CigramHttp.execute(r, new CigramCancel());
        assertEquals(4096, res.body.length);
        assertTrue(res.truncated);
    }

    @Test public void redirectsLeavingTheHostDropTheCredentials() throws Exception {
        TestServer other = new TestServer();
        try {
            other.route("/landing", TestServer.Reply.ok("text/plain", new byte[1]));
            server.route("/go", TestServer.Reply.redirect(other.base() + "/landing"));
            CigramHttp.Request r = new CigramHttp.Request(server.base() + "/go").header("Authorization", "Bearer SECRET");
            // 127.0.0.1 on a different port is the same host name; use localhost for the second hop to change the host
            server.route("/go", TestServer.Reply.redirect(other.base().replace("127.0.0.1", "localhost") + "/landing"));
            CigramHttp.execute(r, new CigramCancel());
            assertEquals("Bearer SECRET", server.lastAuth.get("/go"));
            assertFalse("the credentials must not follow the redirect to another host", other.lastAuth.containsKey("/landing"));
        } finally { other.stop(); }
    }

    @Test public void configFallsBackToDefaultsAndNeverReturnsNull() {
        CigramEngineConfig c = new CigramEngineConfig(new CigramEngineConfig.MapStore());
        assertEquals("https://api.themoviedb.org/3", c.tmdbBase());
        assertEquals("ar", c.tmdbLanguage());
        assertFalse(c.tmdbConfigured());
        c.put("tmdb.token", "t");
        assertTrue(c.tmdbConfigured());
        c.put("tmdb.base", "https://proxy.example/tmdb///");
        assertEquals("https://proxy.example/tmdb", c.tmdbBase());
        assertEquals(30, c.providerPriority("x", 30));
        c.put("provider.x.priority", "abc");
        assertEquals(30, c.providerPriority("x", 30));
        assertTrue(c.providerEnabled("x"));
    }
}
