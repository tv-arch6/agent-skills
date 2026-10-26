package com.my.newproject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * «من المزوّد»: asks the configured licensed providers for DIRECT video links of a movie or of every episode of a
 * season, checks each link with the same validator the viewer app uses (a real stream, not a page; DRM and
 * embed-only answers are skipped), and keeps the first working link as the video plus the others as backups
 * (`video_backups`, one link per line). The viewer app plays them in its own player, with all its controls, and
 * moves to a backup by itself when a link stops working.
 *
 * A provider is a slot (p1..p4) with a name, an https API address and a key, stored in this app's private settings.
 * The API must answer the contract documented in CigramJsonProvider (GET {api}/resolve?type=..&tmdb=..&season=..).
 * Nothing is guessed: a provider that is not configured, not reachable or not answering that contract is reported.
 */
final class NxProviderFetch {
    private NxProviderFetch() { }

    static final String[] SLOTS = {"p1", "p2", "p3", "p4"};
    private static final int LINKS_PER_PROVIDER = 2;

    static final class Found {
        final String provider;
        final String link;          // stream link ("url" or "url|Header=value")
        final String kind;
        final String quality;
        Found(String provider, String link, String kind, String quality) {
            this.provider = provider; this.link = link; this.kind = kind; this.quality = quality;
        }
    }

