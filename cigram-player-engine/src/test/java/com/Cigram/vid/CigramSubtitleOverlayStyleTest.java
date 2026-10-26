package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.List;

/**
 * Glue test (the overlay class needs the ExoPlayer stubs on the class path, see verify/run-glue-tests.sh): the text the
 * overlay hands to the SubtitleView keeps its italic / bold ranges on the right words after right-to-left marks are
 * added around Arabic lines. The Spannable itself needs Android's runtime, so the pure layout step is what is tested.
 */
public class CigramSubtitleOverlayStyleTest {

    private static CigramSubtitles.Cue cue(String srtText) throws Exception {
        String srt = "1\n00:00:01,000 --> 00:00:02,000\n" + srtText + "\n";
        return CigramSubtitles.parse(srt.getBytes(Charset.forName("UTF-8"))).cues.get(0);
    }

    private static String covered(CigramSubtitleOverlay.Layout l, int[] span) { return l.text.substring(span[1], span[2]); }

    @Test public void arabicLinesGetDirectionMarksAndStylesStayOnTheirWords() throws Exception {
        CigramSubtitleOverlay.Layout l = CigramSubtitleOverlay.layout(cue("مرحبا <i>يا صديقي</i>!\nHello <b>friend</b>"));
        String[] lines = l.text.split("\n");
        assertEquals('‏', lines[0].charAt(0));
        assertEquals('‏', lines[0].charAt(lines[0].length() - 1));
        assertEquals("Hello friend", lines[1]);
        assertEquals(2, l.spans.size());
        for (int[] sp : l.spans) {
            if (sp[0] == CigramSubtitles.SPAN_ITALIC) assertEquals("يا صديقي", covered(l, sp));
            else assertEquals("friend", covered(l, sp));
        }
    }

    @Test public void plainLatinTextIsUntouched() throws Exception {
        CigramSubtitleOverlay.Layout l = CigramSubtitleOverlay.layout(cue("Just text"));
        assertEquals("Just text", l.text);
        assertEquals(0, l.spans.size());
    }

    @Test public void aStyleOverAWholeArabicCueIsMappedCorrectly() throws Exception {
        CigramSubtitleOverlay.Layout l = CigramSubtitleOverlay.layout(cue("<i>كل الجملة مائلة</i>"));
        List<int[]> spans = l.spans;
        assertEquals(1, spans.size());
        assertEquals("كل الجملة مائلة", covered(l, spans.get(0)));
    }

    @Test public void twoLinesWithStylesOnEachKeepTheirOwnRanges() throws Exception {
        CigramSubtitleOverlay.Layout l = CigramSubtitleOverlay.layout(cue("<b>أول</b> سطر\n<i>ثاني</i> سطر"));
        assertEquals(2, l.spans.size());
        assertEquals("أول", covered(l, l.spans.get(0).length == 3 && l.spans.get(0)[0] == CigramSubtitles.SPAN_BOLD ? l.spans.get(0) : l.spans.get(1)));
        for (int[] sp : l.spans) if (sp[0] == CigramSubtitles.SPAN_ITALIC) assertEquals("ثاني", covered(l, sp));
    }

    // ---- the search screen's episode hand-over (same glue-test class: both need the stubs)

    @Test public void episodeJsonKeepsEverySeasonAndEpisodeNumberEvenForAVeryLongSeries() throws Exception {
        CigramMediaRef ref = new CigramMediaRef.Builder().kind(CigramMediaRef.SERIES).tmdbId("1").title("مسلسل طويل جدا").build();
        java.util.ArrayList<CigramEpisode> eps = new java.util.ArrayList<CigramEpisode>();
        for (int i = 1; i <= 2500; i++) {
            eps.add(new CigramEpisode("tmdb:ep:" + i, 1 + i / 500, i, i, "عنوان الحلقة رقم " + i + " من المسلسل الطويل جدا", "", "https://image.tmdb.org/t/p/w300/abcdefghijklmnop" + i + ".jpg", "", 0, "", ""));
        }
        String json = CigramCatalogSearchActivity.episodesJson(ref, eps);
        assertTrue("size " + json.length(), json.length() <= CigramCatalogSearchActivity.MAX_EPISODES_JSON);
        org.json.JSONArray arr = new org.json.JSONArray(json);
        assertEquals(2500, arr.length());
        assertEquals("1", arr.getJSONObject(0).getString("episode"));
        assertEquals("2500", arr.getJSONObject(2499).getString("episode"));
        assertEquals(String.valueOf(1 + 2500 / 500), arr.getJSONObject(2499).getString("season"));
    }

    @Test public void aNormalSeriesKeepsTitlesAndPictures() throws Exception {
        CigramMediaRef ref = new CigramMediaRef.Builder().kind(CigramMediaRef.SERIES).tmdbId("1").title("Show").build();
        java.util.ArrayList<CigramEpisode> eps = new java.util.ArrayList<CigramEpisode>();
        eps.add(new CigramEpisode("tmdb:ep:5", 2, 3, 0, "Title", "", "https://x/s.jpg", "", 0, "", ""));
        org.json.JSONObject o = new org.json.JSONArray(CigramCatalogSearchActivity.episodesJson(ref, eps)).getJSONObject(0);
        assertEquals("Title", o.getString("title"));
        assertEquals("https://x/s.jpg", o.getString("backdrop"));
        assertEquals("tmdb:ep:5", o.getString("episode_id"));
        assertEquals("2", o.getString("season"));
        assertEquals("3", o.getString("episode"));
    }
}
