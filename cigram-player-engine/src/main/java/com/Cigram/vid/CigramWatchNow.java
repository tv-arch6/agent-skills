package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * «شاهد الآن» for a title that has no direct link in the library: finds a playable source through
 * the engine (library link, then providers), shows a clear loading state with a cancel button, and
 * opens the player only when a validated source exists. When nothing works it says so plainly, with
 * the reason per provider, and offers a retry.
 *
 * The player screen is not opened before the movie / episode is fully identified, and a second tap
 * while a search is running cannot start a second one (the dialog is modal and the coordinator
 * recognises the duplicate).
 */
public final class CigramWatchNow {
    private static Dialog dialog;
    private static TextView status;

    private CigramWatchNow() { }

    /**
     * Entry point used by CigramStage1InitProvider.openPlayerForTitleItem when the item has no link.
     * @param source        the movie, or the episode
     * @param parentSeries  the series of that episode, or null for a movie
     */
    public static void start(Activity a, JSONObject source, JSONObject parentSeries, boolean episodeTransition) {
        CigramMediaRequest req = buildRequest(source, parentSeries);
        if (req == null) {
            Toast.makeText(a, "لا توجد بيانات كافية لتحديد هذا العمل.", Toast.LENGTH_SHORT).show();
            return;
        }
        begin(a, req, source, parentSeries, episodeTransition);
    }

    /** The request for exactly this movie / episode, or null when the item cannot be identified (e.g. a series without episodes). */
    static CigramMediaRequest buildRequest(JSONObject source, JSONObject parentSeries) {
        if (source == null) return null;
        boolean isEp = parentSeries != null;
        CigramMediaRef ref = CigramLibraryItems.toRef(isEp ? parentSeries : source);
        if (!isEp) {
            if (ref.isSeries()) return null;                                  // a series needs an episode first
            return CigramMediaRequest.movie(ref, source);
        }
        if (ref.title.length() == 0 && ref.tmdbId.length() == 0) return null;
        return CigramMediaRequest.episode(ref, CigramLibraryItems.toEpisode(source, 0), source);
    }

    private static void begin(final Activity a, final CigramMediaRequest req, final JSONObject source,
                              final JSONObject parent, final boolean transition) {
        final CigramEngine engine = CigramEngine.get(a);
        showLoading(a, "جاري البحث عن مصدر التشغيل…", new Runnable() {
            @Override public void run() { engine.coordinator.cancel(); }
        });
        final WeakReference<Activity> ref = new WeakReference<Activity>(a);
        engine.coordinator.play(req, new CigramPlaybackCoordinator.Listener() {
            @Override public void onState(CigramMediaRequest r, int state, String message) {
                if (status != null && dialog != null && dialog.isShowing()) status.setText(message);
            }

            @Override public void onReady(CigramMediaRequest r, CigramPlaybackSource chosen, List<CigramPlaybackSource> alternates) {
                dismiss();
                Activity act = ref.get();
                if (act == null || act.isFinishing()) return;
                CigramPlayerBridge.seed(r, chosen, alternates);
                try {
                    JSONObject patched = new JSONObject(source.toString());
                    patched.put("video", chosen.toStreamLink());
                    List<CigramPlaybackSource.Subtitle> subs = chosen.declaredSubtitles();
                    if (!subs.isEmpty() && CigramLibraryItems.subtitle(patched).length() == 0) patched.put("subtitle", subs.get(0).url);
                    // identity for the player screen (see CigramPlayerBridge.identify)
                    patched.put("cg_origin", r.catalogId.startsWith("tmdb:") ? "tmdb" : "library");
                    patched.put("cg_catalog_id", r.catalogId);
                    patched.put("cg_library_id", r.libraryId);
                    patched.put("cg_tmdb_id", r.tmdbId);
                    patched.put("cg_year", String.valueOf(r.year));
                    patched.put("cg_episode_id", r.episodeId);
                    CigramStage1InitProvider.openPlayerForTitleItem(act, patched, parent, transition, true);
                } catch (Throwable t) {
                    CigramLog.w("watchnow", "could not open the player", t);
                    Toast.makeText(act, "تعذر فتح المشغل", Toast.LENGTH_SHORT).show();
                }
            }

            @Override public void onFailed(CigramMediaRequest r, String message, List<CigramPlaybackCoordinator.Report> reports, boolean retryable) {
                Activity act = ref.get();
                if (act == null || act.isFinishing()) { dismiss(); return; }
                showFailure(act, message, reports, req, source, parent, transition);
            }
        });
    }

