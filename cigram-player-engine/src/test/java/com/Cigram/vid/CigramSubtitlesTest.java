package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.List;

public class CigramSubtitlesTest {

    private static byte[] utf8(String s) { return s.getBytes(Charset.forName("UTF-8")); }

    private static final String ARABIC_SRT =
            "1\r\n00:00:01,000 --> 00:00:03,500\r\nمرحباً بكم في سيغرام!\r\n\r\n"
                    + "2\r\n00:00:04,000 --> 00:00:06,000\r\n<i>أين كنتَ البارحة؟</i>\r\nسؤالٌ مهمّ.\r\n\r\n"
                    + "3\r\n00:01:02,250 --> 00:01:05,000\r\nنعم، (حسناً) — لنذهب...\r\n";

    @Test public void arabicUtf8SrtParsesWithExactTimesAndText() throws Exception {
        CigramSubtitles.Track t = CigramSubtitles.parse(utf8(ARABIC_SRT));
        assertEquals("srt", t.format);
        assertEquals("UTF-8", t.charset);
        assertEquals(3, t.cues.size());
        assertEquals(1000, t.cues.get(0).startMs);
        assertEquals(3500, t.cues.get(0).endMs);
        assertEquals("مرحباً بكم في سيغرام!", t.cues.get(0).text);
        assertEquals("أين كنتَ البارحة؟\nسؤالٌ مهمّ.", t.cues.get(1).text);
        assertEquals(62250, t.cues.get(2).startMs);
        assertTrue(t.rtl);
    }

    @Test public void italicTagBecomesAStyleRangeNotText() throws Exception {
        CigramSubtitles.Track t = CigramSubtitles.parse(utf8(ARABIC_SRT));
        CigramSubtitles.Cue c = t.cues.get(1);
        assertEquals(1, c.spans.size());
        assertEquals(CigramSubtitles.SPAN_ITALIC, c.spans.get(0)[0]);
        assertEquals("أين كنتَ البارحة؟", c.text.substring(c.spans.get(0)[1], c.spans.get(0)[2]));
    }

    @Test public void utf8BomIsRemoved() throws Exception {
        byte[] body = utf8(ARABIC_SRT);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        CigramSubtitles.Track t = CigramSubtitles.parse(withBom);
        assertEquals("مرحباً بكم في سيغرام!", t.cues.get(0).text);
    }

    @Test public void legacyWindows1256ArabicFileIsDecodedCorrectly() throws Exception {
        String text = "1\n00:00:01,000 --> 00:00:03,000\nمرحبا بكم في المسلسل\n\n2\n00:00:04,000 --> 00:00:05,000\nشكرا لكم\n";
        byte[] legacy = text.getBytes(Charset.forName("windows-1256"));
        CigramSubtitles.Track t = CigramSubtitles.parse(legacy);
        assertEquals("windows-1256", t.charset);
        assertEquals("مرحبا بكم في المسلسل", t.cues.get(0).text);
        assertEquals("شكرا لكم", t.cues.get(1).text);
    }

    @Test public void latin1FileIsReadAsWindows1252() throws Exception {
        String text = "1\n00:00:01,000 --> 00:00:03,000\nCafé crème, naïve\n";
        CigramSubtitles.Track t = CigramSubtitles.parse(text.getBytes(Charset.forName("windows-1252")));
        assertEquals("windows-1252", t.charset);
        assertEquals("Café crème, naïve", t.cues.get(0).text);
    }

    @Test public void utf16WithBomIsAccepted() throws Exception {
        byte[] body = ("1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n").getBytes(Charset.forName("UTF-16LE"));
        byte[] withBom = new byte[body.length + 2];
        withBom[0] = (byte) 0xFF; withBom[1] = (byte) 0xFE;
        System.arraycopy(body, 0, withBom, 2, body.length);
        CigramSubtitles.Track t = CigramSubtitles.parse(withBom);
        assertEquals("مرحبا", t.cues.get(0).text);
    }

    @Test public void webVttWithHeaderNotesSettingsAndShortTimestampsParses() throws Exception {
        String vtt = "WEBVTT - title\n\nNOTE a comment\n\nSTYLE\n::cue { color: red }\n\n"
                + "intro\n00:01.000 --> 00:03.000 align:middle line:90%\n<v Ali>أهلاً</v> <b>بك</b>\n\n"
                + "01:00:00.500 --> 01:00:02.000\nثانياً &amp; أخيراً &lt;3\n";
        CigramSubtitles.Track t = CigramSubtitles.parse(utf8(vtt));
        assertEquals("vtt", t.format);
        assertEquals(2, t.cues.size());
        assertEquals(1000, t.cues.get(0).startMs);
        assertEquals("أهلاً بك", t.cues.get(0).text);
        assertEquals(3600500, t.cues.get(1).startMs);
        assertEquals("ثانياً & أخيراً <3", t.cues.get(1).text);
    }

