package com.Cigram.vid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.SubtitleView;

import java.util.ArrayList;
import java.util.List;

/**
 * The single point where the existing single-viewer player (PlayerActivity) meets the engine. The
 * player screen keeps all its own controls; it only asks the bridge for four things:
 *
 *  1. external subtitles   (declared link, local .srt/.vtt file, typed link) with no video restart;
 *  2. a source for an episode that has no direct link (resolved through the coordinator);
 *  3. another source of the SAME movie / episode when the current one fails;
 *  4. the list of sources, to let the viewer choose.
 *
 * It is created and used on the main thread, and released with the screen. It never touches a
 * Together room: this player is the viewer's own.
 */
public final class CigramPlayerBridge {

    /** What the player screen provides: loading a link, and its own subtitle on/off + style. */
    public interface Loader { void load(String video, String subtitle, long startMs); }

    public interface Host {
        boolean subtitlesEnabled();
        void restyleSubtitles();
    }

    // subtitle origin
    private static final int SUB_NONE = 0, SUB_DECLARED = 1, SUB_LOCAL = 2, SUB_URL = 3;
    private static final long[] OFFSETS = {-5000, -2000, -1000, -500, 0, 500, 1000, 2000, 5000};
    private static final String[] OFFSET_LABELS = {"مبكرة 5 ثوانٍ", "مبكرة 2 ثانية", "مبكرة 1 ثانية", "مبكرة نصف ثانية", "بدون تعديل",
            "متأخرة نصف ثانية", "متأخرة 1 ثانية", "متأخرة 2 ثانية", "متأخرة 5 ثوانٍ"};

    // the run that WatchNow resolved just before opening the player (identity -> source)
    private static CigramMediaRequest seedRequest;
    private static CigramPlaybackSource seedSource;
    private static List<CigramPlaybackSource> seedAlternates;

    /** Called by CigramWatchNow right before it opens the player for a source the engine resolved. */
    static void seed(CigramMediaRequest request, CigramPlaybackSource chosen, List<CigramPlaybackSource> alternates) {
        seedRequest = request;
        seedSource = chosen;
        seedAlternates = new ArrayList<CigramPlaybackSource>(alternates);
    }

    private final Activity activity;
    private final ExoPlayer player;
    private final SubtitleView subtitleView;
    private final DefaultTrackSelector trackSelector;
    private final Host host;
    private final CigramEngine engine;
    private final CigramSubtitleOverlay overlay = new CigramSubtitleOverlay();

    private CigramMediaRequest request;
    private CigramPlaybackSource current;
    private ArrayList<CigramPlaybackSource> alternates = new ArrayList<CigramPlaybackSource>();
    private int subKind = SUB_NONE;
    private String subLabel = "";
    private CigramCancel subCancel;
    private boolean released;
    private boolean recovering;
    /** The link the player was last asked to load (to avoid offering the same failed link again). */
    private String currentLink;

    private static CigramPlayerBridge active;

    public static CigramPlayerBridge create(Activity a, ExoPlayer p, SubtitleView v, DefaultTrackSelector ts, Host host) {
        releaseActive();                              // one player screen at a time
        active = new CigramPlayerBridge(a, p, v, ts, host);
        return active;
    }

    /** The player screen is closing (its onStop releases the ExoPlayer): stop the overlay and any running search. */
    public static void releaseActive() {
        CigramPlayerBridge b = active;
        active = null;
        if (b != null) b.release();
    }

    private CigramPlayerBridge(Activity a, ExoPlayer p, SubtitleView v, DefaultTrackSelector ts, Host host) {
        this.activity = a;
        this.player = p;
        this.subtitleView = v;
        this.trackSelector = ts;
        this.host = host;
        this.engine = CigramEngine.get(a);
        overlay.attach(p, v);
        applySize();
        applyPosition();
    }

    // ---- subtitle size and height (the font, colour and background already come from the viewer's account settings)

    private static final String[] SIZE_LABELS = {"صغير", "متوسط", "كبير"};
    private static final float[] SIZE_FACTORS = {0.85f, 1.0f, 1.3f};
    private static final String[] POS_LABELS = {"منخفض", "متوسط", "مرتفع"};
    private static final float[] POS_FRACTIONS = {0.06f, 0.12f, 0.22f};     // 0.12 is what the player has always used

