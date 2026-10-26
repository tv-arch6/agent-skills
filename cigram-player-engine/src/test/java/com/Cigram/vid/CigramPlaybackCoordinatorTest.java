package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CigramPlaybackCoordinatorTest {
    private ExecutorService io;

    @Before public void setUp() { io = Executors.newCachedThreadPool(); }

    @After public void tearDown() { io.shutdownNow(); }

    // ---------------------------------------------------------------- scripted provider

    /** Returns sources "https://{id}/s{n}/{season}-{episode}.m3u8"; links containing "bad" fail validation. */
    static final class Fake extends CigramBaseProvider {
        final AtomicInteger resolves = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        volatile int count = 1;
        volatile int badFirst;               // how many of the first sources are invalid
        volatile boolean throwOnResolve;
        volatile long sleepMs;
        volatile int status = INTEGRATED;
        volatile String note = "";

        Fake(String id, int priority) { super(id, id, priority); }

        @Override public int status() { return status; }
        @Override public String statusNote() { return note; }
        @Override public boolean supports(CigramMediaRequest r) { return true; }

        @Override protected List<CigramPlaybackSource> doResolve(CigramMediaRequest r, CigramCancel cancel) throws CigramEngineException {
            resolves.incrementAndGet();
            if (sleepMs > 0) {
                long end = System.currentTimeMillis() + sleepMs;
                while (System.currentTimeMillis() < end) {
                    if (cancel.isCancelled()) { cancels.incrementAndGet(); throw CigramEngineException.cancelled(); }
                    try { Thread.sleep(10); } catch (InterruptedException e) { cancels.incrementAndGet(); throw CigramEngineException.cancelled(); }
                }
            }
            if (throwOnResolve) throw new CigramEngineException(CigramEngineException.NETWORK, "تعذر الاتصال بالمصدر.");
            ArrayList<CigramPlaybackSource> out = new ArrayList<CigramPlaybackSource>();
            for (int i = 0; i < count; i++) {
                CigramPlaybackSource s = new CigramPlaybackSource();
                s.playbackUri = "https://" + id + ".example/" + (i < badFirst ? "bad" : "good") + i + "/" + r.season + "-" + r.episode + ".m3u8";
                s.quality = "720p";
                out.add(s);
            }
            return out;
        }

        @Override public CigramSourceValidator.Result validate(CigramPlaybackSource s, CigramCancel c) {
            if (s.playbackUri.contains("/bad")) return CigramSourceValidator.Result.unavailable("الرابط يعيد صفحة ويب وليس فيديو.");
            return CigramSourceValidator.Result.ok("hls", "application/x-mpegURL");
        }

        @Override public void cancel(String requestId) { cancels.incrementAndGet(); super.cancel(requestId); }
    }

    /** Collects everything the coordinator tells a listener. */
    static final class Probe implements CigramPlaybackCoordinator.Listener {
        final CountDownLatch done = new CountDownLatch(1);
        final List<String> states = new ArrayList<String>();
        volatile CigramPlaybackSource chosen;
        volatile List<CigramPlaybackSource> alternates;
        volatile String failMessage;
        volatile List<CigramPlaybackCoordinator.Report> reports;
        volatile boolean retryable;
        final AtomicInteger readyCount = new AtomicInteger();
        final AtomicInteger failCount = new AtomicInteger();

        @Override public synchronized void onState(CigramMediaRequest r, int state, String message) { states.add(message); }
        @Override public void onReady(CigramMediaRequest r, CigramPlaybackSource c, List<CigramPlaybackSource> alts) {
            chosen = c; alternates = alts; readyCount.incrementAndGet(); done.countDown();
        }
        @Override public void onFailed(CigramMediaRequest r, String m, List<CigramPlaybackCoordinator.Report> rep, boolean retry) {
            failMessage = m; reports = rep; retryable = retry; failCount.incrementAndGet(); done.countDown();
        }
        boolean await() throws InterruptedException { return done.await(10, TimeUnit.SECONDS); }
    }

    private static CigramMediaRef series() {
        return new CigramMediaRef.Builder().kind(CigramMediaRef.SERIES).tmdbId("1396").title("Show").year(2008).build();
    }

    private static CigramMediaRequest ep(int season, int number) {
        return CigramMediaRequest.episode(series(), new CigramEpisode("", season, number, 0, "", "", "", "", 0, "", ""), null);
    }

    private static CigramMediaRequest movie() {
        return CigramMediaRequest.movie(new CigramMediaRef.Builder().kind(CigramMediaRef.MOVIE).tmdbId("603").title("Film").year(1999).build(), null);
    }

    private CigramPlaybackCoordinator coordinator(CigramSourceProvider... ps) {
        ArrayList<CigramSourceProvider> list = new ArrayList<CigramSourceProvider>();
        for (CigramSourceProvider p : ps) list.add(p);
        return new CigramPlaybackCoordinator(list, io, CigramPoster.DIRECT).timeouts(1500, 3000);
    }

    // ---------------------------------------------------------------- tests

    @Test public void firstProviderFailsSecondProviderSucceeds() throws Exception {
        Fake a = new Fake("a", 10); a.badFirst = 1;
        Fake b = new Fake("b", 20);
        Probe p = new Probe();
        coordinator(a, b).play(ep(1, 2), p);
        assertTrue(p.await());
        assertEquals(1, p.readyCount.get());
        assertEquals("b", p.chosen.providerId);
        assertEquals(CigramPlaybackSource.AVAILABLE, p.chosen.availability);
        assertTrue(p.chosen.isPlayable);
        assertTrue(p.chosen.playbackUri.contains("1-2.m3u8"));
        assertFalse(p.states.isEmpty());
    }

    @Test public void aProviderThatThrowsDoesNotStopTheNextOne() throws Exception {
        Fake a = new Fake("a", 10); a.throwOnResolve = true;
        Fake b = new Fake("b", 20);
        Probe p = new Probe();
        coordinator(a, b).play(movie(), p);
        assertTrue(p.await());
        assertEquals("b", p.chosen.providerId);
    }

    @Test public void aSlowProviderIsGivenUpOnAndItsWorkIsCancelled() throws Exception {
        Fake a = new Fake("a", 10); a.sleepMs = 5000;
        Fake b = new Fake("b", 20);
        Probe p = new Probe();
        long t0 = System.currentTimeMillis();
        coordinator(a, b).play(movie(), p);
        assertTrue(p.await());
        assertEquals("b", p.chosen.providerId);
        assertTrue("took " + (System.currentTimeMillis() - t0), System.currentTimeMillis() - t0 < 4000);
        Thread.sleep(200);
        assertTrue("the slow provider must be told to stop", a.cancels.get() >= 1);
    }

    @Test public void providersThatAreNotIntegratedAreReportedAndNothingIsInvented() throws Exception {
        Fake a = new Fake("a", 10); a.status = CigramSourceProvider.NEEDS_API; a.note = "لا توجد واجهة API";
        Fake b = new Fake("b", 20); b.status = CigramSourceProvider.NEEDS_API; b.note = "لا توجد واجهة API";
        Probe p = new Probe();
        coordinator(a, b).play(ep(1, 1), p);
        assertTrue(p.await());
        assertNull(p.chosen);
        assertEquals(0, a.resolves.get());
        assertEquals(0, b.resolves.get());
        assertEquals(2, p.reports.size());
        assertEquals(CigramPlaybackSource.NOT_INTEGRATED, p.reports.get(0).status);
        assertTrue(p.failMessage.contains("لا يوجد مزود"));
    }

    @Test public void whenEverySourceIsBadTheViewerGetsAClearFailureWithReasons() throws Exception {
        Fake a = new Fake("a", 10); a.badFirst = 5; a.count = 2;
        Fake b = new Fake("b", 20); b.badFirst = 5; b.count = 1;
        Probe p = new Probe();
        coordinator(a, b).play(ep(2, 4), p);
        assertTrue(p.await());
        assertNull(p.chosen);
        assertEquals(1, p.failCount.get());
        assertTrue(p.failMessage, p.failMessage.contains("لا توجد مصادر صالحة لهذه الحلقة"));
        assertEquals(3, p.reports.size());
        assertTrue(p.reports.get(0).note.contains("صفحة ويب"));
    }

    @Test public void aTimeoutMakesTheFailureRetryable() throws Exception {
        Fake a = new Fake("a", 10); a.sleepMs = 5000;
        Probe p = new Probe();
        coordinator(a).play(movie(), p);
        assertTrue(p.await());
        assertTrue(p.retryable);
    }

    @Test public void tappingTwiceOnTheSameThingRunsOneSearch() throws Exception {
        Fake a = new Fake("a", 10); a.sleepMs = 300;
        CigramPlaybackCoordinator c = coordinator(a);
        Probe first = new Probe(), second = new Probe();
        CigramMediaRequest r1 = ep(1, 1), r2 = ep(1, 1);
        String id1 = c.play(r1, first);
        String id2 = c.play(r2, second);
        assertEquals("the second tap is served by the first run", id1, id2);
        assertTrue(first.await());
        assertTrue(second.await());
        assertEquals(1, a.resolves.get());
        assertEquals(1, first.readyCount.get());
        assertEquals(1, second.readyCount.get());
    }

    @Test public void aNewRequestForSomethingElseCancelsTheOldOneAndItsAnswerIsDropped() throws Exception {
        Fake a = new Fake("a", 10); a.sleepMs = 400;
        CigramPlaybackCoordinator c = coordinator(a);
        Probe old = new Probe(), fresh = new Probe();
        c.play(ep(1, 1), old);
        Thread.sleep(80);
        c.play(ep(1, 2), fresh);
        assertTrue(fresh.await());
        assertTrue(fresh.chosen.playbackUri.contains("1-2.m3u8"));
        Thread.sleep(600);
        assertEquals("the old request must never answer", 0, old.readyCount.get() + old.failCount.get());
    }

    @Test public void cancelStopsEverythingAndNothingIsDelivered() throws Exception {
        Fake a = new Fake("a", 10); a.sleepMs = 1000;
        CigramPlaybackCoordinator c = coordinator(a);
        Probe p = new Probe();
        c.play(movie(), p);
        Thread.sleep(80);
        c.cancel();
        Thread.sleep(1300);
        assertEquals(0, p.readyCount.get() + p.failCount.get());
        assertEquals(CigramPlaybackCoordinator.IDLE, c.state());
    }

    @Test public void afterReadyANewTapStartsAFreshRun() throws Exception {
        Fake a = new Fake("a", 10);
        CigramPlaybackCoordinator c = coordinator(a);
        Probe p1 = new Probe();
        c.play(movie(), p1);
        assertTrue(p1.await());
        Probe p2 = new Probe();
        c.play(movie(), p2);
        assertTrue(p2.await());
        assertEquals("links expire, so a later tap resolves again", 2, a.resolves.get());
    }

    @Test public void aFailedSourceIsReplacedByAnotherSourceOfTheSameEpisode() throws Exception {
        Fake a = new Fake("a", 10); a.count = 2;       // two good sources for the episode
        Fake b = new Fake("b", 20);
        CigramPlaybackCoordinator c = coordinator(a, b);
        CigramMediaRequest req = ep(3, 5);
        Probe p = new Probe();
        c.play(req, p);
        assertTrue(p.await());
        CigramPlaybackSource first = p.chosen;
        assertEquals("a", first.providerId);
        assertEquals(1, p.alternates.size());          // the second "a" source waits as an alternate

        Probe retry = new Probe();
        c.reportPlaybackFailure(req, first, "فشل التشغيل", retry);
        assertTrue(retry.await());
        assertNotNull(retry.chosen);
        assertFalse(retry.chosen.sourceKey().equals(first.sourceKey()));
        assertTrue("same episode", retry.chosen.playbackUri.contains("3-5.m3u8"));
        assertEquals(CigramPlaybackSource.AVAILABLE, retry.chosen.availability);
    }

    @Test public void fallbackNeverMovesToADifferentEpisode() throws Exception {
        Fake a = new Fake("a", 10);                    // exactly one source per episode
        CigramPlaybackCoordinator c = coordinator(a);
        CigramMediaRequest req = ep(1, 1);
        Probe p = new Probe();
        c.play(req, p);
        assertTrue(p.await());
        Probe retry = new Probe();
        c.reportPlaybackFailure(req, p.chosen, "x", retry);
        assertTrue(retry.await());
        assertNull("no other source of episode 1 exists: it must fail, not play episode 2", retry.chosen);
        assertEquals(1, retry.failCount.get());
    }

    @Test public void aSourceStampedForAnotherEpisodeIsIgnored() throws Exception {
        // A provider that does not use CigramBaseProvider and returns the wrong episode's source.
        CigramSourceProvider rogue = new CigramSourceProvider() {
            @Override public String id() { return "rogue"; }
            @Override public String displayName() { return "rogue"; }
            @Override public int priority() { return 1; }
            @Override public int status() { return INTEGRATED; }
            @Override public String statusNote() { return ""; }
            @Override public boolean supports(CigramMediaRequest r) { return true; }
            @Override public List<String> search(String t, String c, int s, int e, CigramCancel x) { return new ArrayList<String>(); }
            @Override public List<CigramPlaybackSource> resolve(CigramMediaRequest r, CigramCancel x) {
                CigramPlaybackSource s = new CigramPlaybackSource();
                s.providerId = "rogue"; s.playbackUri = "https://rogue.example/ep2.m3u8";
                s.requestKey = ep(1, 2).identityKey();
                ArrayList<CigramPlaybackSource> l = new ArrayList<CigramPlaybackSource>();
                l.add(s);
                return l;
            }
            @Override public CigramSourceValidator.Result validate(CigramPlaybackSource s, CigramCancel x) { return CigramSourceValidator.Result.ok("hls", "x"); }
            @Override public void cancel(String id) { }
        };
        Probe p = new Probe();
        coordinator(rogue).play(ep(1, 1), p);
        assertTrue(p.await());
        assertNull(p.chosen);
    }

    @Test public void fallbacksAreCappedSoItNeverLoopsForever() throws Exception {
        Fake a = new Fake("a", 10); a.count = 12; a.badFirst = 12;
        Probe p = new Probe();
        coordinator(a).play(movie(), p);
        assertTrue(p.await());
        assertNull(p.chosen);
        assertTrue(p.failMessage, p.failMessage.contains("فشلت جميع المصادر"));
    }

    @Test public void failureReportForSomethingTheViewerLeftIsAnsweredNotSilent() throws Exception {
        Fake a = new Fake("a", 10);
        CigramPlaybackCoordinator c = coordinator(a);
        CigramMediaRequest req = ep(1, 1);
        Probe p = new Probe();
        c.play(req, p);
        assertTrue(p.await());
        c.cancel();
        Probe late = new Probe();
        c.reportPlaybackFailure(req, p.chosen, "x", late);
        assertTrue(late.await());
        assertTrue(late.retryable);
    }

    @Test public void listSourcesValidatesEveryProviderForTheChooser() throws Exception {
        Fake a = new Fake("a", 10); a.count = 2; a.badFirst = 1;
        Fake b = new Fake("b", 20);
        Fake c3 = new Fake("c", 30); c3.status = CigramSourceProvider.NEEDS_API; c3.note = "needs api";
        CigramPlaybackCoordinator c = coordinator(a, b, c3);
        final CountDownLatch latch = new CountDownLatch(1);
        final List<CigramPlaybackSource> got = new ArrayList<CigramPlaybackSource>();
        final List<CigramPlaybackCoordinator.Report> reps = new ArrayList<CigramPlaybackCoordinator.Report>();
        c.listSources(ep(1, 1), new CigramPlaybackCoordinator.ListCallback() {
            @Override public void onSources(CigramMediaRequest r, List<CigramPlaybackSource> s, List<CigramPlaybackCoordinator.Report> rr) {
                got.addAll(s); reps.addAll(rr); latch.countDown();
            }
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(3, got.size());
        int available = 0, unavailable = 0;
        for (CigramPlaybackSource s : got) {
            if (s.availability == CigramPlaybackSource.AVAILABLE) available++;
            if (s.availability == CigramPlaybackSource.UNAVAILABLE) { unavailable++; assertTrue(s.failureReason.length() > 0); }
        }
        assertEquals(2, available);
        assertEquals(1, unavailable);
        assertEquals(1, reps.size());       // only the not-integrated provider
    }

    @Test public void playSpecificValidatesTheChosenSourceBeforeUsingIt() throws Exception {
        Fake a = new Fake("a", 10); a.count = 2; a.badFirst = 1;
        CigramPlaybackCoordinator c = coordinator(a);
        CigramMediaRequest req = movie();
        Probe p = new Probe();
        c.play(req, p);
        assertTrue(p.await());
        CigramPlaybackSource bad = new CigramPlaybackSource();
        bad.providerId = "a";
        bad.playbackUri = "https://a.example/bad9/x.m3u8";
        bad.requestKey = req.identityKey();
        Probe pick = new Probe();
        c.playSpecific(req, bad, pick);
        assertTrue(pick.await());
        assertNull("a bad link chosen by hand is still checked", pick.chosen);
        assertEquals(1, pick.failCount.get());
    }
}