    @Test public void srtWithDotAndVttWithCommaAreBothAccepted() throws Exception {
        CigramSubtitles.Track a = CigramSubtitles.parse(utf8("1\n00:00:01.500 --> 00:00:02.000\nx\n"));
        CigramSubtitles.Track b = CigramSubtitles.parse(utf8("WEBVTT\n\n00:00:01,500 --> 00:00:02,000\nx\n"));
        assertEquals(1500, a.cues.get(0).startMs);
        assertEquals(1500, b.cues.get(0).startMs);
    }

    @Test public void topPositionTagSetsTheTopFlagAndIsNotShown() throws Exception {
        CigramSubtitles.Track t = CigramSubtitles.parse(utf8("1\n00:00:01,000 --> 00:00:02,000\n{\\an8}فوق\n"));
        assertTrue(t.cues.get(0).top);
        assertEquals("فوق", t.cues.get(0).text);
    }

    @Test public void invalidTimingsAreSkippedWithAWarningButGoodCuesSurvive() throws Exception {
        String srt = "1\n00:00:05,000 --> 00:00:04,000\nreversed\n\n2\n00:00:06,000 --> 00:00:07,000\nok\n";
        CigramSubtitles.Track t = CigramSubtitles.parse(utf8(srt));
        assertEquals(1, t.cues.size());
        assertEquals("ok", t.cues.get(0).text);
        assertFalse(t.warnings.isEmpty());
    }

    @Test public void outOfOrderCuesAreSorted() throws Exception {
        String srt = "1\n00:00:10,000 --> 00:00:11,000\nlate\n\n2\n00:00:01,000 --> 00:00:02,000\nearly\n";
        CigramSubtitles.Track t = CigramSubtitles.parse(utf8(srt));
        assertEquals("early", t.cues.get(0).text);
        assertEquals("late", t.cues.get(1).text);
    }

    private static void assertRejected(byte[] data) {
        try {
            CigramSubtitles.parse(data);
            fail("should have been rejected");
        } catch (CigramEngineException e) {
            assertEquals(CigramEngineException.PARSE, e.kind);
            assertTrue(e.getMessage().length() > 0);
        }
    }

    @Test public void invalidFilesAreRejectedWithAMessage() {
        assertRejected(new byte[0]);
        assertRejected(utf8("just some text without any timing"));
        assertRejected(utf8("<!DOCTYPE html><html><body>404 Not Found</body></html>"));
        assertRejected(utf8("{\"error\":\"not found\"}"));
        assertRejected(new byte[]{0x00, 0x01, 0x02, 0x03, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01});
        assertRejected(utf8("1\n00:00:05,000 --> 00:00:04,000\nonly a reversed cue\n"));
    }

    @Test public void oversizedFileIsRejectedBeforeParsing() {
        assertRejected(new byte[CigramSubtitles.MAX_BYTES + 1]);
    }

    @Test public void srtToWebVttConversionIsValidAndLossless() throws Exception {
        CigramSubtitles.Track srt = CigramSubtitles.parse(utf8(ARABIC_SRT));
        String vtt = CigramSubtitles.toWebVtt(srt);
        assertTrue(vtt.startsWith("WEBVTT\n\n"));
        assertTrue(vtt.contains("00:00:01.000 --> 00:00:03.500"));
        assertTrue(vtt.contains("00:01:02.250 --> 00:01:05.000"));
        assertFalse("SRT comma must not survive", vtt.contains(",000 -->"));
        assertTrue(vtt.contains("<i>أين كنتَ البارحة؟</i>"));
        // reading the converted document gives the same cues
        CigramSubtitles.Track back = CigramSubtitles.parse(CigramSubtitles.utf8(vtt));
        assertEquals("vtt", back.format);
        assertEquals(srt.cues.size(), back.cues.size());
        for (int i = 0; i < srt.cues.size(); i++) {
            assertEquals(srt.cues.get(i).startMs, back.cues.get(i).startMs);
            assertEquals(srt.cues.get(i).endMs, back.cues.get(i).endMs);
            assertEquals(srt.cues.get(i).text, back.cues.get(i).text);
        }
    }

    @Test public void conversionEscapesMarkupCharactersAndArrows() throws Exception {
        CigramSubtitles.Track t = CigramSubtitles.parse(utf8("1\n00:00:01,000 --> 00:00:02,000\na &lt; b &amp; c --&gt; d\n"));
        String vtt = CigramSubtitles.toWebVtt(t);
        assertTrue(vtt.contains("a &lt; b &amp; c -&gt; d") || vtt.contains("a &lt; b &amp; c --&gt; d"));
        CigramSubtitles.Track back = CigramSubtitles.parse(CigramSubtitles.utf8(vtt));
        assertEquals(1, back.cues.size());
    }