    static final class Outcome {
        final ArrayList<Found> links = new ArrayList<Found>();
        final ArrayList<String> report = new ArrayList<String>();
        String primary() { return links.isEmpty() ? "" : links.get(0).link; }
        String backups() {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < links.size(); i++) { if (sb.length() > 0) sb.append('\n'); sb.append(links.get(i).link); }
            return sb.toString();
        }
    }

    static CigramEngineConfig config(Context c) { return CigramEngineConfig.forApp(c); }

    static String name(Context c, int slot) {
        return config(c).get("provider." + SLOTS[slot] + ".name", "المزوّد " + (slot + 1));
    }

    static List<CigramJsonProvider> providers(Context c) {
        ArrayList<CigramJsonProvider> out = new ArrayList<CigramJsonProvider>();
        CigramEngineConfig cfg = config(c);
        for (int i = 0; i < SLOTS.length; i++) {
            if (cfg.providerApi(SLOTS[i]).length() == 0) continue;
            out.add(new CigramJsonProvider(SLOTS[i], name(c, i), (i + 1) * 10, cfg, ""));
        }
        return out;
    }

    /** Blocking (call it off the main thread): every configured provider, every direct source validated. */
    static Outcome resolve(Context c, CigramMediaRequest req, CigramCancel cancel) {
        Outcome o = new Outcome();
        List<CigramJsonProvider> ps = providers(c);
        if (ps.isEmpty()) { o.report.add("لا يوجد مزوّد مضبوط. اضغط «إعداد المزوّدين»."); return o; }
        for (CigramJsonProvider p : ps) {
            if (cancel.isCancelled()) break;
            if (p.status() != CigramSourceProvider.INTEGRATED) { o.report.add("✖ " + p.displayName() + ": " + p.statusNote()); continue; }
            List<CigramPlaybackSource> list;
            try {
                list = p.resolve(req, cancel);
            } catch (CigramEngineException e) {
                o.report.add("✖ " + p.displayName() + ": " + e.getMessage());
                continue;
            }
            int ok = 0, skipped = 0;
            String lastReason = "";
            for (CigramPlaybackSource s : list) {
                if (ok >= LINKS_PER_PROVIDER || cancel.isCancelled()) break;
                if (s.availability != CigramPlaybackSource.UNKNOWN) { skipped++; lastReason = s.failureReason; continue; }
                CigramSourceValidator.Result r = p.validate(s, cancel);
                if (r.ok) { o.links.add(new Found(p.displayName(), s.toStreamLink(), r.kind, s.quality)); ok++; }
                else lastReason = r.reason;
            }
            if (ok > 0) o.report.add("✔ " + p.displayName() + ": " + ok + " رابط يعمل");
            else if (list.isEmpty()) o.report.add("— " + p.displayName() + ": غير متوفر لديه");
            else o.report.add("✖ " + p.displayName() + ": لا رابط مباشر يعمل" + (lastReason.length() > 0 ? " (" + lastReason + ")" : "")
                    + (skipped > 0 ? " — " + skipped + " غير مباشر أو محمي" : ""));
        }
        return o;
    }

    // ================================================================== movie: the «من المزوّد» tab

    /** onUse receives {primary link, backups (one per line)}. */
    static View movieBox(final Activity a, final JSONObject st, final Runnable rerender, final CgUi.Callback<String[]> onUse) {
        LinearLayout box = CgUi.vbox(a);
        box.setBackground(NxUi.surface(a, 20));
        int p = CgUi.dp(a, 14);
        box.setPadding(p, p, p, CgUi.dp(a, 10));
        final String tmdb = CxJ.s(st, "tmdb_id"), imdb = CxJ.s(st, "imdb_id");
        List<CigramJsonProvider> ps = providers(a);
        StringBuilder names = new StringBuilder();
        for (CigramJsonProvider pr : ps) { if (names.length() > 0) names.append("، "); names.append(pr.displayName()); }
        box.addView(CgUi.text(a, ps.isEmpty() ? "لا يوجد مزوّد مضبوط بعد." : "المزوّدون: " + names, 13, NxUi.TEXT, true));
        final LinearLayout results = CgUi.vbox(a);
        if (tmdb.length() == 0 && imdb.length() == 0) {
            box.addView(CgUi.text(a, "هذا الفيلم بلا معرّف TMDB / IMDb. اختره من نتائج البحث أولاً.", 12, NxUi.DOWN, false));
        } else if (!ps.isEmpty()) {
            TextView go = CgUi.button(a, "جلب الروابط من المزوّدين", 0);
            go.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    results.removeAllViews();
                    results.addView(CgUi.text(a, "جارٍ السؤال والفحص... (قد يستغرق دقيقة)", 12, NxUi.SUB, false));
                    final CigramMediaRef ref = new CigramMediaRef.Builder().kind(CigramMediaRef.MOVIE).tmdbId(tmdb).imdbId(imdb)
                            .catalogId("admin:" + (tmdb.length() > 0 ? tmdb : imdb)).title(CxJ.s(st, "title"))
                            .year(CxJ.n(st, "year")).build();
                    new Thread(new Runnable() {
                        @Override public void run() {
                            final Outcome o = resolve(a, CigramMediaRequest.movie(ref, null), new CigramCancel());
                            a.runOnUiThread(new Runnable() {
                                @Override public void run() { showMovieOutcome(a, results, o, onUse); }
                            });
                        }
                    }, "provider-fetch").start();
                }
            });
            box.addView(go, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        }
        box.addView(results, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        TextView set = CgUi.smallButton(a, "إعداد المزوّدين", 1);
        set.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { settings(a, rerender); }
        });
        box.addView(set, CgUi.lp(a, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
        return box;
    }

    private static void showMovieOutcome(final Activity a, LinearLayout results, final Outcome o, final CgUi.Callback<String[]> onUse) {
        results.removeAllViews();
        for (String line : o.report) {
            results.addView(CgUi.text(a, line, 12, line.startsWith("✔") ? NxUi.UP : line.startsWith("✖") ? NxUi.DOWN : NxUi.SUB, false));
        }
        if (o.links.isEmpty()) {
            results.addView(CgUi.text(a, "لا يوجد رابط فيديو مباشر يعمل لهذا الفيلم الآن.", 12.5f, NxUi.DOWN, true));
            return;
        }
        Found first = o.links.get(0);
        results.addView(CgUi.text(a, "الأساسي: " + first.provider + " (" + first.kind + (first.quality.length() > 0 ? " " + first.quality : "") + ")"
                + (o.links.size() > 1 ? " + " + (o.links.size() - 1) + " احتياطي" : ""), 12.5f, NxUi.TEXT, true));
        TextView use = CgUi.button(a, "استخدام الروابط", 0);
        use.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onUse.run(new String[]{o.primary(), o.backups()}); }
        });
        results.addView(use, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
    }

    // ================================================================== settings

    static void settings(final Activity a, final Runnable done) {
        final CigramEngineConfig cfg = config(a);
        LinearLayout box = CgUi.vbox(a);
        int p = CgUi.dp(a, 16);
        box.setPadding(p, CgUi.dp(a, 8), p, 0);
        box.addView(CgUi.text(a, "لكل مزوّد مرخّص: اسم، عنوان الـ API (https) ومفتاحه. الـ API يجب أن يعيد روابط فيديو مباشرة بالصيغة الموثّقة "
                + "(GET {api}/resolve?type=movie|episode&tmdb=..&imdb=..&season=..&episode=..). المفتاح يُحفظ على هذا الجهاز فقط.", 11.5f, NxUi.SUB, false));
        final EditText[][] fields = new EditText[SLOTS.length][3];
        for (int i = 0; i < SLOTS.length; i++) {
            String id = SLOTS[i];
            box.addView(CgUi.text(a, "المزوّد " + (i + 1), 13.5f, NxUi.TEXT, true), CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
            fields[i][0] = CgUi.field(a, "الاسم", false);
            fields[i][0].setText(cfg.get("provider." + id + ".name", ""));
            fields[i][1] = CgUi.urlField(a, "https://api.example.com/v1");
            fields[i][1].setText(cfg.providerApi(id));
            fields[i][2] = CgUi.field(a, cfg.providerToken(id).length() > 0 ? "المفتاح محفوظ — اتركه فارغاً للإبقاء عليه" : "المفتاح (API key)", false);
            fields[i][2].setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            for (EditText e : fields[i]) box.addView(e);
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        CgUi.dialog(a).setTitle("إعداد المزوّدين").setView(sv)
                .setPositiveButton("حفظ", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        for (int i = 0; i < SLOTS.length; i++) {
                            String id = SLOTS[i];
                            String api = fields[i][1].getText().toString().trim();
                            if (api.length() > 0 && !CigramJsonProvider.apiBaseOk(api)) {
                                CgUi.toast(a, "المزوّد " + (i + 1) + ": العنوان يجب أن يبدأ بـ https://");
                                continue;
                            }
                            cfg.put("provider." + id + ".name", fields[i][0].getText().toString().trim());
                            cfg.put("provider." + id + ".api", api);
                            String key = fields[i][2].getText().toString().trim();
                            if (key.length() > 0) cfg.put("provider." + id + ".token", key);
                            if (api.length() == 0) cfg.put("provider." + id + ".token", null);
                        }
                        if (done != null) done.run();
                    }
                })
                .setNegativeButton("إلغاء", null).show();
    }

    // ================================================================== a whole season

    /**
     * Asks the server for the season's episodes (TMDB), resolves every episode through the providers, shows which ones
     * have working links, and publishes them in one write (season/commit) with their backups.
     */
    static void season(final Activity a, final String seriesKey, final int season, final Runnable onPublished) {
        if (providers(a).isEmpty()) { settings(a, null); return; }
        final TextView status = CgUi.text(a, "جارٍ جلب حلقات الموسم " + season + "...", 13, NxUi.TEXT, false);
        int p = CgUi.dp(a, 18);
        status.setPadding(p, p, p, p);
        final CigramCancel cancel = new CigramCancel();
        final AlertDialog progress = CgUi.dialog(a).setTitle("جلب روابط الموسم").setView(status).setCancelable(false)
                .setNegativeButton("إيقاف", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { cancel.cancel(); }
                }).show();
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                final JSONArray rows = new JSONArray();
                final ArrayList<String> lines = new ArrayList<String>();
                try {
                    JSONObject b = new JSONObject();
                    b.put("series_id", seriesKey);
                    b.put("season", season);
                    JSONObject info = CxApi.post("/admin/tmdb/season", b);
                    JSONArray eps = info.optJSONArray("episodes");
                    if (eps == null || eps.length() == 0) throw new Exception("لا توجد حلقات لهذا الموسم في TMDB.");
                    CigramMediaRef ref = new CigramMediaRef.Builder().kind(CigramMediaRef.SERIES)
                            .tmdbId(info.optString("tmdb_id", "")).imdbId(info.optString("imdb_id", ""))
                            .catalogId("admin:" + seriesKey).title(info.optString("title", seriesKey)).build();
                    for (int i = 0; i < eps.length() && !cancel.isCancelled(); i++) {
                        final JSONObject e = eps.getJSONObject(i);
                        final int n = e.optInt("ep");
                        final int at = i + 1, total = eps.length();
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() { status.setText("الحلقة " + n + " (" + at + " من " + total + ")..."); }
                        });
                        CigramEpisode ep = new CigramEpisode("", season, n, i, e.optString("title"), "", "", "", 0, "", "");
                        Outcome o = resolve(a, CigramMediaRequest.episode(ref, ep, null), cancel);
                        if (o.links.isEmpty()) {
                            lines.add(String.format(java.util.Locale.US, "E%02d", n) + "  ✖ لا رابط يعمل");
                            continue;
                        }
                        JSONObject row = new JSONObject();
                        row.put("ep", n);
                        row.put("title", e.optString("title", ""));
                        row.put("video", o.primary());
                        if (o.backups().length() > 0) row.put("video_backups", o.backups());
                        row.put("thumb", e.optString("thumb", ""));
                        row.put("overview", e.optString("overview", ""));
                        row.put("duration", e.optString("duration", ""));
                        row.put("year", e.optString("year", ""));
                        rows.put(row);
                        lines.add(String.format(java.util.Locale.US, "E%02d", n) + "  ✔ " + o.links.get(0).provider
                                + (o.links.size() > 1 ? " + " + (o.links.size() - 1) + " احتياطي" : ""));
                    }
                } catch (Throwable t) {
                    error = t.getMessage() == null ? "تعذر جلب الموسم." : t.getMessage();
                }
                final String err = error;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { progress.dismiss(); } catch (Throwable ignored) { }
                        if (err != null) { CgUi.info(a, "جلب روابط الموسم", err); return; }
                        confirmSeason(a, seriesKey, season, rows, lines, onPublished);
                    }
                });
            }
        }, "provider-season").start();
    }

    private static void confirmSeason(final Activity a, final String seriesKey, final int season, final JSONArray rows,
                                      List<String> lines, final Runnable onPublished) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        AlertDialog.Builder b = CgUi.dialog(a).setTitle("الموسم " + season + ": " + rows.length() + " حلقة جاهزة").setMessage(sb.toString())
                .setNegativeButton("إغلاق", null);
        if (rows.length() > 0) {
            b.setPositiveButton("نشر " + rows.length() + " حلقة", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { publishSeason(a, seriesKey, season, rows, onPublished); }
            });
        }
        b.show();
    }

    private static void publishSeason(final Activity a, final String seriesKey, final int season, final JSONArray rows, final Runnable onPublished) {
        CgUi.toast(a, "جارٍ النشر...");
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                boolean ok = false;
                try {
                    JSONObject b = new JSONObject();
                    b.put("series_id", seriesKey);
                    b.put("season", season);
                    b.put("episodes", rows);
                    b.put("update_existing", true);
                    JSONObject r = CxApi.post("/admin/season/commit", b);
                    msg = r.optInt("added") + " جديدة، " + r.optInt("updated") + " محدّثة";
                    ok = true;
                } catch (Throwable t) {
                    msg = t.getMessage() == null ? "تعذر النشر." : t.getMessage();
                }
                final String m = msg;
                final boolean done = ok;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        CgUi.info(a, done ? "تم نشر الموسم " + season : "لم يُنشر الموسم", m);
                        if (done && onPublished != null) onPublished.run();
                    }
                });
            }
        }, "provider-season-publish").start();
    }
}
