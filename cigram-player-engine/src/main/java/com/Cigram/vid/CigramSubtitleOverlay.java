package com.Cigram.vid;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.ui.SubtitleView;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws an external subtitle (a parsed {@link CigramSubtitles.Track}) on the player's own
 * SubtitleView, driven by the player's playback position.
 *
 * Why not hand the file to ExoPlayer as a MediaItem subtitle: changing a subtitle that way means
 * replacing the media item (the video stalls and re-buffers), the player only reads UTF-8 and cannot
 * shift timing. Here a subtitle can be switched, removed, delayed or advanced WHILE the video plays,
 * with no restart, and the style the viewer already chose (font, colour, background) is the
 * SubtitleView's own, so it still applies.
 *
 * It follows the position by polling every 100 ms (no Player.Listener: the Sketchware D8 build of this
 * app crashes inside ExoPlayer's ListenerSet, the other players avoid listeners for the same reason).
 * Must be created, used and released on the main thread.
 */
public final class CigramSubtitleOverlay {
    private static final long TICK_MS = 100L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private SubtitleView view;
    private CigramSubtitles.Track track;
    private long offsetMs;
    private boolean enabled = true;
    private boolean running;
    private List<CigramSubtitles.Cue> shown = new ArrayList<CigramSubtitles.Cue>();
    private boolean shownAnything;
    private long lastDraw;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            update();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /** Binds the overlay to a player and the view it draws on. Replaces an earlier binding. */
    public void attach(ExoPlayer p, SubtitleView v) {
        this.player = p;
        this.view = v;
        if (track != null) start();
    }

    /** Switches to this subtitle immediately (null = no external subtitle). The video is untouched. */
    public void setTrack(CigramSubtitles.Track t) {
        this.track = t;
        clear();
        if (t != null) start(); else stop();
    }

    public CigramSubtitles.Track track() { return track; }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) clear();
        else if (track != null) { start(); update(); }
    }

    public boolean isEnabled() { return enabled; }

    /** Positive = subtitles appear later, negative = earlier. */
    public void setOffsetMs(long ms) { offsetMs = ms; update(); }

    public long offsetMs() { return offsetMs; }

    public void release() {
        stop();
        clear();
        player = null;
        view = null;
        track = null;
    }

    private void start() {
        if (running || player == null || view == null) return;
        running = true;
        handler.post(tick);
    }

    private void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    private void clear() {
        shown = new ArrayList<CigramSubtitles.Cue>();
        if (shownAnything && view != null) {
            try { view.setCues(new ArrayList<Cue>()); } catch (Throwable ignored) { }
        }
        shownAnything = false;
    }

    private void update() {
        ExoPlayer p = player;
        SubtitleView v = view;
        CigramSubtitles.Track t = track;
        if (p == null || v == null || t == null || !enabled) return;
        long pos;
        try { pos = p.getCurrentPosition() - offsetMs; } catch (Throwable ignored) { return; }
        List<CigramSubtitles.Cue> now = t.activeAt(pos);
        long clock = android.os.SystemClock.uptimeMillis();
        // Same cues as before: nothing to draw, except once a second in case PlayerView cleared the view (it does so on re-attach).
        if (same(now, shown) && (!shownAnything || clock - lastDraw < 1000L)) return;
        lastDraw = clock;
        shown = now;
        ArrayList<Cue> out = new ArrayList<Cue>(now.size());
        for (CigramSubtitles.Cue c : now) out.add(toExoCue(c));
        try { v.setCues(out); } catch (Throwable ignored) { }
        shownAnything = !out.isEmpty();
    }

    private static boolean same(List<CigramSubtitles.Cue> a, List<CigramSubtitles.Cue> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    static Cue toExoCue(CigramSubtitles.Cue c) {
        Cue.Builder b = new Cue.Builder().setText(styled(c));
        if (c.top) b.setLine(0.1f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START);
        return b.build();
    }

    /** The text shown for a cue and its style ranges ({type, start, end}), after right-to-left marks were added. */
    static final class Layout {
        final String text;
        final List<int[]> spans;
        Layout(String text, List<int[]> spans) { this.text = text; this.spans = spans; }
    }

    /**
     * Adds a right-to-left mark around every right-to-left line (so punctuation stays on the correct side of an
     * Arabic sentence) and moves the style ranges to match. Pure Java: unit-tested.
     */
    static Layout layout(CigramSubtitles.Cue c) {
        String text = c.text;
        StringBuilder out = new StringBuilder(text.length() + 8);
        int[] map = new int[text.length() + 1];
        int i = 0;
        String[] lines = text.split("\n", -1);
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            boolean rtl = CigramText.mostlyRtl(line);
            if (li > 0) { map[i] = out.length(); out.append('\n'); i++; }
            if (rtl) out.append('\u200F');
            for (int k = 0; k < line.length(); k++) { map[i] = out.length(); out.append(line.charAt(k)); i++; }
            if (rtl) out.append('\u200F');
        }
        map[text.length()] = out.length();
        ArrayList<int[]> spans = new ArrayList<int[]>();
        for (int[] sp : c.spans) {
            int from = Math.min(sp[1], text.length()), to = Math.min(sp[2], text.length());
            if (to <= from) continue;
            int a = map[from];
            int e = map[to - 1] + 1;                 // just after the last styled character, not after a trailing mark
            if (e > a) spans.add(new int[]{sp[0], a, e});
        }
        return new Layout(out.toString(), spans);
    }

    /** The cue text with its italic / bold / underline ranges, ready for the SubtitleView. */
    static CharSequence styled(CigramSubtitles.Cue c) {
        Layout l = layout(c);
        SpannableStringBuilder sb = new SpannableStringBuilder(l.text);
        for (int[] sp : l.spans) {
            if (sp[0] == CigramSubtitles.SPAN_ITALIC) sb.setSpan(new StyleSpan(Typeface.ITALIC), sp[1], sp[2], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else if (sp[0] == CigramSubtitles.SPAN_BOLD) sb.setSpan(new StyleSpan(Typeface.BOLD), sp[1], sp[2], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            else if (sp[0] == CigramSubtitles.SPAN_UNDERLINE) sb.setSpan(new UnderlineSpan(), sp[1], sp[2], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }
}