    @Test public void activeAtFindsOverlappingCuesAndGaps() throws Exception {
        String srt = "1\n00:00:01,000 --> 00:00:05,000\nlong\n\n2\n00:00:02,000 --> 00:00:03,000\nshort\n\n3\n00:00:10,000 --> 00:00:11,000\nlater\n";
        CigramSubtitles.Track t = CigramSubtitles.parse(utf8(srt));
        assertEquals(0, t.activeAt(500).size());
        assertEquals(1, t.activeAt(1500).size());
        List<CigramSubtitles.Cue> both = t.activeAt(2500);
        assertEquals(2, both.size());
        assertEquals("long", both.get(0).text);
        assertEquals(1, t.activeAt(4000).size());
        assertEquals(0, t.activeAt(6000).size());
        assertEquals("later", t.activeAt(10500).get(0).text);
        assertEquals(0, t.activeAt(11000).size());
    }

    @Test public void shiftMovesEveryCueAndNeverGoesNegative() throws Exception {
        CigramSubtitles.Track t = CigramSubtitles.parse(utf8(ARABIC_SRT)).shifted(-2000);
        assertEquals(0, t.cues.get(0).startMs);
        assertEquals(1500, t.cues.get(0).endMs);
        assertEquals(60250, t.cues.get(2).startMs);
    }

    @Test public void rtlCueGetsDirectionMarksOnlyOnItsRtlLines() throws Exception {
        CigramSubtitles.Track t = CigramSubtitles.parse(utf8("1\n00:00:01,000 --> 00:00:02,000\nمرحبا!\nHello there\n"));
        String shown = t.cues.get(0).displayText();
        String[] lines = shown.split("\n");
        assertEquals('‏', lines[0].charAt(0));
        assertEquals('‏', lines[0].charAt(lines[0].length() - 1));
        assertEquals("Hello there", lines[1]);
    }

    @Test public void manyCuesPerformAndKeepOrder() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            long s = i * 2000L;
            sb.append(i + 1).append('\n').append(CigramSubtitles.vttTime(s).replace('.', ',')).append(" --> ")
                    .append(CigramSubtitles.vttTime(s + 1500).replace('.', ',')).append("\nline ").append(i).append("\n\n");
        }
        long t0 = System.nanoTime();
        CigramSubtitles.Track t = CigramSubtitles.parse(utf8(sb.toString()));
        long ms = (System.nanoTime() - t0) / 1000000L;
        assertEquals(5000, t.cues.size());
        assertNotNull(t.activeAt(4999 * 2000L + 100));
        assertEquals("line 4999", t.activeAt(4999 * 2000L + 100).get(0).text);
        assertTrue("parsing 5000 cues took " + ms + " ms", ms < 3000);
    }

    // ---- remote fetch

    @Test public void remoteFetchRefusesUntrustedAndInsecureLinks() throws Exception {
        try {
            CigramSubtitles.fetch("https://example.com/a.srt", null, 0, new CigramCancel());
            fail();
        } catch (CigramEngineException e) { assertEquals(CigramEngineException.UNSUPPORTED, e.kind); }
        try {
            CigramSubtitles.fetch("http://example.com/a.srt", null, CigramSubtitles.TRUST_MANUAL, new CigramCancel());
            fail();
        } catch (CigramEngineException e) { assertEquals(CigramEngineException.UNSUPPORTED, e.kind); }
        try {
            CigramSubtitles.fetch("http://subtitles.invalid/a.srt", null, CigramSubtitles.TRUST_PROVIDER, new CigramCancel());
            fail();
        } catch (CigramEngineException e) {
            // allowed by policy for the owner's own links: it fails later, on the network, not on the policy
            assertTrue(e.kind != CigramEngineException.UNSUPPORTED);
        }
        try {
            CigramSubtitles.fetch("file:///sdcard/a.srt", null, CigramSubtitles.TRUST_PROVIDER, new CigramCancel());
            fail();
        } catch (CigramEngineException e) { assertEquals(CigramEngineException.UNSUPPORTED, e.kind); }
    }

    @Test public void remoteFetchReadsAGoodFileAndRejectsPagesAndHugeFiles() throws Exception {
        TestServer s = new TestServer();
        try {
            s.route("/ar.srt", TestServer.Reply.ok("application/x-subrip", utf8(ARABIC_SRT)));
            s.route("/page.srt", TestServer.Reply.text("text/html", "<html>login</html>"));
            s.route("/video.srt", TestServer.Reply.ok("video/mp4", new byte[100]));
            s.route("/huge.srt", TestServer.Reply.ok("text/plain", new byte[CigramSubtitles.MAX_BYTES + 10]));
            s.route("/missing.srt", TestServer.Reply.code(404));
            CigramSubtitles.Track t = CigramSubtitles.fetch(s.base() + "/ar.srt", null, CigramSubtitles.TRUST_MANUAL, new CigramCancel());
            assertEquals(3, t.cues.size());
            for (String bad : new String[]{"/page.srt", "/video.srt", "/huge.srt", "/missing.srt"}) {
                try {
                    CigramSubtitles.fetch(s.base() + bad, null, CigramSubtitles.TRUST_PROVIDER, new CigramCancel());
                    fail(bad + " should be rejected");
                } catch (CigramEngineException e) {
                    assertTrue(bad, e.getMessage().length() > 0);
                }
            }
        } finally { s.stop(); }
    }
}
