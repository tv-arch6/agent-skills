package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CigramProvidersTest {
    private TestServer server;
    private CigramEngineConfig config;
    private final ArrayList<JSONObject> items = new ArrayList<JSONObject>();

    private final CigramLibraryData library = new CigramLibraryData() {
        @Override public List<JSONObject> items() { return items; }
        @Override public List<JSONObject> episodesOf(JSONObject series) {
            ArrayList<JSONObject> out = new ArrayList<JSONObject>();
            JSONArray a = series.optJSONArray("episodes");
            for (int i = 0; a != null && i < a.length(); i++) out.add(a.optJSONObject(i));
            return out;
        }
    };

    @Before public void setUp() throws Exception {
        server = new TestServer();
        config = new CigramEngineConfig(new CigramEngineConfig.MapStore());
    }

    @After public void tearDown() { server.stop(); }

    private static CigramMediaRef seriesRef() {
        return new CigramMediaRef.Builder().kind(CigramMediaRef.SERIES).origin(CigramMediaRef.ORIGIN_LIBRARY).libraryId("s9").tmdbId("77").title("مسلسل").build();
    }

    private static CigramMediaRequest episode(int season, int number) {
        return CigramMediaRequest.episode(seriesRef(), new CigramEpisode("", season, number, 0, "", "", "", "", 0, "", ""), null);
    }

    private static CigramMediaRequest movie() {
        return CigramMediaRequest.movie(new CigramMediaRef.Builder().kind(CigramMediaRef.MOVIE).tmdbId("603").imdbId("tt0133093").title("The Matrix").year(1999).build(), null);
    }

    // ---------------------------------------------------------------- library provider

    @Test public void libraryReturnsTheLinkOfThatEpisodeOnlyNeverANeighbour() throws Exception {
        items.add(new JSONObject("{\"id\":\"s9\",\"type\":\"series\",\"title\":\"مسلسل\",\"tmdb_id\":\"77\",\"episodes\":["
                + "{\"season\":\"1\",\"episode\":\"3\",\"video\":\"https://x/s1e3.mp4\"},"
                + "{\"season\":\"1\",\"episode\":\"1\",\"video\":\"https://x/s1e1.mp4\",\"subtitle\":\"https://x/s1e1.vtt\"},"
                + "{\"season\":\"1\",\"episode\":\"2\"},"
                + "{\"season\":\"2\",\"episode\":\"1\",\"video\":\"https://x/s2e1.mp4|Referer=https%3A%2F%2Fsite%2F\"}]}"));
        CigramLibraryProvider p = new CigramLibraryProvider(library);

        List<CigramPlaybackSource> e1 = p.resolve(episode(1, 1), new CigramCancel());
        assertEquals(1, e1.size());
        assertEquals("https://x/s1e1.mp4", e1.get(0).playbackUri);
        assertEquals("library", e1.get(0).providerId);
        assertEquals(1, e1.get(0).supportedSubtitles.size());
        assertTrue(e1.get(0).supportedSubtitles.get(0).providerDeclared);

        assertTrue("episode 2 has no link: nothing, not episode 1 or 3", p.resolve(episode(1, 2), new CigramCancel()).isEmpty());
        assertTrue("an episode that does not exist", p.resolve(episode(1, 9), new CigramCancel()).isEmpty());

        List<CigramPlaybackSource> s2 = p.resolve(episode(2, 1), new CigramCancel());
        assertEquals("https://x/s2e1.mp4", s2.get(0).playbackUri);
        assertEquals("https://site/", s2.get(0).headers.get("Referer"));
        assertEquals("https://x/s2e1.mp4|Referer=https%3A%2F%2Fsite%2F", s2.get(0).toStreamLink());
    }

    @Test public void libraryFindsAMovieByItsTmdbIdWhenOpenedFromTheCatalog() throws Exception {
        items.add(new JSONObject("{\"id\":\"m1\",\"type\":\"movie\",\"title\":\"ماتريكس\",\"tmdb_id\":\"603\",\"video\":\"https://x/matrix.m3u8\"}"));
        CigramLibraryProvider p = new CigramLibraryProvider(library);
        List<CigramPlaybackSource> r = p.resolve(movie(), new CigramCancel());
        assertEquals(1, r.size());
        assertEquals("https://x/matrix.m3u8", r.get(0).playbackUri);
    }

    @Test public void libraryDoesNotAnswerForAnotherWorkWithTheSameTitle() throws Exception {
        items.add(new JSONObject("{\"id\":\"m1\",\"type\":\"movie\",\"title\":\"The Matrix\",\"tmdb_id\":\"604\",\"video\":\"https://x/reloaded.mp4\"}"));
        CigramLibraryProvider p = new CigramLibraryProvider(library);
        assertTrue(p.resolve(movie(), new CigramCancel()).isEmpty());
    }

    @Test public void sourcesAreStampedWithTheRequestIdentity() throws Exception {
        items.add(new JSONObject("{\"id\":\"m1\",\"type\":\"movie\",\"tmdb_id\":\"603\",\"title\":\"x\",\"video\":\"https://x/a.mp4\"}"));
        CigramMediaRequest r = movie();
        CigramPlaybackSource s = new CigramLibraryProvider(library).resolve(r, new CigramCancel()).get(0);
        assertEquals(r.identityKey(), s.requestKey);
    }

    // ---------------------------------------------------------------- authorised-API provider

    @Test public void theFourNamedProvidersReportNeedsApiAndResolveNothing() {
        List<CigramSourceProvider> all = CigramProviders.createDefault(config, library);
        assertEquals(5, all.size());
        assertEquals("library", all.get(0).id());
        String[] ids = {"vidfast", "vidnest", "videm", "vidbolt"};
        for (int i = 0; i < 4; i++) {
            CigramSourceProvider p = all.get(i + 1);
            assertEquals(ids[i], p.id());
            assertEquals(CigramSourceProvider.NEEDS_API, p.status());
            assertTrue(p.statusNote().contains("API"));
            try {
                p.resolve(movie(), new CigramCancel());
                fail("a provider without an API must not return anything");
            } catch (CigramEngineException e) {
                assertEquals(CigramEngineException.NOT_CONFIGURED, e.kind);
            }
        }
        String report = CigramProviders.report(all);
        assertTrue(report.contains("VidFast") && report.contains("يحتاج API") && report.contains("vidbolt.pro"));
    }

    private CigramJsonProvider configured(String id) {
        config.put("provider." + id + ".api", server.base() + "/api");
        config.put("provider." + id + ".token", "sekret-key");
        return new CigramJsonProvider(id, "Prov", 10, config, "note");
    }

    @Test public void aConfiguredProviderReturnsOnlyWhatTheApiSaysAndKeepsTheKeyInTheHeader() throws Exception {
        server.route("/api/resolve", TestServer.Reply.text("application/json", "{\"sources\":["
                + "{\"url\":\"https://cdn.example/a/index.m3u8\",\"type\":\"hls\",\"quality\":\"1080p\",\"headers\":{\"Referer\":\"https://p.example/\",\"Bad\\r\\nHeader\":\"x\"},"
                + "\"subtitles\":[{\"url\":\"https://cdn.example/a/ar.vtt\",\"lang\":\"ar\",\"label\":\"العربية\"}]},"
                + "{\"url\":\"https://cdn.example/a/index.m3u8\",\"type\":\"hls\"},"
                + "{\"url\":\"javascript:alert(1)\",\"type\":\"mp4\"},"
                + "{\"url\":\"\",\"type\":\"mp4\"},"
                + "{\"url\":\"https://cdn.example/b.mp4\",\"type\":\"mp4\"}]}"));
        CigramJsonProvider p = configured("prov");
        assertEquals(CigramSourceProvider.INTEGRATED, p.status());
        List<CigramPlaybackSource> r = p.resolve(episode(2, 5), new CigramCancel());
        assertEquals("duplicate, javascript: and empty links are dropped", 2, r.size());
        assertEquals("application/x-mpegURL", r.get(0).mimeType);
        assertEquals("1080p", r.get(0).quality);
        assertEquals("https://p.example/", r.get(0).headers.get("Referer"));
        assertEquals(1, r.get(0).headers.size());
        assertEquals("ar", r.get(0).supportedSubtitles.get(0).language);
        assertEquals("Bearer sekret-key", server.lastAuth.get("/api/resolve"));
        String q = server.lastQuery.get("/api/resolve");
        assertFalse(q.contains("sekret"));
        assertTrue(q.contains("type=episode") && q.contains("season=2") && q.contains("episode=5") && q.contains("tmdb=77"));
    }

    @Test public void drmAndEmbedOnlySourcesAreFlaggedNotPlayable() throws Exception {
        server.route("/api/resolve", TestServer.Reply.text("application/json", "{\"sources\":["
                + "{\"url\":\"https://cdn.example/d.mpd\",\"type\":\"dash\",\"drm\":true},"
                + "{\"url\":\"https://embed.example/e/603\",\"type\":\"embed\"}]}"));
        List<CigramPlaybackSource> r = configured("prov").resolve(movie(), new CigramCancel());
        assertEquals(CigramPlaybackSource.DRM, r.get(0).availability);
        assertFalse(r.get(0).isPlayable);
        assertEquals(CigramPlaybackSource.EMBED_ONLY, r.get(1).availability);
        assertFalse(r.get(1).isPlayable);
        assertTrue(r.get(1).failureReason.contains("لا يوجد رابط وسائط مباشر"));
    }

    @Test public void apiErrorsAreMappedNotSwallowed() throws Exception {
        CigramJsonProvider p = configured("prov");
        server.route("/api/resolve", TestServer.Reply.code(403));
        try { p.resolve(movie(), new CigramCancel()); fail(); } catch (CigramEngineException e) { assertEquals(CigramEngineException.AUTH, e.kind); }
        server.route("/api/resolve", TestServer.Reply.text("application/json", "{\"error\":\"login_required\"}"));
        try { p.resolve(movie(), new CigramCancel()); fail(); } catch (CigramEngineException e) { assertEquals(CigramEngineException.AUTH, e.kind); }
        server.route("/api/resolve", TestServer.Reply.text("application/json", "<html>"));
        try { p.resolve(movie(), new CigramCancel()); fail(); } catch (CigramEngineException e) { assertEquals(CigramEngineException.PARSE, e.kind); }
        server.route("/api/resolve", TestServer.Reply.code(404));
        assertTrue(p.resolve(movie(), new CigramCancel()).isEmpty());
    }

    @Test public void theApiBaseMustBeHttpsExceptOnLoopback() {
        assertTrue(CigramJsonProvider.apiBaseOk("https://resolver.example.com/v1"));
        assertTrue(CigramJsonProvider.apiBaseOk("http://127.0.0.1:8080/x"));
        assertFalse(CigramJsonProvider.apiBaseOk("http://resolver.example.com/v1"));
        assertFalse(CigramJsonProvider.apiBaseOk("ftp://x"));
        assertFalse(CigramJsonProvider.apiBaseOk(""));
        config.put("provider.p2.api", "http://insecure.example.com");
        CigramJsonProvider p = new CigramJsonProvider("p2", "P2", 10, config, "note");
        assertEquals(CigramSourceProvider.NEEDS_API, p.status());
        assertTrue(p.statusNote().contains("https"));
    }

    @Test public void aProviderCanBeSwitchedOff() {
        CigramJsonProvider p = configured("prov");
        config.put("provider.prov.enabled", "0");
        assertEquals(CigramSourceProvider.DISABLED, p.status());
        try {
            p.resolve(movie(), new CigramCancel());
            fail();
        } catch (CigramEngineException e) {
            assertEquals(CigramEngineException.NOT_CONFIGURED, e.kind);
        }
        config.put("provider.prov.enabled", "1");
        assertEquals(CigramSourceProvider.INTEGRATED, p.status());
    }

    @Test public void cancellingStopsAProviderRequestImmediately() throws Exception {
        final CigramJsonProvider p = configured("prov");
        TestServer.Reply slow = TestServer.Reply.text("application/json", "{\"sources\":[]}");
        slow.delayMs = 3000;
        server.route("/api/resolve", slow);
        final CigramMediaRequest req = movie();
        final CigramCancel c = new CigramCancel();
        final long[] took = new long[1];
        final Throwable[] err = new Throwable[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long t0 = System.currentTimeMillis();
                try { p.resolve(req, c); } catch (Throwable e) { err[0] = e; }
                took[0] = System.currentTimeMillis() - t0;
            }
        });
        t.start();
        Thread.sleep(300);
        p.cancel(req.requestId);              // the way the coordinator stops a provider
        t.join(4000);
        assertTrue("cancelled call must end quickly, took " + took[0], took[0] < 2000);
        assertTrue(String.valueOf(err[0]), err[0] instanceof CigramEngineException);
        assertEquals(CigramEngineException.CANCELLED, ((CigramEngineException) err[0]).kind);
    }

    // ---------------------------------------------------------------- episode helpers

    @Test public void episodeOrderingHandlesUnnumberedAndSpecialEpisodes() {
        ArrayList<CigramEpisode> in = new ArrayList<CigramEpisode>();
        in.add(new CigramEpisode("sp", 0, 1, 0, "special", "", "", "", 0, "", ""));
        in.add(new CigramEpisode("u1", 1, -1, 1, "unnumbered first", "", "", "", 0, "", ""));
        in.add(new CigramEpisode("e3", 1, 3, 2, "three", "", "", "", 0, "", ""));
        in.add(new CigramEpisode("e1", 1, 1, 3, "one", "", "", "", 0, "", ""));
        in.add(new CigramEpisode("u2", 1, 0, 4, "unnumbered second", "", "", "", 0, "", ""));
        in.add(new CigramEpisode("s2", 2, 1, 5, "next season", "", "", "", 0, "", ""));
        in.add(new CigramEpisode("e1b", 1, 1, 6, "duplicate of one", "", "", "", 0, "", ""));
        List<CigramEpisode> out = CigramEpisode.normalize(in);
        StringBuilder sb = new StringBuilder();
        for (CigramEpisode e : out) sb.append(e.title).append('|');
        assertEquals("one|three|unnumbered first|unnumbered second|next season|special|", sb.toString());
        assertEquals("unnumbered first", CigramEpisode.next(out, CigramEpisode.find(out, 1, 3)).title);
        assertNull(CigramEpisode.next(out, out.get(out.size() - 1)));
        assertNull(CigramEpisode.previous(out, out.get(0)));
    }

    @Test public void requestIdentityDependsOnTheWorkAndEpisodeNotOnTheRequestId() {
        CigramMediaRequest a = episode(1, 2), b = episode(1, 2), c = episode(1, 3), d = episode(2, 2);
        assertEquals(a.identityKey(), b.identityKey());
        assertFalse(a.requestId.equals(b.requestId));
        assertFalse(a.identityKey().equals(c.identityKey()));
        assertFalse(a.identityKey().equals(d.identityKey()));
        assertFalse(a.identityKey().equals(movie().identityKey()));
    }

    // ---------------------------------------------------------------- documented embed templates

    @Test public void embedTemplateFromTheProvidersDocsGivesThePlayerPageOfExactlyThatEpisode() throws Exception {
        config.put("provider.vidfast.embed.movie", "https://player.example/movie/{tmdb}");
        config.put("provider.vidfast.embed.tv", "https://player.example/tv/{tmdb}/{season}/{episode}");
        CigramJsonProvider p = new CigramJsonProvider("vidfast", "VidFast", 10, config, "note");
        assertEquals(CigramSourceProvider.INTEGRATED, p.status());
        CigramPlaybackSource m = p.resolve(movie(), new CigramCancel()).get(0);
        assertEquals("https://player.example/movie/603", m.playbackUri);
        assertEquals(CigramPlaybackSource.EMBED_ONLY, m.availability);
        assertFalse(m.isPlayable);
        assertEquals("https://player.example/tv/77/2/5", p.resolve(episode(2, 5), new CigramCancel()).get(0).playbackUri);
    }

    @Test public void badOrUnsafeTemplatesAreRefusedAndHostileIdsAreNotInserted() throws Exception {
        assertFalse(CigramJsonProvider.embedOk("http://player.example/{tmdb}", false));
        assertFalse(CigramJsonProvider.embedOk("https://player.example/x", false));
        assertFalse(CigramJsonProvider.embedOk("https://player.example/{tmdb}/{unknown}", false));
        assertFalse(CigramJsonProvider.embedOk("https://player.example/tv/{tmdb}", true));
        config.put("provider.p.embed.movie", "https://player.example/movie/{tmdb}");
        CigramJsonProvider p = new CigramJsonProvider("p", "P", 10, config, "note");
        CigramMediaRef evil = new CigramMediaRef.Builder().kind(CigramMediaRef.MOVIE).tmdbId("1/../x").title("t").build();
        assertTrue(p.resolve(CigramMediaRequest.movie(evil, null), new CigramCancel()).isEmpty());
    }

    @Test public void theCoordinatorUsesTheProviderPlayerOnlyWhenNoNativeSourceWorks() throws Exception {
        config.put("provider.vidfast.embed.movie", "https://player.example/movie/{tmdb}");
        java.util.ArrayList<CigramSourceProvider> list = new java.util.ArrayList<CigramSourceProvider>();
        list.add(new CigramLibraryProvider(library));
        list.add(new CigramJsonProvider("vidfast", "VidFast", 10, config, "note"));
        java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newCachedThreadPool();
        try {
            CigramPlaybackCoordinator c = new CigramPlaybackCoordinator(list, io, CigramPoster.DIRECT);
            final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
            final CigramPlaybackSource[] got = new CigramPlaybackSource[1];
            c.play(movie(), new CigramPlaybackCoordinator.Listener() {
                @Override public void onState(CigramMediaRequest r, int s, String m) { }
                @Override public void onReady(CigramMediaRequest r, CigramPlaybackSource ch, List<CigramPlaybackSource> a) { got[0] = ch; l.countDown(); }
                @Override public void onFailed(CigramMediaRequest r, String m, List<CigramPlaybackCoordinator.Report> rep, boolean x) { l.countDown(); }
            });
            assertTrue(l.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(CigramPlaybackSource.EMBED_ONLY, got[0].availability);
            assertEquals("https://player.example/movie/603", got[0].playbackUri);
        } finally { io.shutdownNow(); }
    }

    @Test public void aMovieThatTheAdminLinkedToAProviderPlayerIsOfferedAsThatPlayer() throws Exception {
        items.add(new JSONObject("{\"id\":\"m9\",\"type\":\"movie\",\"title\":\"x\",\"tmdb_id\":\"603\",\"video\":\"\",\"embed_url\":\"https://player.example/movie/603\"}"));
        List<CigramPlaybackSource> r = new CigramLibraryProvider(library).resolve(movie(), new CigramCancel());
        assertEquals(1, r.size());
        assertEquals(CigramPlaybackSource.EMBED_ONLY, r.get(0).availability);
        assertEquals("https://player.example/movie/603", r.get(0).playbackUri);
        items.clear();
        items.add(new JSONObject("{\"id\":\"m9\",\"type\":\"movie\",\"title\":\"x\",\"tmdb_id\":\"603\",\"embed_url\":\"http://insecure.example/603\"}"));
        assertTrue(new CigramLibraryProvider(library).resolve(movie(), new CigramCancel()).isEmpty());
    }

    @Test public void backupLinksSavedByTheAdminAreOfferedAfterTheMainLinkInOrder() throws Exception {
        items.add(new JSONObject("{\"id\":\"m5\",\"type\":\"movie\",\"title\":\"x\",\"tmdb_id\":\"603\",\"video\":\"https://a.example/main.m3u8\","
                + "\"video_backups\":\"https://b.example/b1.mp4\\nnot a link\\nhttps://c.example/b2.m3u8|Referer=https%3A%2F%2Fc.example%2F\"}"));
        List<CigramPlaybackSource> r = new CigramLibraryProvider(library).resolve(movie(), new CigramCancel());
        assertEquals(3, r.size());
        assertEquals("https://a.example/main.m3u8", r.get(0).playbackUri);
        assertEquals("https://b.example/b1.mp4", r.get(1).playbackUri);
        assertEquals("https://c.example/b2.m3u8", r.get(2).playbackUri);
        assertEquals("https://c.example/", r.get(2).headers.get("Referer"));
        assertTrue(r.get(1).priority < r.get(2).priority);
    }

    @Test public void whenTheMainLinkFailsInThePlayerTheNextBackupOfTheSameMoviePlays() throws Exception {
        items.add(new JSONObject("{\"id\":\"m5\",\"type\":\"movie\",\"title\":\"x\",\"tmdb_id\":\"603\",\"video\":\"https://a.example/main.m3u8\","
                + "\"video_backups\":\"https://b.example/b1.mp4\"}"));
        CigramLibraryProvider lib = new CigramLibraryProvider(library);
        java.util.ArrayList<CigramSourceProvider> list = new java.util.ArrayList<CigramSourceProvider>();
        list.add(new CigramBaseProviderWrapper(lib));
        java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newCachedThreadPool();
        try {
            CigramPlaybackCoordinator c = new CigramPlaybackCoordinator(list, io, CigramPoster.DIRECT);
            final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
            final CigramPlaybackSource[] got = new CigramPlaybackSource[1];
            // the player failed on the main link: exclude it, as CigramPlayerBridge does
            c.play(movie(), new CigramPlaybackCoordinator.Listener() {
                @Override public void onState(CigramMediaRequest r, int s, String m) { }
                @Override public void onReady(CigramMediaRequest r, CigramPlaybackSource ch, List<CigramPlaybackSource> a) { got[0] = ch; l.countDown(); }
                @Override public void onFailed(CigramMediaRequest r, String m, List<CigramPlaybackCoordinator.Report> rep, boolean x) { l.countDown(); }
            }, "library|https://a.example/main.m3u8");
            assertTrue(l.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("https://b.example/b1.mp4", got[0].playbackUri);
        } finally { io.shutdownNow(); }
    }

    /** Library provider whose validation says yes to everything (no network in this test). */
    static final class CigramBaseProviderWrapper implements CigramSourceProvider {
        final CigramLibraryProvider inner;
        CigramBaseProviderWrapper(CigramLibraryProvider p) { inner = p; }
        @Override public String id() { return inner.id(); }
        @Override public String displayName() { return inner.displayName(); }
        @Override public int priority() { return inner.priority(); }
        @Override public int status() { return inner.status(); }
        @Override public String statusNote() { return inner.statusNote(); }
        @Override public boolean supports(CigramMediaRequest r) { return inner.supports(r); }
        @Override public List<String> search(String t, String c, int s, int e, CigramCancel x) { return new java.util.ArrayList<String>(); }
        @Override public List<CigramPlaybackSource> resolve(CigramMediaRequest r, CigramCancel x) throws CigramEngineException { return inner.resolve(r, x); }
        @Override public CigramSourceValidator.Result validate(CigramPlaybackSource s, CigramCancel x) { return CigramSourceValidator.Result.ok("hls", "x"); }
        @Override public void cancel(String id) { inner.cancel(id); }
    }
}
