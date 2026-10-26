package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.Charset;

public class CigramSourceValidatorTest {
    private TestServer server;
    private final CigramSourceValidator validator = new CigramSourceValidator().timeouts(2000, 2000);

    @Before public void start() throws Exception { server = new TestServer(); }

    @After public void stop() { server.stop(); }

    // ---------------------------------------------------------------- media bytes

    static byte[] mp4(String brand) {
        byte[] b = new byte[3000];
        b[3] = 0x18;
        System.arraycopy("ftyp".getBytes(), 0, b, 4, 4);
        System.arraycopy(brand.getBytes(), 0, b, 8, 4);
        return b;
    }

    static byte[] ts(int packets) {
        byte[] b = new byte[packets * 188];
        for (int i = 0; i < packets; i++) b[i * 188] = 0x47;
        return b;
    }

    static byte[] mkv(String doc) {
        byte[] b = new byte[600];
        b[0] = 0x1A; b[1] = 0x45; b[2] = (byte) 0xDF; b[3] = (byte) 0xA3;
        System.arraycopy(doc.getBytes(), 0, b, 20, doc.length());
        return b;
    }

    private CigramPlaybackSource source(String path) {
        CigramPlaybackSource s = new CigramPlaybackSource();
        s.providerId = "t";
        s.playbackUri = server.base() + path;
        return s;
    }

    private CigramSourceValidator.Result check(String path) {
        return validator.validate(source(path), new CigramCancel());
    }

    private void assertOk(CigramSourceValidator.Result r, String kind) {
        assertTrue("expected ok but: " + r.reason, r.ok);
        assertEquals(kind, r.kind);
        assertEquals(CigramPlaybackSource.AVAILABLE, r.status);
        assertTrue(r.mime.length() > 0);
    }

    private void assertFail(CigramSourceValidator.Result r, int status, String reasonPart) {
        assertFalse("expected a failure", r.ok);
        assertEquals(status, r.status);
        assertTrue("reason was: " + r.reason, r.reason.contains(reasonPart));
    }

    // ---------------------------------------------------------------- progressive files

    @Test public void mp4IsAccepted() {
        server.route("/a.mp4", TestServer.Reply.ok("video/mp4", mp4("isom")));
        assertOk(check("/a.mp4"), "mp4");
    }

    @Test public void mp4IsAcceptedEvenWhenTheServerIgnoresRange() {
        TestServer.Reply r = TestServer.Reply.ok("video/mp4", mp4("mp42"));
        r.honourRange = false;
        server.route("/big.mp4", r);
        assertOk(check("/big.mp4"), "mp4");
    }

    @Test public void mkvAndWebmAreAccepted() {
        server.route("/a.mkv", TestServer.Reply.ok("video/x-matroska", mkv("matroska")));
        server.route("/a.webm", TestServer.Reply.ok("video/webm", mkv("webm")));
        assertOk(check("/a.mkv"), "mkv");
        assertOk(check("/a.webm"), "webm");
    }

    @Test public void transportStreamIsAccepted() {
        server.route("/a.ts", TestServer.Reply.ok("video/mp2t", ts(60)));
        assertOk(check("/a.ts"), "ts");
    }

    @Test public void audioOnlyFilesAreRejected() {
        server.route("/a.m4a", TestServer.Reply.ok("audio/mp4", mp4("M4A ")));
        byte[] mp3 = new byte[2000];
        mp3[0] = 'I'; mp3[1] = 'D'; mp3[2] = '3';
        server.route("/a.mp3", TestServer.Reply.ok("audio/mpeg", mp3));
        assertFail(check("/a.m4a"), CigramPlaybackSource.UNAVAILABLE, "صوت فقط");
        assertFail(check("/a.mp3"), CigramPlaybackSource.UNAVAILABLE, "صوت فقط");
    }

    @Test public void aWebPageBehindAVideoLinkIsRejectedWhateverItsContentType() {
        server.route("/page.mp4", TestServer.Reply.text("text/html; charset=utf-8", "<!doctype html><html><body>Please log in</body></html>"));
        server.route("/lying.mp4", TestServer.Reply.text("video/mp4", "<html><head></head><body>blocked</body></html>"));
        assertFail(check("/page.mp4"), CigramPlaybackSource.UNAVAILABLE, "صفحة ويب");
        assertFail(check("/lying.mp4"), CigramPlaybackSource.UNAVAILABLE, "صفحة ويب");
    }

    @Test public void jsonAndPlainTextAreNotVideo() {
        server.route("/j.mp4", TestServer.Reply.text("application/json", "{\"error\":\"expired\"}"));
        server.route("/t.mp4", TestServer.Reply.text("text/plain", "this is not a video file at all, just words"));
        assertFail(check("/j.mp4"), CigramPlaybackSource.UNAVAILABLE, "لا يشير إلى ملف فيديو");
        assertFail(check("/t.mp4"), CigramPlaybackSource.UNAVAILABLE, "لا يشير إلى ملف فيديو");
    }

