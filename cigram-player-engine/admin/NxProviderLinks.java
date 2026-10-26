package com.my.newproject;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * «من المزوّد» in the add-movie screen: the movie is attached to a provider's OWN documented player page, built from
 * the template the admin copies once from that provider's documentation (placeholders {tmdb} {imdb}). Nothing is
 * guessed, fetched or extracted: the page link is stored on the item as `embed_url` and the viewer app opens that
 * provider player when the movie has no direct video file.
 */
final class NxProviderLinks {
    private NxProviderLinks() { }

    static final String[][] PROVIDERS = {
            {"vidfast", "VidFast"}, {"vidnest", "VidNest"}, {"videm", "Videm"}, {"vidbolt", "VidBolt"}};

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("cigram_admin_providers", Context.MODE_PRIVATE);
    }

    /** Patterns the owner already gave (from a real provider link); used until a template is saved on the device. */
    private static String defaultTemplate(String id) {
        return "vidbolt".equals(id) ? "https://vidbolt.pro/movie/{tmdb}" : "";
    }

    static String template(Context c, String id) { return prefs(c).getString(id + ".movie", defaultTemplate(id)); }

    /**
     * Accepts either a template ({tmdb} / {imdb}) or an example link of one movie copied from the provider, such as
     * https://vidbolt.pro/movie/1400940: the TMDB number (or tt… IMDb id) in it becomes the placeholder.
     * Returns "" when it cannot tell where the id is.
     */
    static String normalizeTemplate(String in) {
        String t = in == null ? "" : in.trim();
        if (templateOk(t)) return t;
        if (!t.startsWith("https://") || t.indexOf('{') >= 0) return "";
        java.util.regex.Matcher imdb = java.util.regex.Pattern.compile("^(https://[^?#]+?/)(tt\\d{5,10})(/?([?#].*)?)$").matcher(t);
        if (imdb.matches()) return imdb.group(1) + "{imdb}" + imdb.group(3);
        java.util.regex.Matcher tmdb = java.util.regex.Pattern.compile("^(https://[^?#]+?/)(\\d{1,10})(/?([?#].*)?)$").matcher(t);
        if (tmdb.matches()) return tmdb.group(1) + "{tmdb}" + tmdb.group(3);
        return "";
    }

    /** https, a {tmdb} or {imdb} placeholder, no other placeholder. */
    static boolean templateOk(String t) {
        if (t == null || !t.startsWith("https://")) return false;
        String rest = t.replace("{tmdb}", "").replace("{imdb}", "");
        if (rest.indexOf('{') >= 0 || rest.indexOf('}') >= 0) return false;
        return t.contains("{tmdb}") || t.contains("{imdb}");
    }

    private static String safeId(String s) { return s != null && s.matches("[A-Za-z0-9]{1,20}") ? s : ""; }

    /** The provider page for this movie, or "" when the template or the ids are missing. */
    static String movieUrl(String template, String tmdb, String imdb) {
        if (!templateOk(template)) return "";
        String t = safeId(tmdb), i = safeId(imdb);
        if ((template.contains("{tmdb}") && t.length() == 0) || (template.contains("{imdb}") && i.length() == 0)) return "";
        return template.replace("{tmdb}", t).replace("{imdb}", i);
    }

    /** The «من المزوّد» panel. onPick receives {url, providerId}. */
    static View box(final Activity a, final JSONObject st, final Runnable rerender, final CgUi.Callback<String[]> onPick) {
        LinearLayout box = CgUi.vbox(a);
        box.setBackground(NxUi.surface(a, 20));
        int p = CgUi.dp(a, 14);
        box.setPadding(p, p, p, CgUi.dp(a, 10));
        String tmdb = CxJ.s(st, "tmdb_id"), imdb = CxJ.s(st, "imdb_id");
        if (tmdb.length() == 0 && imdb.length() == 0) {
            box.addView(CgUi.text(a, "هذا الفيلم بلا معرّف TMDB / IMDb، فلا يمكن ربطه بمشغّل مزوّد. اختره من نتائج البحث.", 12.5f, NxUi.DOWN, true));
            return box;
        }
        for (final String[] pr : PROVIDERS) {
            final String tpl = template(a, pr[0]);
            final String url = movieUrl(tpl, tmdb, imdb);
            LinearLayout row = CgUi.vbox(a);
            row.setPadding(0, CgUi.dp(a, 6), 0, CgUi.dp(a, 6));
            row.addView(CgUi.text(a, pr[1], 14, NxUi.TEXT, true));
            TextView info = CgUi.text(a, url.length() > 0 ? url : (tpl.length() == 0 ? "لم يُضبط قالب الرابط — انسخه من توثيق المزوّد" : "القالب لا يناسب معرّفات هذا الفيلم"), 11, url.length() > 0 ? NxUi.SUB : NxUi.DIM, false);
            if (url.length() > 0) CgUi.ltr(info);
            row.addView(info);
            LinearLayout btns = CgUi.hbox(a);
            if (url.length() > 0) {
                TextView use = CgUi.smallButton(a, "ربط بالبطاقة", 0);
                use.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { onPick.run(new String[]{url, pr[0]}); }
                });
                btns.addView(use);
                TextView look = CgUi.smallButton(a, "معاينة", 1);
                look.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Throwable t) { CgUi.toast(a, "لا يوجد متصفح."); }
                    }
                });
                btns.addView(look, CgUi.lp(a, CgUi.WRAP, CgUi.WRAP, 8, 0, 0, 0));
            }
            TextView edit = CgUi.smallButton(a, "القالب", 1);
            edit.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    CgUi.input(a, "رابط فيلم من " + pr[1] + " (مثال) أو القالب", "https://…/movie/1400940", tpl, false, new CgUi.Callback<String>() {
                        @Override public void run(String value) {
                            String raw = value == null ? "" : value.trim();
                            String t = raw.length() == 0 ? "" : normalizeTemplate(raw);
                            if (raw.length() > 0 && t.length() == 0) {
                                CgUi.info(a, "لم أتعرّف على الرابط", "الصق رابط فيلم واحد من المزوّد ينتهي برقم TMDB (مثل https://vidbolt.pro/movie/1400940) أو قالباً فيه {tmdb}.");
                                return;
                            }
                            prefs(a).edit().putString(pr[0] + ".movie", t).apply();
                            if (t.length() > 0) CgUi.toast(a, "القالب: " + t);
                            rerender.run();
                        }
                    });
                }
            });
            btns.addView(edit, CgUi.lp(a, CgUi.WRAP, CgUi.WRAP, 8, 0, 0, 0));
            row.addView(btns, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
            box.addView(row);
        }
        box.addView(CgUi.text(a, "المشاهد يرى مشغّل المزوّد نفسه؛ أزرار مشغّل التطبيق لا تعمل عليه. للأزرار كاملة ارفع الملف أو استعمل رابطاً مباشراً.", 11, NxUi.DIM, false),
                CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 2, 8, 2, 0));
        return box;
    }
}