    // ================================================================== dialogs

    private static void showLoading(Activity a, String text, final Runnable onCancel) {
        dismiss();
        LinearLayout card = card(a);
        ProgressBar bar = new ProgressBar(a);
        card.addView(bar, new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)));
        status = label(a, text, 15, Color.WHITE, true);
        card.addView(status, params(a, 14));
        card.addView(button(a, "إلغاء", false, new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); onCancel.run(); }
        }), params(a, 16));
        show(a, card, true, onCancel);
    }

    private static void showFailure(final Activity a, String message, List<CigramPlaybackCoordinator.Report> reports,
                                    final CigramMediaRequest req, final JSONObject source, final JSONObject parent, final boolean transition) {
        dismiss();
        LinearLayout card = card(a);
        card.addView(label(a, "تعذر التشغيل", 17, Color.WHITE, true), params(a, 0));
        card.addView(label(a, message, 14, 0xFFC3CDD2, false), params(a, 8));
        if (reports != null && !reports.isEmpty()) {
            final LinearLayout details = new LinearLayout(a);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setVisibility(View.GONE);
            for (CigramPlaybackCoordinator.Report r : reports) {
                details.addView(label(a, "• " + r.displayName + ": " + r.note, 12, 0xFF8796A0, false), params(a, 4));
            }
            card.addView(details, params(a, 6));
            final TextView toggle = button(a, "التفاصيل", false, null);
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean open = details.getVisibility() == View.VISIBLE;
                    details.setVisibility(open ? View.GONE : View.VISIBLE);
                    toggle.setText(open ? "التفاصيل" : "إخفاء التفاصيل");
                }
            });
            card.addView(toggle, params(a, 10));
        }
        card.addView(button(a, "إعادة المحاولة", true, new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); begin(a, req, source, parent, transition); }
        }), params(a, 12));
        card.addView(button(a, "إغلاق", false, new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        }), params(a, 8));
        show(a, card, true, null);
    }

    private static void show(Activity a, View content, boolean cancelable, final Runnable onCancel) {
        if (a.isFinishing()) return;
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(content);
        d.setCancelable(cancelable);
        d.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
            @Override public void onCancel(android.content.DialogInterface di) { if (onCancel != null) onCancel.run(); }
        });
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            d.getWindow().setLayout(Math.min(dp(a, 340), a.getResources().getDisplayMetrics().widthPixels - dp(a, 32)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog = d;
        try { d.show(); } catch (Throwable ignored) { dialog = null; }
    }

    static void dismiss() {
        Dialog d = dialog;
        dialog = null;
        status = null;
        if (d != null) { try { d.dismiss(); } catch (Throwable ignored) { } }
    }

    // ------------------------------------------------------------------ small view helpers (the app's dark style)

    private static int dp(Activity a, float v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }

    private static LinearLayout card(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(a, 20), dp(a, 20), dp(a, 20), dp(a, 18));
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFF0A1F29);
        g.setCornerRadius(dp(a, 18));
        g.setStroke(Math.max(1, dp(a, 1)), 0x2EFFFFFF);
        l.setBackground(g);
        return l;
    }

    private static TextView label(Activity a, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(font(a), bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private static TextView button(Activity a, String text, boolean primary, View.OnClickListener click) {
        TextView b = label(a, text, 14, Color.WHITE, true);
        b.setPadding(dp(a, 16), dp(a, 11), dp(a, 16), dp(a, 11));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(primary ? CigramDesign.ACCENT : 0x14FFFFFF);
        g.setCornerRadius(dp(a, 14));
        if (!primary) g.setStroke(Math.max(1, dp(a, 1)), 0x2EFFFFFF);
        b.setBackground(g);
        if (click != null) b.setOnClickListener(click);
        return b;
    }

    private static LinearLayout.LayoutParams params(Activity a, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(a, topDp);
        return p;
    }

    private static Typeface font(Activity a) {
        try { return CigramDesign.font(a); } catch (Throwable t) { return Typeface.DEFAULT; }
    }
}