    @Test public void httpErrorsAreExplainedAndAuthIsTold() {
        server.route("/gone.mp4", TestServer.Reply.code(404));
        server.route("/denied.mp4", TestServer.Reply.code(403));
        server.route("/busy.mp4", TestServer.Reply.code(503));
        assertFail(check("/gone.mp4"), CigramPlaybackSource.UNAVAILABLE, "غير موجود");
        assertFail(check("/denied.mp4"), CigramPlaybackSource.NEEDS_AUTH, "يرفض الوصول");
        CigramSourceValidator.Result busy = check("/busy.mp4");
        assertFail(busy, CigramPlaybackSource.UNAVAILABLE, "لا يستجيب");
        assertTrue(busy.retryable);
    }

    @Test public void redirectsAreFollowedAndLoopsAreStopped() {
        server.route("/start.mp4", TestServer.Reply.redirect(server.base() + "/real.mp4"));
        server.route("/real.mp4", TestServer.Reply.ok("video/mp4", mp4("isom")));
        server.route("/loop1.mp4", TestServer.Reply.redirect(server.base() + "/loop2.mp4"));
        server.route("/loop2.mp4", TestServer.Reply.redirect(server.base() + "/loop1.mp4"));
        assertOk(check("/start.mp4"), "mp4");
        assertFalse(check("/loop1.mp4").ok);
    }

    @Test public void slowServerTimesOutAsRetryable() {
        TestServer.Reply slow = TestServer.Reply.ok("video/mp4", mp4("isom"));
        slow.delayMs = 3500;
        server.route("/slow.mp4", slow);
        long t0 = System.currentTimeMillis();
        CigramSourceValidator.Result r = new CigramSourceValidator().timeouts(500, 500).validate(source("/slow.mp4"), new CigramCancel());
        assertFalse(r.ok);
        assertTrue(r.retryable);
        assertTrue("took " + (System.currentTimeMillis() - t0), System.currentTimeMillis() - t0 < 3000);
    }

    @Test public void sourceHeadersAreSentToTheProvider() {
        server.route("/h.mp4", TestServer.Reply.ok("video/mp4", mp4("isom")));
        CigramPlaybackSource s = source("/h.mp4");
        s.headers.put("Referer", "https://player.example/");
        assertTrue(validator.validate(s, new CigramCancel()).ok);
        assertEquals("https://player.example/", server.lastReferer.get("/h.mp4"));
    }

    @Test public void nonWebLinksAndFlaggedSourcesNeverReachTheNetwork() {
        CigramPlaybackSource js = new CigramPlaybackSource();
        js.playbackUri = "javascript:alert(1)";
        assertFalse(validator.validate(js, new CigramCancel()).ok);
        CigramPlaybackSource file = new CigramPlaybackSource();
        file.playbackUri = "file:///sdcard/a.mp4";
        assertFalse(validator.validate(file, new CigramCancel()).ok);
        CigramPlaybackSource embed = source("/embed");
        embed.markFailed(CigramPlaybackSource.EMBED_ONLY, "صفحة تضمين فقط");
        CigramSourceValidator.Result r = validator.validate(embed, new CigramCancel());
        assertEquals(CigramPlaybackSource.EMBED_ONLY, r.status);
        assertEquals(0, server.hits("/embed"));
    }

    // ---------------------------------------------------------------- HLS

    private static String media(String segment) {
        return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.000,\n" + segment + "\n#EXT-X-ENDLIST\n";
    }

