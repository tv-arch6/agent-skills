package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** The catalog against a local server that answers like TMDB (the real service is not reachable from the build machine). */
public class CigramMediaCatalogTest {
    private TestServer server;
    private ExecutorService io;
    private CigramEngineConfig config;
    private final ArrayList<JSONObject> libraryItems = new ArrayList<JSONObject>();
    private CigramMediaCatalog catalog;

    @Before public void setUp() throws Exception {
        server = new TestServer();
        io = Executors.newCachedThreadPool();
        config = new CigramEngineConfig(new CigramEngineConfig.MapStore());
        config.put("tmdb.base", server.base() + "/3");
        config.put("tmdb.token", "test-read-token");
        CigramLibraryData lib = new CigramLibraryData() {
            @Override public List<JSONObject> items() { return libraryItems; }
            @Override public List<JSONObject> episodesOf(JSONObject series) {
                ArrayList<JSONObject> out = new ArrayList<JSONObject>();
                JSONArray a = series.optJSONArray("episodes");
                for (int i = 0; a != null && i < a.length(); i++) out.add(a.optJSONObject(i));
                return out;
            }
        };
        ArrayList<CigramMediaCatalog.Source> sources = new ArrayList<CigramMediaCatalog.Source>();
        sources.add(new CigramLibrarySource(lib));
        sources.add(new CigramTmdbSource(config));
        catalog = new CigramMediaCatalog(sources, io, CigramPoster.DIRECT);
    }

    @After public void tearDown() { server.stop(); io.shutdownNow(); }

    private static JSONObject obj(String json) throws Exception { return new JSONObject(json); }

    private void tmdbSearchFixture() {
        server.route("/3/search/multi", TestServer.Reply.text("application/json",
                "{\"results\":["
                        + "{\"id\":438631,\"media_type\":\"movie\",\"title\":\"كثيب\",\"original_title\":\"Dune\",\"release_date\":\"2021-09-15\",\"poster_path\":\"/dune21.jpg\",\"overview\":\"...\",\"vote_average\":7.8},"
                        + "{\"id\":841,\"media_type\":\"movie\",\"title\":\"Dune\",\"original_title\":\"Dune\",\"release_date\":\"1984-12-14\",\"poster_path\":\"/dune84.jpg\"},"
                        + "{\"id\":90001,\"media_type\":\"person\",\"name\":\"Dune Person\"},"
                        + "{\"id\":90002,\"media_type\":\"tv\",\"name\":\"Dune: Prophecy\",\"original_name\":\"Dune: Prophecy\",\"first_air_date\":\"2024-11-17\"},"
                        + "{\"id\":0,\"media_type\":\"movie\",\"title\":\"broken\"}"
                        + "]}"));
    }

    private CigramMediaCatalog.SearchResult search(String q) throws Exception {
        final AtomicReference<CigramMediaCatalog.SearchResult> got = new AtomicReference<CigramMediaCatalog.SearchResult>();
        final AtomicReference<CigramEngineException> err = new AtomicReference<CigramEngineException>();
        final CountDownLatch l = new CountDownLatch(1);
        catalog.search(q, new CigramMediaCatalog.Callback<CigramMediaCatalog.SearchResult>() {
            @Override public void onResult(CigramMediaCatalog.SearchResult r) { got.set(r); l.countDown(); }
            @Override public void onError(CigramEngineException e) { err.set(e); l.countDown(); }
        });
        assertTrue(l.await(10, TimeUnit.SECONDS));
        if (err.get() != null) throw err.get();
        return got.get();
    }

    // ---------------------------------------------------------------- search