    private android.content.SharedPreferences subtitlePrefs() {
        return activity.getSharedPreferences("subtitle_settings", Activity.MODE_PRIVATE);
    }

    private int pref(String key, int max) {
        return Math.max(0, Math.min(max, subtitlePrefs().getInt(key, 1)));
    }

    private void applySize() {
        try { subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SIZE_FACTORS[pref("ext_size", 2)]); } catch (Throwable ignored) { }
    }

    private void applyPosition() {
        try { subtitleView.setBottomPaddingFraction(POS_FRACTIONS[pref("ext_pos", 2)]); } catch (Throwable ignored) { }
    }

    public String sizeSummary() { return SIZE_LABELS[pref("ext_size", 2)]; }
    public String[] sizeChoices() { return SIZE_LABELS; }
    public int sizeSelected() { return pref("ext_size", 2); }

    public String sizePick(int which) {
        subtitlePrefs().edit().putInt("ext_size", which).apply();
        applySize();
        return sizeSummary();
    }

    public String positionSummary() { return POS_LABELS[pref("ext_pos", 2)]; }
    public String[] positionChoices() { return POS_LABELS; }
    public int positionSelected() { return pref("ext_pos", 2); }

    public String positionPick(int which) {
        subtitlePrefs().edit().putInt("ext_pos", which).apply();
        applyPosition();
        return positionSummary();
    }

    public void release() {
        released = true;
        if (subCancel != null) subCancel.cancel();
        overlay.release();
        try { if (request != null) engine.coordinator.cancelIdentity(request.identityKey()); } catch (Throwable ignored) { }
    }

    // ================================================================== content identity

    /** Builds the identity of what is about to play from the intent extras and the current season / episode. */
    private CigramMediaRequest identify(String season, String episode) {
        Intent in = activity.getIntent();
        String origin = str(in, "cg_origin");
        String catalogId = str(in, "cg_catalog_id");
        String libraryId = str(in, "cg_library_id");
        String tmdbId = str(in, "cg_tmdb_id");
        String id = str(in, "id");
        String type = str(in, "type");
        String title = str(in, "series_title");
        if (title.length() == 0) title = str(in, "title");
        boolean isEp = season.length() > 0 || episode.length() > 0 || "series".equalsIgnoreCase(type);
        boolean tmdb = CigramMediaRef.ORIGIN_TMDB.equals(origin) && catalogId.length() > 0;
        if (!tmdb && libraryId.length() == 0) libraryId = isEp && str(in, "series_id").length() > 0 ? str(in, "series_id") : id;
        CigramMediaRef.Builder b = new CigramMediaRef.Builder()
                .kind(isEp ? CigramMediaRef.SERIES : CigramMediaRef.MOVIE)
                .origin(tmdb ? CigramMediaRef.ORIGIN_TMDB : CigramMediaRef.ORIGIN_LIBRARY)
                .catalogId(tmdb ? catalogId : "")
                .libraryId(tmdb ? "" : (libraryId.length() > 0 ? libraryId : "local_" + Integer.toHexString(title.hashCode())))
                .tmdbId(tmdbId)
                .title(title);
        try { b.year(Integer.parseInt(str(in, "cg_year"))); } catch (NumberFormatException ignored) { }
        CigramMediaRef ref = b.build();
        if (!isEp) return CigramMediaRequest.movie(ref, null);
        int s = parseInt(season, 1), e = parseInt(episode, 0);
        return CigramMediaRequest.episode(ref, new CigramEpisode(str(in, "cg_episode_id"), s, e, 0, "", "", "", "", 0, "", ""), null);
    }

    private static String str(Intent in, String k) {
        String v = in == null ? null : in.getStringExtra(k);
        return v == null ? "" : v.trim();
    }

    private static int parseInt(String s, int fallback) {
        try { return (int) Math.round(Double.parseDouble(s.trim())); } catch (Throwable e) { return fallback; }
    }

    // ================================================================== 1. loading + external subtitles

    /**
     * The player is about to load {@code video}. Registers what is playing, and prepares the subtitle:
     * a local file the viewer chose before for this very movie / episode comes first, then the link
     * the library or the provider declared. The video itself is not touched.
     */
    public void onLoading(String video, String declaredSubtitle, String season, String episode) {
        if (released) return;
        currentLink = video;
        CigramMediaRequest next = identify(season, episode);
        boolean sameContent = request != null && request.identityKey().equals(next.identityKey());
        request = next;
        if (!sameContent) {
            if (seedRequest != null && seedRequest.identityKey().equals(next.identityKey())) {
                current = seedSource;
                alternates = new ArrayList<CigramPlaybackSource>(seedAlternates);
            } else {
                current = null;
                alternates.clear();
            }
            seedRequest = null; seedSource = null; seedAlternates = null;
            clearSubtitle();
            // a new movie / episode: its own subtitle
            CigramSubtitleStore.Entry local = CigramSubtitleStore.recall(activity, CigramSubtitleStore.keyOf(next));
            if (local != null) loadLocal(local.uri, local.name, false);
            else if (declaredSubtitle != null && declaredSubtitle.length() > 0) loadDeclared(declaredSubtitle);
        }
        // otherwise this is a retry / source change of the same content: the subtitle stays as it is
    }

    private void loadDeclared(final String url) {
        if (subCancel != null) subCancel.cancel();
        final CigramCancel c = new CigramCancel();
        subCancel = c;
        engine.io.execute(new Runnable() {
            @Override public void run() {
                CigramSubtitles.Track t = null;
                try {
                    t = CigramSubtitles.fetch(url, null, CigramSubtitles.TRUST_PROVIDER, c);
                } catch (CigramEngineException e) {
                    CigramLog.d("player", "declared subtitle rejected: " + e.getMessage());
                }
                final CigramSubtitles.Track track = t;
                engine.poster.post(new Runnable() {
                    @Override public void run() {
                        if (released || c.isCancelled()) return;
                        if (track != null) applyTrack(track, SUB_DECLARED, "ترجمة العمل");
                    }
                });
            }
        });
    }

    private void loadLocal(final Uri uri, final String name, final boolean toastOnError) {
        if (subCancel != null) subCancel.cancel();
        final CigramCancel c = new CigramCancel();
        subCancel = c;
        engine.io.execute(new Runnable() {
            @Override public void run() {
                CigramSubtitles.Track t = null;
                String error = null;
                try {
                    t = CigramSubtitles.parse(CigramSubtitlePickActivity.read(activity.getContentResolver(), uri));
                } catch (CigramEngineException e) {
                    error = e.getMessage();
                } catch (Throwable e) {
                    error = "تعذر قراءة ملف الترجمة المحفوظ.";
                }
                final CigramSubtitles.Track track = t;
                final String err = error;
                engine.poster.post(new Runnable() {
                    @Override public void run() {
                        if (released || c.isCancelled()) return;
                        if (track != null) applyTrack(track, SUB_LOCAL, name == null || name.length() == 0 ? "ملف من الهاتف" : name);
                        else if (toastOnError) toast(err);
                    }
                });
            }
        });
    }

    private void applyTrack(CigramSubtitles.Track t, int kind, String label) {
        subKind = kind;
        subLabel = label;
        overlay.setTrack(t);
        overlay.setEnabled(true);
        // an embedded text track would be drawn on the same view: only one subtitle at a time
        try {
            if (trackSelector != null) {
                trackSelector.setParameters(trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true));
            }
        } catch (Throwable ignored) { }
        syncVisibility();
        if (host != null) host.restyleSubtitles();
    }

    private void clearSubtitle() {
        if (subCancel != null) subCancel.cancel();
        overlay.setTrack(null);
        subKind = SUB_NONE;
        subLabel = "";
        try {
            if (trackSelector != null) {
                trackSelector.setParameters(trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false));
            }
        } catch (Throwable ignored) { }
    }

    private void syncVisibility() {
        if (subtitleView == null) return;
        boolean on = host == null || host.subtitlesEnabled();
        subtitleView.setVisibility(on && subKind != SUB_NONE ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /** True when an external subtitle is loaded (the player's own subtitle toggle then governs it). */
    public boolean hasExternalSubtitle() { return subKind != SUB_NONE; }

    /** Called when the player's own subtitle toggle changes. */
    public void onSubtitleToggle(boolean on) {
        overlay.setEnabled(on);
        syncVisibility();
    }

    // ---- sheet rows

    public String subtitleSummary() { return subKind == SUB_NONE ? "لا توجد" : subLabel; }

    public String[] subtitleChoices() {
        ArrayList<String> l = new ArrayList<String>();
        l.add("اختيار ملف SRT / VTT من الهاتف");
        l.add("إدخال رابط ترجمة");
        if (subKind != SUB_NONE) l.add("إزالة الترجمة الخارجية");
        return l.toArray(new String[0]);
    }

    public int subtitleSelected() { return -1; }

    /** @return the text to show as the row's value afterwards */
    public String subtitlePick(int which) {
        if (which == 0) { pickFile(); return subtitleSummary(); }
        if (which == 1) { askUrl(); return subtitleSummary(); }
        if (subKind != SUB_NONE) {
            if (request != null) CigramSubtitleStore.forget(activity, CigramSubtitleStore.keyOf(request));
            clearSubtitle();
            syncVisibility();
        }
        return subtitleSummary();
    }

    private void pickFile() {
        CigramSubtitlePickActivity.pick(activity, new CigramSubtitlePickActivity.Callback() {
            @Override public void onPicked(CigramSubtitles.Track track, String displayName, Uri uri) {
                if (released) return;
                if (request != null) CigramSubtitleStore.remember(activity, CigramSubtitleStore.keyOf(request), uri, displayName);
                applyTrack(track, SUB_LOCAL, displayName);
                for (String w : track.warnings) CigramLog.d("player", "subtitle: " + w);
                toast("تم تحميل الترجمة: " + displayName + " (" + track.cues.size() + " سطر)");
            }
            @Override public void onError(String message) { toast(message); }
            @Override public void onCancelled() { }
        });
    }

    private void askUrl() {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://…/subtitle.srt");
        input.setSingleLine(true);
        input.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("رابط ملف الترجمة")
                .setMessage("رابط مباشر لملف .srt أو .vtt يبدأ بـ https. لا يُرفع شيء من جهازك.")
                .setView(input)
                .setPositiveButton("تحميل", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { loadUrl(input.getText().toString().trim()); }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void loadUrl(final String url) {
        if (url.length() == 0) return;
        if (subCancel != null) subCancel.cancel();
        final CigramCancel c = new CigramCancel();
        subCancel = c;
        toast("جاري تحميل الترجمة…");
        engine.io.execute(new Runnable() {
            @Override public void run() {
                CigramSubtitles.Track t = null;
                String error = null;
                try {
                    t = CigramSubtitles.fetch(url, null, CigramSubtitles.TRUST_MANUAL, c);
                } catch (CigramEngineException e) {
                    error = e.getMessage();
                }
                final CigramSubtitles.Track track = t;
                final String err = error;
                engine.poster.post(new Runnable() {
                    @Override public void run() {
                        if (released || c.isCancelled()) return;
                        if (track != null) {
                            applyTrack(track, SUB_URL, "رابط ترجمة");
                            toast("تم تحميل الترجمة (" + track.cues.size() + " سطر)");
                        } else {
                            toast(err == null ? "تعذر تحميل الترجمة." : err);
                        }
                    }
                });
            }
        });
    }

    public String offsetSummary() {
        long o = overlay.offsetMs();
        for (int i = 0; i < OFFSETS.length; i++) if (OFFSETS[i] == o) return OFFSET_LABELS[i];
        return (o / 1000.0) + " ث";
    }

    public String[] offsetChoices() { return OFFSET_LABELS; }

    public int offsetSelected() {
        for (int i = 0; i < OFFSETS.length; i++) if (OFFSETS[i] == overlay.offsetMs()) return i;
        return 4;
    }

    public String offsetPick(int which) {
        overlay.setOffsetMs(OFFSETS[which]);
        return offsetSummary();
    }

    // ================================================================== 2. episodes without a direct link

    /**
     * {@code MediaLoader.load} got an empty link. When the content is known to the engine, resolves
     * it (library link, then providers) and loads the result; returns false when the engine cannot
     * help, so the player shows its own "link not available" message.
     */
    public boolean resolveMissing(String season, String episode, final long startMs, final Loader loader) {
        if (released) return false;
        CigramMediaRequest req = identify(season, episode);
        if (req.tmdbId.length() == 0 && !(req.libraryId.length() > 0 && !req.libraryId.startsWith("local_"))) return false;
        // The screen already shows the new episode; do not keep playing the old one while its source is being found.
        try { player.pause(); } catch (Throwable ignored) { }
        resolveInto(req, null, startMs, loader, false);
        return true;
    }

    private void resolveInto(CigramMediaRequest req, String excludedKey, final long startMs, final Loader loader, final boolean closeOnGiveUp) {
        toast("جاري البحث عن مصدر التشغيل…");
        engine.coordinator.play(req, new CigramPlaybackCoordinator.Listener() {
            @Override public void onState(CigramMediaRequest r, int state, String message) {
                if (state == CigramPlaybackCoordinator.VALIDATING) toast(message);
            }
            @Override public void onReady(CigramMediaRequest r, CigramPlaybackSource chosen, List<CigramPlaybackSource> alts) {
                if (released) return;
                current = chosen;
                alternates = new ArrayList<CigramPlaybackSource>(alts);
                request = r;
                List<CigramPlaybackSource.Subtitle> declared = chosen.declaredSubtitles();
                loader.load(chosen.toStreamLink(), declared.isEmpty() ? "" : declared.get(0).url, startMs);
            }
            @Override public void onFailed(CigramMediaRequest r, String message, List<CigramPlaybackCoordinator.Report> reports, boolean retryable) {
                if (released) return;
                showFailure(message, reports, startMs, loader, closeOnGiveUp);
            }
        }, excludedKey);
    }

    // ================================================================== 3. failure -> another source of the same content

    /**
     * The player could not play the current link. Tries another source of the same movie / episode;
     * returns true when it took over (a source is being loaded, or the retry dialog is up).
     */
    public boolean onPlayerError(Throwable error, final long startMs, final Loader loader) {
        if (released || request == null || recovering) return false;
        recovering = true;
        final CigramMediaRequest req = request;
        String key = current == null ? "" : current.sourceKey();
        CigramLog.d("player", "playback failed: " + (error == null ? "" : error.getClass().getSimpleName()));
        if (current != null) {
            engine.coordinator.reportPlaybackFailure(req, current, error == null ? "" : "فشل تشغيل المصدر.", new CigramPlaybackCoordinator.Listener() {
                @Override public void onState(CigramMediaRequest r, int state, String message) { }
                @Override public void onReady(CigramMediaRequest r, CigramPlaybackSource chosen, List<CigramPlaybackSource> alts) {
                    recovering = false;
                    if (released) return;
                    current = chosen;
                    alternates = new ArrayList<CigramPlaybackSource>(alts);
                    toast("تم التبديل إلى: " + chosen.displayName);
                    List<CigramPlaybackSource.Subtitle> declared = chosen.declaredSubtitles();
                    loader.load(chosen.toStreamLink(), subKind == SUB_NONE && !declared.isEmpty() ? declared.get(0).url : "", startMs);
                }
                @Override public void onFailed(CigramMediaRequest r, String message, List<CigramPlaybackCoordinator.Report> reports, boolean retryable) {
                    recovering = false;
                    if (!released) showFailure(message, reports, startMs, loader, true);
                }
            });
            return true;
        }
        // a plain library link failed: look for another source of the same content, never the same link again
        engine.coordinator.play(req, new CigramPlaybackCoordinator.Listener() {
            @Override public void onState(CigramMediaRequest r, int state, String message) { }
            @Override public void onReady(CigramMediaRequest r, CigramPlaybackSource chosen, List<CigramPlaybackSource> alts) {
                recovering = false;
                if (released) return;
                current = chosen;
                alternates = new ArrayList<CigramPlaybackSource>(alts);
                toast("تم التبديل إلى: " + chosen.displayName);
                loader.load(chosen.toStreamLink(), "", startMs);
            }
            @Override public void onFailed(CigramMediaRequest r, String message, List<CigramPlaybackCoordinator.Report> reports, boolean retryable) {
                recovering = false;
                if (!released) showFailure("تعذر تشغيل الفيديو.", reports, startMs, loader, true);
            }
        }, lastLoadedKey());
        return true;
    }

    private String lastLoadedKey() {
        return currentLink == null ? "" : "library|" + CigramStreamLink.cleanUrl(currentLink);
    }

    /**
     * @param closeOnGiveUp true when nothing is left to watch (the current video failed): "close" leaves the player.
     *                      false when the viewer was only switching episode: the previous episode is still playing,
     *                      so "close" just dismisses the message.
     */
    private void showFailure(String message, List<CigramPlaybackCoordinator.Report> reports, final long startMs, final Loader loader,
                             final boolean closeOnGiveUp) {
        if (released || activity.isFinishing()) return;
        StringBuilder detail = new StringBuilder(message);
        if (reports != null && !reports.isEmpty()) {
            detail.append("\n");
            int n = 0;
            for (CigramPlaybackCoordinator.Report r : reports) {
                if (n++ >= 6) break;
                detail.append("\n• ").append(r.displayName).append(": ").append(r.note);
            }
        }
        AlertDialog.Builder b = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("تعذر تشغيل الفيديو")
                .setMessage(detail.toString())
                .setPositiveButton("إعادة المحاولة", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (request != null) resolveInto(request, null, startMs, loader, closeOnGiveUp);
                    }
                })
                .setNegativeButton(closeOnGiveUp ? "إغلاق المشغل" : "حسناً", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { if (closeOnGiveUp) activity.finish(); }
                })
                .setCancelable(!closeOnGiveUp);
        try { b.show(); } catch (Throwable ignored) { }
    }

    // ================================================================== 4. choosing a source

    public boolean hasSourceChoices() { return current != null && !alternates.isEmpty(); }

    public String sourceSummary() { return current == null ? "—" : label(current); }

    public String[] sourceChoices() {
        ArrayList<String> l = new ArrayList<String>();
        if (current != null) l.add(label(current));
        for (CigramPlaybackSource s : alternates) l.add(label(s));
        return l.toArray(new String[0]);
    }

    public int sourceSelected() { return 0; }

    /** The viewer picked another source: validate it, then reload the same content from the same position. */
    public String sourcePick(int which, final long startMs, final Loader loader) {
        if (which <= 0 || which > alternates.size() || request == null) return sourceSummary();
        final CigramPlaybackSource chosen = alternates.get(which - 1);
        toast("جاري التحقق من المصدر…");
        engine.coordinator.playSpecific(request, chosen, new CigramPlaybackCoordinator.Listener() {
            @Override public void onState(CigramMediaRequest r, int state, String message) { }
            @Override public void onReady(CigramMediaRequest r, CigramPlaybackSource ok, List<CigramPlaybackSource> alts) {
                if (released) return;
                ArrayList<CigramPlaybackSource> rest = new ArrayList<CigramPlaybackSource>(alternates);
                rest.remove(chosen);
                if (current != null) rest.add(0, current);
                current = ok;
                alternates = rest;
                loader.load(ok.toStreamLink(), "", startMs);
            }
            @Override public void onFailed(CigramMediaRequest r, String message, List<CigramPlaybackCoordinator.Report> reports, boolean retryable) {
                toast("هذا المصدر لا يعمل الآن.");
            }
        });
        return label(chosen);
    }

    private static String label(CigramPlaybackSource s) {
        return s.displayName + (s.quality.length() > 0 ? " · " + s.quality : "");
    }

    // ================================================================== misc

    private void toast(String m) {
        if (m == null || m.length() == 0 || released) return;
        try { Toast.makeText(activity, m, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
    }
}
