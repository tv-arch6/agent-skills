package com.my.newproject;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Admin-side check that uses the SAME engine the viewer app uses (CigramSourceValidator / CigramSubtitles), so
 * "it passes here" means "the app accepts it": a real media stream (not a web page), a real HLS playlist whose
 * first segment answers, DRM / audio-only flagged, and a subtitle file that parses (encoding, cue count, Arabic).
 * Nothing is uploaded or saved; it only reads.
 */
final class CgEngineCheck {
    private CgEngineCheck() { }

    static void run(final Activity a, final String video, final String subtitle, final LinearLayout box) {
        box.removeAllViews();
        add(a, box, "جارٍ الفحص...", CgCfg.MUTED);
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.ArrayList<Object[]> lines = new java.util.ArrayList<Object[]>();
                checkVideo(video, lines);
                checkSubtitle(subtitle, lines);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        box.removeAllViews();
                        for (Object[] l : lines) add(a, box, (String) l[0], (Integer) l[1]);
                    }
                });
            }
        }, "cg-engine-check").start();
    }

    private static void checkVideo(String url, List<Object[]> out) {
        if (url == null || url.trim().length() == 0) { out.add(new Object[]{"✖ الفيديو: الرابط مفقود.", CgCfg.BAD}); return; }
        CigramPlaybackSource s = new CigramPlaybackSource();
        s.providerId = "admin";
        s.playbackUri = CigramStreamLinkLite.clean(url);
        long t0 = System.currentTimeMillis();
        CigramSourceValidator.Result r = new CigramSourceValidator().timeouts(8000, 12000).validate(s, new CigramCancel());
        if (r.ok) {
            out.add(new Object[]{"✔ الفيديو: وسيط حقيقي (" + r.kind + ") — " + r.mime, CgCfg.GOOD});
        } else {
            out.add(new Object[]{"✖ الفيديو: " + r.reason, CgCfg.BAD});
            if (r.status == CigramPlaybackSource.DRM) out.add(new Object[]{"الفيديو محمي بـ DRM ولا يعمل بلا ترخيص.", CgCfg.WARN});
            if (r.retryable) out.add(new Object[]{"قد يكون الخطأ مؤقتاً: أعد الفحص.", CgCfg.WARN});
        }
        out.add(new Object[]{"زمن الفحص: " + (System.currentTimeMillis() - t0) + " ms", CgCfg.MUTED});
    }

    private static void checkSubtitle(String url, List<Object[]> out) {
        if (url == null || url.trim().length() == 0) { out.add(new Object[]{"— لا توجد ترجمة (الفيديو يعمل بدونها).", CgCfg.MUTED}); return; }
        try {
            CigramSubtitles.Track t = CigramSubtitles.fetch(url.trim(), null, CigramSubtitles.TRUST_PROVIDER, new CigramCancel());
            boolean utf8 = "UTF-8".equals(t.charset);
            out.add(new Object[]{"✔ الترجمة: " + t.cues.size() + " سطر، صيغة " + t.format.toUpperCase(java.util.Locale.US)
                    + "، آخر سطر عند " + (t.lastEndMs() / 60000) + " د", CgCfg.GOOD});
            out.add(new Object[]{"الترميز: " + t.charset + (utf8 ? " (سليم)" : " — ليس UTF-8، التطبيق يقرأه تلقائياً لكن الأفضل رفعه UTF-8"), utf8 ? CgCfg.MUTED : CgCfg.WARN});
            out.add(new Object[]{t.rtl ? "النص عربي (يُعرض من اليمين لليسار)." : "النص ليس عربياً في معظمه.", CgCfg.MUTED});
            for (int i = 0; i < Math.min(3, t.cues.size()); i++) {
                CigramSubtitles.Cue c = t.cues.get(i);
                out.add(new Object[]{CigramSubtitles.vttTimeOf(c.startMs) + "  " + c.text.replace('\n', ' '), CgCfg.TEXT});
            }
            for (String w : t.warnings) out.add(new Object[]{"⚠ " + w, CgCfg.WARN});
        } catch (CigramEngineException e) {
            out.add(new Object[]{"✖ الترجمة: " + e.getMessage(), CgCfg.BAD});
        }
    }

    private static void add(Activity a, LinearLayout box, String text, int color) {
        TextView t = CgUi.text(a, text, 13, color, false);
        t.setPadding(0, CgUi.dp(a, 3), 0, 0);
        box.addView(t);
    }
}