    @Test public void similarTitlesAreAllReturnedWithDistinctIdsAndPeopleAreDropped() throws Exception {
        tmdbSearchFixture();
        CigramMediaCatalog.SearchResult r = search("Dune");
        assertEquals(3, r.results.size());
        assertTrue(r.ambiguous());
        List<String> ids = new ArrayList<String>();
        for (CigramMediaRef m : r.results) ids.add(m.catalogId);
        assertTrue(ids.contains("tmdb:movie:438631"));
        assertTrue(ids.contains("tmdb:movie:841"));
        assertTrue(ids.contains("tmdb:tv:90002"));
        CigramMediaRef dune21 = null;
        for (CigramMediaRef m : r.results) if (m.tmdbId.equals("438631")) dune21 = m;
        assertEquals("كثيب", dune21.title);
        assertEquals("Dune", dune21.originalTitle);
        assertEquals(2021, dune21.year);
        assertEquals("https://image.tmdb.org/t/p/w342/dune21.jpg", dune21.poster);
        assertEquals(CigramMediaRef.MOVIE, dune21.kind);
    }

    @Test public void theYearTypedInTheQueryRanksTheRightWorkFirstAndIsNotSentAsTitle() throws Exception {
        tmdbSearchFixture();
        assertEquals("tmdb:movie:841", search("Dune 1984").results.get(0).catalogId);
        assertEquals("tmdb:movie:438631", search("dune (2021)").results.get(0).catalogId);
        assertEquals("language=ar", server.lastQuery.get("/3/search/multi").replaceAll(".*(language=[a-z]+).*", "$1"));
        assertTrue(server.lastQuery.get("/3/search/multi").contains("query=dune"));
        assertFalse("the year is only a ranking hint", server.lastQuery.get("/3/search/multi").contains("2021"));
    }

    @Test public void credentialsAreSentInTheHeaderNeverInTheUrl() throws Exception {
        tmdbSearchFixture();
        search("Dune");
        assertEquals("Bearer test-read-token", server.lastAuth.get("/3/search/multi"));
        assertFalse(server.lastQuery.get("/3/search/multi").contains("test-read-token"));
    }

    @Test public void aProxyWithoutAnyKeyInTheAppWorksAndTheOptionalAppKeyGoesInItsOwnHeader() throws Exception {
        tmdbSearchFixture();
        config.put("tmdb.token", null);
        config.put("tmdb.appkey", "shared-speed-bump");
        assertTrue("a non-TMDB base counts as configured: the proxy holds the secret", config.tmdbConfigured());
        assertEquals(3, search("Dune").results.size());
        assertNull(server.lastAuth.get("/3/search/multi"));
    }

    @Test public void libraryTitleWinsOverTheSameTitleFromTheCatalog() throws Exception {
        tmdbSearchFixture();
        libraryItems.add(obj("{\"id\":\"m7\",\"type\":\"movie\",\"title\":\"Dune\",\"year\":\"2021\",\"tmdb_id\":\"438631\",\"video\":\"https://cdn.example/dune.mp4\"}"));
        CigramMediaCatalog.SearchResult r = search("Dune");
        assertEquals(3, r.results.size());       // 1 library + 1984 + series; the TMDB 2021 duplicate is merged away
        assertEquals(CigramMediaRef.ORIGIN_LIBRARY, r.results.get(0).origin);
        int dune2021 = 0;
        for (CigramMediaRef m : r.results) if (m.year == 2021) dune2021++;
        assertEquals(1, dune2021);
    }

    @Test public void twoDifferentLibraryWorksWithTheSameTitleAreNotMerged() throws Exception {
        tmdbSearchFixture();
        libraryItems.add(obj("{\"id\":\"a\",\"type\":\"movie\",\"title\":\"Dune\",\"year\":\"1984\",\"tmdb_id\":\"841\"}"));
        libraryItems.add(obj("{\"id\":\"b\",\"type\":\"movie\",\"title\":\"Dune\",\"year\":\"2021\",\"tmdb_id\":\"438631\"}"));
        CigramMediaCatalog.SearchResult r = search("Dune");
        int lib = 0;
        for (CigramMediaRef m : r.results) if (CigramMediaRef.ORIGIN_LIBRARY.equals(m.origin)) lib++;
        assertEquals(2, lib);
    }

    @Test public void unknownNameGivesAnEmptyListNotAnError() throws Exception {
        server.route("/3/search/multi", TestServer.Reply.text("application/json", "{\"results\":[]}"));
        CigramMediaCatalog.SearchResult r = search("zzzz no such title");
        assertTrue(r.results.isEmpty());
        assertFalse(r.ambiguous());
    }

