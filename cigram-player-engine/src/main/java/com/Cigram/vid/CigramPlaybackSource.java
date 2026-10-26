package com.Cigram.vid;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One way to play one movie / episode, as a provider reports it and as the validator judged it.
 * Providers fill the identity and link fields; {@link #availability} / {@link #failureReason}
 * describe what is known about it. Nothing in here is ever logged without {@link CigramLog#redact}.
 */
public final class CigramPlaybackSource {
    public static final int UNKNOWN = 0;        // reported by a provider, not checked yet
    public static final int AVAILABLE = 1;      // checked: a real, parsable media stream
    public static final int UNAVAILABLE = 2;    // checked: failed (see failureReason)
    public static final int NEEDS_AUTH = 3;     // provider asks for a login / key we do not have
    public static final int EMBED_ONLY = 4;     // only an embeddable web page, no media link
    public static final int NOT_INTEGRATED = 5; // provider has no usable integration yet
    public static final int DRM = 6;            // protected stream: needs the owner's licence

    public static final class Subtitle {
        public final String url;
        public final String language;
        public final String label;
        /** True when the provider (not a random page) declared it; only these are fetched automatically. */
        public final boolean providerDeclared;

        public Subtitle(String url, String language, String label, boolean providerDeclared) {
            this.url = CigramMediaRef.nz(url);
            this.language = CigramMediaRef.nz(language);
            this.label = CigramMediaRef.nz(label);
            this.providerDeclared = providerDeclared;
        }
    }

    public String providerId = "";
    public String displayName = "";
    public String playbackUri = "";
    public String mimeType = "";                 // application/x-mpegURL, video/mp4 ... "" = unknown
    public final HashMap<String, String> headers = new HashMap<String, String>();
    public final ArrayList<Subtitle> supportedSubtitles = new ArrayList<Subtitle>();
    public String quality = "";                  // "1080p", "720p" ... (label only)
    public boolean isPlayable;
    public int availability = UNKNOWN;
    public String failureReason = "";
    /** {@link CigramMediaRequest#identityKey()} of the request this source was resolved for. */
    public String requestKey = "";
    public int priority;                         // lower = tried first

    public CigramPlaybackSource() { }

    public CigramPlaybackSource copy() {
        CigramPlaybackSource c = new CigramPlaybackSource();
        c.providerId = providerId; c.displayName = displayName; c.playbackUri = playbackUri; c.mimeType = mimeType;
        c.headers.putAll(headers); c.supportedSubtitles.addAll(supportedSubtitles); c.quality = quality;
        c.isPlayable = isPlayable; c.availability = availability; c.failureReason = failureReason;
        c.requestKey = requestKey; c.priority = priority;
        return c;
    }

    public CigramPlaybackSource markAvailable(String mime) {
        availability = AVAILABLE;
        isPlayable = true;
        failureReason = "";
        if (mime != null && mime.length() > 0) mimeType = mime;
        return this;
    }

    public CigramPlaybackSource markFailed(int status, String reason) {
        availability = status;
        isPlayable = false;
        failureReason = reason == null ? "" : reason;
        return this;
    }

    /** Stable identity inside one request (provider + link without volatile query noise). */
    public String sourceKey() {
        return providerId + "|" + playbackUri;
    }

    public List<Subtitle> declaredSubtitles() {
        ArrayList<Subtitle> out = new ArrayList<Subtitle>();
        for (Subtitle s : supportedSubtitles) if (s.providerDeclared && s.url.length() > 0) out.add(s);
        return out;
    }

    /**
     * The link in the format {@code CigramStreams} already understands: {@code url|Header=value&Other=value}.
     * Used so the existing players keep their one place that builds ExoPlayer data sources.
     */
    public String toStreamLink() {
        if (headers.isEmpty()) return playbackUri;
        StringBuilder sb = new StringBuilder(playbackUri).append('|');
        boolean first = true;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!CigramHttp.safeHeader(e.getKey(), e.getValue())) continue;
            if (!first) sb.append('&');
            first = false;
            try {
                sb.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
            } catch (UnsupportedEncodingException impossible) {
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return first ? playbackUri : sb.toString();
    }

    @Override public String toString() {
        return providerId + " [" + availability + "] " + CigramLog.redact(playbackUri);
    }
}
