package com.Cigram.vid;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Decides whether a source really is a playable video, before the player is bothered with it.
 * "HTTP 200" is never enough:
 *
 *  - a page (HTML / JSON / text) behind a video-looking link is rejected;
 *  - an HLS link must be a real playlist whose first variant and first segment answer and look like
 *    media (MPEG-TS or fragmented MP4), not a page;
 *  - a DASH link must be a manifest with a video representation;
 *  - MP4 / MKV / WebM / TS / FLV links are checked on their first bytes (magic numbers, audio-only
 *    MP4 brands are rejected);
 *  - DRM-protected streams are reported as DRM (they need the owner's licence), never bypassed.
 *
 * What this cannot prove: that every codec of the stream is decodable on this device. That final
 * check belongs to ExoPlayer; the coordinator moves to the next source when the player reports an
 * error for a source this class accepted.
 */
public final class CigramSourceValidator {

    public static final class Result {
        public final boolean ok;
        public final int status;       // CigramPlaybackSource.AVAILABLE / UNAVAILABLE / DRM / NEEDS_AUTH ...
        public final String kind;      // hls, dash, mp4, mkv, webm, ts, flv, "" when failed
        public final String mime;
        public final String reason;    // Arabic, safe to show; "" when ok
        public final boolean retryable;

        Result(boolean ok, int status, String kind, String mime, String reason, boolean retryable) {
            this.ok = ok; this.status = status; this.kind = kind; this.mime = mime; this.reason = reason; this.retryable = retryable;
        }

        static Result ok(String kind, String mime) { return new Result(true, CigramPlaybackSource.AVAILABLE, kind, mime, "", false); }

        static Result fail(int status, String reason, boolean retryable) { return new Result(false, status, "", "", reason, retryable); }

        static Result unavailable(String reason) { return fail(CigramPlaybackSource.UNAVAILABLE, reason, false); }
    }

    private static final CigramSourceValidator SHARED = new CigramSourceValidator();

    public static CigramSourceValidator shared() { return SHARED; }

    private int probeBytes = 8192;
    private int connectMs = 6000;
    private int readMs = 9000;

    public CigramSourceValidator() { }

    public CigramSourceValidator timeouts(int connect, int read) { this.connectMs = connect; this.readMs = read; return this; }

    // ================================================================== network validation

    public Result validate(CigramPlaybackSource s, CigramCancel cancel) {
        try {
            return validateOrThrow(s, cancel);
        } catch (CigramEngineException e) {
            if (e.kind == CigramEngineException.CANCELLED) return Result.fail(CigramPlaybackSource.UNKNOWN, "أُلغي الفحص.", false);
            int st = e.kind == CigramEngineException.AUTH ? CigramPlaybackSource.NEEDS_AUTH : CigramPlaybackSource.UNAVAILABLE;
            return Result.fail(st, e.getMessage(), e.retryable());
        } catch (Throwable t) {
            CigramLog.w("validator", "unexpected failure", t);
            return Result.unavailable("تعذر فحص المصدر.");
        }
    }

    private Result validateOrThrow(CigramPlaybackSource s, CigramCancel cancel) throws CigramEngineException {
        switch (s.availability) {
            case CigramPlaybackSource.EMBED_ONLY:
            case CigramPlaybackSource.DRM:
            case CigramPlaybackSource.NEEDS_AUTH:
            case CigramPlaybackSource.NOT_INTEGRATED:
                return Result.fail(s.availability, s.failureReason.length() > 0 ? s.failureReason : "المصدر غير قابل للتشغيل المباشر.", false);
            default:
                break;
        }
        String url = s.playbackUri == null ? "" : s.playbackUri.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) return Result.unavailable("لا يوجد رابط تشغيل صالح.");

        CigramHttp.Request req = new CigramHttp.Request(url).headers(s.headers).range(0, probeBytes - 1).max(probeBytes);
        req.identityEncoding = true;
        req.timeouts(connectMs, readMs);
        CigramHttp.Response res = CigramHttp.execute(req, cancel);
        Result bad = httpProblem(res);
        if (bad != null) return bad;

        String ct = res.contentType.toLowerCase(Locale.US);
        String kind = sniffKind(res.body, res.body.length);
        if (ct.startsWith("text/html") || "html".equals(kind)) return Result.fail(CigramPlaybackSource.UNAVAILABLE, "الرابط يعيد صفحة ويب وليس فيديو.", false);

        if ("hls".equals(kind)) return checkHls(s, res, cancel);
        if ("dash".equals(kind)) return checkDash(s, res, cancel);
        if ("ss".equals(kind)) return Result.unavailable("بث Smooth Streaming غير مدعوم في هذا المشغل.");
        if ("mp4".equals(kind) || "mkv".equals(kind) || "webm".equals(kind) || "ts".equals(kind) || "flv".equals(kind)) {
            return Result.ok(kind, mimeOfKind(kind));
        }
        if ("mp4-audio".equals(kind) || "mp3".equals(kind) || "aac".equals(kind) || "ogg".equals(kind)) {
            return Result.unavailable("المصدر صوت فقط وليس فيديو.");
        }
        if ("json".equals(kind) || "text".equals(kind)) return Result.unavailable("الرابط لا يشير إلى ملف فيديو.");
        return Result.unavailable("صيغة الملف غير معروفة أو غير مدعومة.");
    }

    private static Result httpProblem(CigramHttp.Response res) {
        if (res.code == 200 || res.code == 206) return null;
        CigramEngineException e = CigramEngineException.http(res.code);
        int st = e.kind == CigramEngineException.AUTH ? CigramPlaybackSource.NEEDS_AUTH : CigramPlaybackSource.UNAVAILABLE;
        return Result.fail(st, e.getMessage(), e.retryable());
    }

    // ------------------------------------------------------------------ HLS

    private Result checkHls(CigramPlaybackSource s, CigramHttp.Response first, CigramCancel cancel) throws CigramEngineException {
        String text = fullText(s, first, cancel);
        if (!CigramSourceValidator.startsWithExtM3u(text)) return Result.unavailable("قائمة التشغيل غير صالحة.");
        if (hlsIsDrm(text)) return Result.fail(CigramPlaybackSource.DRM, "المصدر محمي بـ DRM ويحتاج ترخيص صاحب المحتوى.", false);

        String mediaText = text;
        String mediaBase = first.url;
        String variant = firstVariantUri(text);
        if (variant != null) {
            mediaBase = resolve(first.url, variant);
            if (mediaBase == null) return Result.unavailable("رابط جودة غير صالح داخل القائمة.");
            CigramHttp.Request vr = new CigramHttp.Request(mediaBase).headers(s.headers).max(512 * 1024).timeouts(connectMs, readMs);
            vr.identityEncoding = true;
            CigramHttp.Response vres = CigramHttp.execute(vr, cancel);
            Result bad = httpProblem(vres);
            if (bad != null) return Result.fail(bad.status, "قائمة الجودة لا تستجيب: " + bad.reason, bad.retryable);
            mediaText = vres.text();
            mediaBase = vres.url;
            if (!startsWithExtM3u(mediaText)) return Result.unavailable("قائمة الجودة ليست قائمة تشغيل صالحة.");
            if (hlsIsDrm(mediaText)) return Result.fail(CigramPlaybackSource.DRM, "المصدر محمي بـ DRM ويحتاج ترخيص صاحب المحتوى.", false);
        }
        String segment = firstSegmentUri(mediaText);
        if (segment == null) return Result.unavailable("قائمة التشغيل لا تحتوي مقاطع فيديو.");
        String segUrl = resolve(mediaBase, segment);
        if (segUrl == null) return Result.unavailable("رابط مقطع غير صالح.");

        CigramHttp.Request sr = new CigramHttp.Request(segUrl).headers(s.headers).range(0, 1023).max(1024).timeouts(connectMs, readMs);
        sr.identityEncoding = true;
        CigramHttp.Response sres = CigramHttp.execute(sr, cancel);
        Result bad = httpProblem(sres);
        if (bad != null) return Result.fail(bad.status, "مقاطع الفيديو لا تستجيب: " + bad.reason, bad.retryable);
        String sct = sres.contentType.toLowerCase(Locale.US);
        String sk = sniffKind(sres.body, sres.body.length);
        if (sct.startsWith("text/html") || "html".equals(sk) || "json".equals(sk)) {
            return Result.unavailable("مقاطع الفيديو تعيد صفحة ويب وليس وسائط.");
        }
        if (!("ts".equals(sk) || "mp4".equals(sk) || "mp4-audio".equals(sk) || "aac".equals(sk) || "mp3".equals(sk) || "mkv".equals(sk) || "webm".equals(sk))) {
            // AES-128 encrypted segments look like random bytes: acceptable only when the playlist declares the key.
            if (!(mediaText.contains("#EXT-X-KEY") && mediaText.contains("METHOD=AES-128"))) {
                return Result.unavailable("مقاطع الفيديو بصيغة غير معروفة.");
            }
        }
        return Result.ok("hls", "application/x-mpegURL");
    }

    static boolean startsWithExtM3u(String text) {
        if (text == null) return false;
        String t = text;
        if (t.length() > 0 && t.charAt(0) == '﻿') t = t.substring(1);
        return t.trim().startsWith("#EXTM3U");
    }

    static boolean hlsIsDrm(String text) {
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (!(l.startsWith("#EXT-X-KEY") || l.startsWith("#EXT-X-SESSION-KEY"))) continue;
            String u = l.toUpperCase(Locale.US);
            if (u.contains("METHOD=SAMPLE-AES") || u.contains("WIDEVINE") || u.contains("PLAYREADY") || u.contains("STREAMINGKEYDELIVERY")
                    || u.contains("EDEF8BA9") || u.contains("9A04F079")) return true;
        }
        return false;
    }

    /** The URI line that follows the first #EXT-X-STREAM-INF, or null for a media playlist. */
    static String firstVariantUri(String text) {
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().startsWith("#EXT-X-STREAM-INF")) continue;
            for (int j = i + 1; j < lines.length; j++) {
                String l = lines[j].trim();
                if (l.length() == 0 || l.startsWith("#")) continue;
                return l;
            }
        }
        return null;
    }

    /** The first media segment (or init section) of a media playlist, or null when there is none. */
    static String firstSegmentUri(String text) {
        String[] lines = text.split("\\r?\\n");
        boolean sawInfo = false;
        for (String raw : lines) {
            String l = raw.trim();
            if (l.startsWith("#EXTINF")) { sawInfo = true; continue; }
            if (l.length() == 0 || l.startsWith("#")) continue;
            if (sawInfo) return l;
        }
        return null;
    }

    // ------------------------------------------------------------------ DASH

    private Result checkDash(CigramPlaybackSource s, CigramHttp.Response first, CigramCancel cancel) throws CigramEngineException {
        String text = fullText(s, first, cancel);
        if (!text.contains("<MPD")) return Result.unavailable("ملف DASH غير صالح.");
        if (text.contains("<ContentProtection")) {
            return Result.fail(CigramPlaybackSource.DRM, "المصدر محمي بـ DRM ويحتاج ترخيص صاحب المحتوى.", false);
        }
        boolean video = text.contains("contentType=\"video\"") || text.contains("mimeType=\"video/");
        if (!video || !text.contains("<Representation")) return Result.unavailable("ملف DASH لا يحتوي مسار فيديو.");
        return Result.ok("dash", "application/dash+xml");
    }

    // ------------------------------------------------------------------ helpers

    /** A playlist may be larger than the probe: fetch it whole (<= 512 KB) when the probe was cut. */
    private String fullText(CigramPlaybackSource s, CigramHttp.Response first, CigramCancel cancel) throws CigramEngineException {
        if (!first.truncated && first.code == 200) return first.text();
        CigramHttp.Request r = new CigramHttp.Request(first.url).headers(s.headers).max(512 * 1024).timeouts(connectMs, readMs);
        r.identityEncoding = true;
        CigramHttp.Response full = CigramHttp.execute(r, cancel);
        return full.ok() ? full.text() : first.text();
    }

    static String resolve(String base, String ref) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    static String mimeOfKind(String kind) {
        if ("hls".equals(kind)) return "application/x-mpegURL";
        if ("dash".equals(kind)) return "application/dash+xml";
        if ("mp4".equals(kind)) return "video/mp4";
        if ("mkv".equals(kind)) return "video/x-matroska";
        if ("webm".equals(kind)) return "video/webm";
        if ("ts".equals(kind)) return "video/mp2t";
        if ("flv".equals(kind)) return "video/x-flv";
        return "";
    }

    // ================================================================== byte sniffing (pure)

    /**
     * Names the content from its first bytes: hls, dash, ss, mp4, mp4-audio, mkv, webm, ts, flv,
     * mp3, aac, ogg, html, json, text, or "" when it cannot tell.
     */
    public static String sniffKind(byte[] b, int n) {
        if (b == null || n <= 0) return "";
        n = Math.min(n, b.length);
        int start = 0;
        if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) start = 3;

        if (n >= 12 && ascii(b, 4, "ftyp")) {
            String brand = n >= 12 ? new String(b, 8, 4, java.nio.charset.Charset.forName("ISO-8859-1")) : "";
            return brand.startsWith("M4A") || brand.startsWith("M4B") ? "mp4-audio" : "mp4";
        }
        if (n >= 8 && (ascii(b, 4, "moov") || ascii(b, 4, "mdat") || ascii(b, 4, "moof") || ascii(b, 4, "styp")
                || ascii(b, 4, "free") || ascii(b, 4, "wide") || ascii(b, 4, "skip"))) return "mp4";
        if (n >= 4 && (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) {
            String head = new String(b, 0, Math.min(n, 64), java.nio.charset.Charset.forName("ISO-8859-1"));
            return head.contains("webm") ? "webm" : "mkv";
        }
        if (n >= 189 && (b[0] & 0xFF) == 0x47 && (b[188] & 0xFF) == 0x47) return "ts";
        if (n >= 4 && ascii(b, 0, "FLV") && b[3] == 1) return "flv";
        if (n >= 4 && ascii(b, 0, "OggS")) return "ogg";
        if (n >= 3 && ascii(b, 0, "ID3")) return "mp3";
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && ((b[1] & 0xF6) == 0xF0)) return "aac";
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && ((b[1] & 0xE0) == 0xE0)) return "mp3";

        // text based
        int i = start;
        while (i < n && (b[i] == ' ' || b[i] == '\t' || b[i] == '\r' || b[i] == '\n')) i++;
        int len = Math.min(n - i, 600);
        if (len <= 0) return "";
        String t = new String(b, i, len, java.nio.charset.Charset.forName("ISO-8859-1"));
        String lower = t.toLowerCase(Locale.US);
        if (lower.startsWith("#extm3u")) return "hls";
        if (lower.startsWith("<!doctype html") || lower.startsWith("<html") || lower.startsWith("<head") || lower.startsWith("<body")
                || lower.startsWith("<script") || lower.startsWith("<?php") || lower.startsWith("<!--")) return "html";
        if (lower.contains("<mpd")) return "dash";
        if (lower.contains("<smoothstreamingmedia")) return "ss";
        if (lower.startsWith("<?xml") && (lower.contains("<html") || lower.contains("<!doctype html"))) return "html";
        if (lower.startsWith("{") || lower.startsWith("[")) return "json";
        int printable = 0;
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '\n' || c == '\r' || c == '\t' || (c >= 32 && c < 127)) printable++;
        }
        return printable * 100 / t.length() > 90 ? "text" : "";
    }

    private static boolean ascii(byte[] b, int off, String s) {
        if (off + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) if (b[off + i] != (byte) s.charAt(i)) return false;
        return true;
    }

    /** Convenience for callers that only want the playable subset of a list. */
    public static ArrayList<CigramPlaybackSource> playableOnly(java.util.List<CigramPlaybackSource> in) {
        ArrayList<CigramPlaybackSource> out = new ArrayList<CigramPlaybackSource>();
        for (CigramPlaybackSource s : in) if (s.isPlayable) out.add(s);
        return out;
    }
}