    @Test public void libraryStillAnswersWhenTheCatalogIsDown() throws Exception {
        libraryItems.add(obj("{\"id\":\"m7\",\"title\":\"فيلم تجريبي\",\"type\":\"movie\"}"));
        server.route("/3/search/multi", TestServer.Reply.code(503));
        CigramMediaCatalog.SearchResult r = search("تجريبي");
        assertEquals(1, r.results.size());
        assertEquals(1, r.warnings.size());
    }

    @Test public void catalogErrorsAreMappedToClearKindsWhenItIsTheOnlySource() throws Exception {
        ArrayList<CigramMediaCatalog.Source> only = new ArrayList<CigramMediaCatalog.Source>();
        only.add(new CigramTmdbSource(config));
        catalog = new CigramMediaCatalog(only, io, CigramPoster.DIRECT);
        server.route("/3/search/multi", TestServer.Reply.code(401));
        try { search("anything"); org.junit.Assert.fail(); } catch (CigramEngineException e) { assertEquals(CigramEngineException.AUTH, e.kind); }
        server.route("/3/search/multi", TestServer.Reply.code(429));
        try { search("anything"); org.junit.Assert.fail(); } catch (CigramEngineException e) { assertTrue(e.retryable()); }
        server.route("/3/search/multi", TestServer.Reply.text("application/json", "not json at all"));
        try { search("anything"); org.junit.Assert.fail(); } catch (CigramEngineException e) { assertEquals(CigramEngineException.PARSE, e.kind); }
    }

    @Test public void aCatalogFailureNextToAnEmptyLibraryBecomesAWarningNotAFakeEmptyAnswer() throws Exception {
        server.route("/3/search/multi", TestServer.Reply.code(401));
        CigramMediaCatalog.SearchResult r = search("anything");
        assertTrue(r.results.isEmpty());
        assertEquals(1, r.warnings.size());
        assertTrue(r.warnings.get(0).contains("مفتاح"));
    }

    @Test public void aNewSearchCancelsTheOldOneAndTheOldAnswerNeverArrives() throws Exception {
        TestServer.Reply slow = TestServer.Reply.text("application/json", "{\"results\":[{\"id\":1,\"media_type\":\"movie\",\"title\":\"Slow\"}]}");
        slow.delayMs = 700;
        server.route("/3/search/multi", slow);
        final AtomicReference<CigramMediaCatalog.SearchResult> first = new AtomicReference<CigramMediaCatalog.SearchResult>();
        catalog.search("slow one", new CigramMediaCatalog.Callback<CigramMediaCatalog.SearchResult>() {
            @Override public void onResult(CigramMediaCatalog.SearchResult r) { first.set(r); }
            @Override public void onError(CigramEngineException e) { }
        });
        Thread.sleep(100);
        slow.delayMs = 0;
        CigramMediaCatalog.SearchResult second = search("fast one");
        assertEquals(1, second.results.size());
        Thread.sleep(900);
        assertNull("the cancelled search must not deliver", first.get());
    }

    @Test public void tooShortQueryDoesNotHitTheNetwork() throws Exception {
        CigramMediaCatalog.SearchResult r = search("a");
        assertTrue(r.results.isEmpty());
        assertEquals(0, server.hits("/3/search/multi"));
    }

    // ---------------------------------------------------------------- seasons / episodes

    private CigramMediaRef breakingSeries() {
        return new CigramMediaRef.Builder().kind(CigramMediaRef.SERIES).tmdbId("1396").title("Breaking").build();
    }