    private void hls(String base, String segmentPath, TestServer.Reply segment) {
        server.route(base + "/index.m3u8", TestServer.Reply.text("application/vnd.apple.mpegurl",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nv1/play.m3u8\n"));
        server.route(base + "/v1/play.m3u8", TestServer.Reply.text("application/vnd.apple.mpegurl", media(segmentPath)));
        server.route(base + "/v1/" + segmentPath, segment);
    }

    @Test public void hlsMasterVariantAndSegmentAreAllChecked() {
        hls("/ok", "seg0.ts", TestServer.Reply.ok("video/mp2t", ts(20)));
        assertOk(check("/ok/index.m3u8"), "hls");
        assertEquals(1, server.hits("/ok/v1/seg0.ts"));
    }

    @Test public void hlsMediaPlaylistWithFragmentedMp4SegmentsIsAccepted() {
        server.route("/fm/p.m3u8", TestServer.Reply.text("application/x-mpegURL", media("s0.m4s")));
        byte[] seg = new byte[2000];
        seg[3] = 0x20;
        System.arraycopy("moof".getBytes(), 0, seg, 4, 4);
        server.route("/fm/s0.m4s", TestServer.Reply.ok("video/iso.segment", seg));
        assertOk(check("/fm/p.m3u8"), "hls");
    }

    @Test public void hlsWhoseSegmentsAreWebPagesIsRejected() {
        hls("/html", "seg0.ts", TestServer.Reply.text("text/html", "<html><body>forbidden</body></html>"));
        assertFail(check("/html/index.m3u8"), CigramPlaybackSource.UNAVAILABLE, "صفحة ويب");
    }

    @Test public void hlsWithMissingSegmentOrVariantIsRejected() {
        hls("/noseg", "seg0.ts", TestServer.Reply.code(404));
        assertFail(check("/noseg/index.m3u8"), CigramPlaybackSource.UNAVAILABLE, "مقاطع الفيديو لا تستجيب");
        server.route("/novar/index.m3u8", TestServer.Reply.text("application/x-mpegURL",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmissing.m3u8\n"));
        assertFail(check("/novar/index.m3u8"), CigramPlaybackSource.UNAVAILABLE, "قائمة الجودة لا تستجيب");
    }

    @Test public void hlsPlaylistWithoutSegmentsIsRejected() {
        server.route("/empty/p.m3u8", TestServer.Reply.text("application/x-mpegURL", "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-ENDLIST\n"));
        assertFail(check("/empty/p.m3u8"), CigramPlaybackSource.UNAVAILABLE, "لا تحتوي مقاطع");
    }

    @Test public void drmProtectedHlsIsReportedAsDrmNotBypassed() {
        server.route("/drm/p.m3u8", TestServer.Reply.text("application/x-mpegURL",
                "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\ns.ts\n"));
        assertFail(check("/drm/p.m3u8"), CigramPlaybackSource.DRM, "DRM");
        assertEquals(0, server.hits("/drm/s.ts"));
    }

    @Test public void aes128EncryptedSegmentsAreAcceptedBecauseExoPlayerDecryptsThem() {
        byte[] noise = new byte[2000];
        for (int i = 0; i < noise.length; i++) noise[i] = (byte) (i * 31 + 7);
        server.route("/aes/p.m3u8", TestServer.Reply.text("application/x-mpegURL",
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:6,\ns.ts\n"));
        server.route("/aes/s.ts", TestServer.Reply.ok("video/mp2t", noise));
        assertOk(check("/aes/p.m3u8"), "hls");
    }

    @Test public void hlsDetectedByContentEvenWithoutAnExtension() {
        hls("/noext", "seg0.ts", TestServer.Reply.ok("video/mp2t", ts(20)));
        server.route("/stream", TestServer.Reply.text("text/plain", media("noext/v1/seg0.ts")));
        server.route("/noext/v1/seg0.ts", TestServer.Reply.ok("video/mp2t", ts(20)));
        // "/stream" resolves its segment relative to itself: /noext/v1/seg0.ts
        assertOk(check("/stream"), "hls");
    }

    // ---------------------------------------------------------------- DASH

    private static String mpd(String adaptation) {
        return "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period>" + adaptation + "</Period></MPD>";
    }

    @Test public void dashWithVideoRepresentationIsAccepted() {
        server.route("/d/ok.mpd", TestServer.Reply.text("application/dash+xml",
                mpd("<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\"><Representation id=\"1\" bandwidth=\"1\"/></AdaptationSet>")));
        assertOk(check("/d/ok.mpd"), "dash");
    }

    @Test public void dashWithOnlyAudioOrWithContentProtectionIsRejected() {
        server.route("/d/audio.mpd", TestServer.Reply.text("application/dash+xml",
                mpd("<AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\"><Representation id=\"1\"/></AdaptationSet>")));
        server.route("/d/drm.mpd", TestServer.Reply.text("application/dash+xml",
                mpd("<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\"><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/><Representation id=\"1\"/></AdaptationSet>")));
        assertFail(check("/d/audio.mpd"), CigramPlaybackSource.UNAVAILABLE, "لا يحتوي مسار فيديو");
        assertFail(check("/d/drm.mpd"), CigramPlaybackSource.DRM, "DRM");
    }

    // ---------------------------------------------------------------- pure sniffing

    @Test public void sniffKindRecognisesTheFormatsByTheirFirstBytes() {
        byte[] flv = new byte[16];
        flv[0] = 'F'; flv[1] = 'L'; flv[2] = 'V'; flv[3] = 1;
        assertEquals("flv", CigramSourceValidator.sniffKind(flv, flv.length));
        assertEquals("hls", CigramSourceValidator.sniffKind("﻿#EXTM3U\n".getBytes(Charset.forName("UTF-8")), 12));
        assertEquals("html", CigramSourceValidator.sniffKind("  <HTML>".getBytes(), 8));
        assertEquals("json", CigramSourceValidator.sniffKind("[1,2]".getBytes(), 5));
        assertEquals("dash", CigramSourceValidator.sniffKind("<?xml version=\"1.0\"?><MPD>".getBytes(), 26));
        assertEquals("ss", CigramSourceValidator.sniffKind("<SmoothStreamingMedia>".getBytes(), 22));
        assertEquals("", CigramSourceValidator.sniffKind(new byte[0], 0));
        assertEquals("", CigramSourceValidator.sniffKind(new byte[]{(byte) 0x80, 0x01, (byte) 0xC3, 0x28, 0x02, 0x7F}, 6));
    }
}