    private void seriesFixture() {
        server.route("/3/tv/1396", TestServer.Reply.text("application/json",
                "{\"id\":1396,\"number_of_seasons\":3,\"number_of_episodes\":8,\"seasons\":["
                        + "{\"season_number\":3,\"name\":\"الموسم 3\",\"episode_count\":2,\"air_date\":\"2010-03-21\"},"
                        + "{\"season_number\":0,\"name\":\"Specials\",\"episode_count\":1},"
                        + "{\"season_number\":1,\"name\":\"الموسم 1\",\"episode_count\":3,\"air_date\":\"2008-01-20\"},"
                        + "{\"season_number\":2,\"name\":\"الموسم 2\",\"episode_count\":2,\"air_date\":\"2009-03-08\"}]}"));
        server.route("/3/tv/1396/season/1", TestServer.Reply.text("application/json",
                "{\"episodes\":[{\"id\":13,\"season_number\":1,\"episode_number\":3,\"name\":\"Three\"},"
                        + "{\"id\":11,\"season_number\":1,\"episode_number\":1,\"name\":\"One\",\"still_path\":\"/s1e1.jpg\"},"
                        + "{\"id\":12,\"season_number\":1,\"episode_number\":2,\"name\":\"Two\"}]}"));
        server.route("/3/tv/1396/season/2", TestServer.Reply.text("application/json",
                "{\"episodes\":[{\"id\":22,\"season_number\":2,\"episode_number\":2,\"name\":\"B\"},{\"id\":21,\"season_number\":2,\"episode_number\":1,\"name\":\"A\"}]}"));
        server.route("/3/tv/1396/season/3", TestServer.Reply.text("application/json",
                "{\"episodes\":[{\"id\":31,\"season_number\":3,\"episode_number\":1,\"name\":\"C\"},{\"id\":32,\"season_number\":3,\"episode_number\":2,\"name\":\"D\"}]}"));
        server.route("/3/tv/1396/season/0", TestServer.Reply.text("application/json",
                "{\"episodes\":[{\"id\":99,\"season_number\":0,\"episode_number\":0,\"name\":\"Behind the scenes\"},{\"id\":98,\"season_number\":0,\"name\":\"Unnumbered\"}]}"));
    }

    private <T> T await(CigramCatalogCall<T> call) throws Exception {
        final AtomicReference<T> got = new AtomicReference<T>();
        final AtomicReference<CigramEngineException> err = new AtomicReference<CigramEngineException>();
        final CountDownLatch l = new CountDownLatch(1);
        call.run(new CigramMediaCatalog.Callback<T>() {
            @Override public void onResult(T v) { got.set(v); l.countDown(); }
            @Override public void onError(CigramEngineException e) { err.set(e); l.countDown(); }
        });
        assertTrue(l.await(10, TimeUnit.SECONDS));
        if (err.get() != null) throw err.get();
        return got.get();
    }

    private interface CigramCatalogCall<T> { void run(CigramMediaCatalog.Callback<T> cb); }

    @Test public void everySeasonIsReturnedInNumericOrderWithSpecialsLast() throws Exception {
        seriesFixture();
        final CigramMediaRef s = breakingSeries();
        List<CigramSeason> seasons = await(new CigramCatalogCall<List<CigramSeason>>() {
            @Override public void run(CigramMediaCatalog.Callback<List<CigramSeason>> cb) { catalog.seasons(s, cb); }
        });
        assertEquals(4, seasons.size());
        assertEquals(1, seasons.get(0).number);
        assertEquals(2, seasons.get(1).number);
        assertEquals(3, seasons.get(2).number);
        assertEquals(0, seasons.get(3).number);
        assertTrue(seasons.get(3).isSpecial());
        assertEquals("حلقات خاصة", new CigramSeason(0, "", 1, "", 0).label());
    }

    @Test public void episodesComeSortedWhateverTheJsonOrderAndKeepTheirOwnIds() throws Exception {
        seriesFixture();
        final CigramMediaRef s = breakingSeries();
        List<CigramEpisode> eps = await(new CigramCatalogCall<List<CigramEpisode>>() {
            @Override public void run(CigramMediaCatalog.Callback<List<CigramEpisode>> cb) { catalog.episodes(s, 1, cb); }
        });
        assertEquals(3, eps.size());
        assertEquals("One", eps.get(0).title);
        assertEquals("Two", eps.get(1).title);
        assertEquals("Three", eps.get(2).title);
        assertEquals("tmdb:ep:11", eps.get(0).episodeId);
        assertEquals("https://image.tmdb.org/t/p/w300/s1e1.jpg", eps.get(0).still);
    }

    @Test public void allEpisodesAreInViewingOrderAcrossSeasons() throws Exception {
        seriesFixture();
        final CigramMediaRef s = breakingSeries();
        List<CigramEpisode> all = await(new CigramCatalogCall<List<CigramEpisode>>() {
            @Override public void run(CigramMediaCatalog.Callback<List<CigramEpisode>> cb) { catalog.allEpisodes(s, cb); }
        });
        StringBuilder order = new StringBuilder();
        for (CigramEpisode e : all) order.append(e.season).append('x').append(e.number).append(' ');
        assertEquals("1x1 1x2 1x3 2x1 2x2 3x1 3x2 0x0 0x-1 ", order.toString());
        CigramEpisode last1 = CigramEpisode.find(all, 1, 3);
        assertEquals("2:1", CigramEpisode.next(all, last1).key());
        assertEquals("1:3", CigramEpisode.previous(all, CigramEpisode.find(all, 2, 1)).key());
    }

    @Test public void seasonsAreCachedSoTheServerIsAskedOnce() throws Exception {
        seriesFixture();
        final CigramMediaRef s = breakingSeries();
        for (int i = 0; i < 3; i++) {
            await(new CigramCatalogCall<List<CigramSeason>>() {
                @Override public void run(CigramMediaCatalog.Callback<List<CigramSeason>> cb) { catalog.seasons(s, cb); }
            });
        }
        assertEquals(1, server.hits("/3/tv/1396"));
    }

    @Test public void detailsAddAlternativeTitlesAndTheImdbId() throws Exception {
        server.route("/3/movie/438631", TestServer.Reply.text("application/json",
                "{\"id\":438631,\"imdb_id\":\"tt1160419\",\"runtime\":155,\"vote_average\":7.8,"
                        + "\"alternative_titles\":{\"titles\":[{\"title\":\"Dune: Part One\"},{\"title\":\"ديون\"}]}}"));
        final CigramMediaRef ref = new CigramMediaRef.Builder().kind(CigramMediaRef.MOVIE).tmdbId("438631").title("كثيب").year(2021).build();
        CigramMediaRef full = await(new CigramCatalogCall<CigramMediaRef>() {
            @Override public void run(CigramMediaCatalog.Callback<CigramMediaRef> cb) { catalog.details(ref, cb); }
        });
        assertEquals("tt1160419", full.imdbId);
        assertEquals(155, full.runtimeMin);
        assertTrue(full.altTitles.contains("Dune: Part One"));
        assertTrue(full.altTitles.contains("ديون"));
        assertEquals(ref.catalogId, full.catalogId);
    }

    @Test public void libraryEpisodesAreGroupedBySeasonAndNumberedWithoutDuplicates() throws Exception {
        libraryItems.add(obj("{\"id\":\"s9\",\"type\":\"series\",\"title\":\"مسلسل\",\"episodes\":["
                + "{\"season\":\"2\",\"episode\":\"1\",\"title\":\"b1\",\"video\":\"https://x/s2e1.mp4\"},"
                + "{\"season\":\"1\",\"episode\":\"2\",\"title\":\"a2\",\"video\":\"https://x/s1e2.mp4\"},"
                + "{\"season\":\"1\",\"episode\":\"1\",\"title\":\"a1\",\"video\":\"https://x/s1e1.mp4\"},"
                + "{\"season\":\"1\",\"episode\":\"1\",\"title\":\"a1-dup\",\"video\":\"https://x/dup.mp4\"}]}"));
        final CigramMediaRef ref = CigramLibraryItems.toRef(libraryItems.get(0));
        List<CigramEpisode> all = await(new CigramCatalogCall<List<CigramEpisode>>() {
            @Override public void run(CigramMediaCatalog.Callback<List<CigramEpisode>> cb) { catalog.allEpisodes(ref, cb); }
        });
        assertEquals(3, all.size());
        assertEquals("a1", all.get(0).title);
        assertEquals("a2", all.get(1).title);
        assertEquals("b1", all.get(2).title);
        assertEquals("https://x/s1e1.mp4", all.get(0).libraryVideo);
        assertNotNull(ref.libraryId);
    }
}
