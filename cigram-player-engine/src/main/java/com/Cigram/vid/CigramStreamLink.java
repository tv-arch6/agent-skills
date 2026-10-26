package com.Cigram.vid;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The "url|Header=value&Other=value" link syntax the app already uses (see CigramStreams). Kept
 * here as a tiny dependency-free copy of the two parsing functions so the engine core does not need
 * ExoPlayer on its class path; CigramStreams stays the one place that talks to ExoPlayer.
 */
public final class CigramStreamLink {
    private CigramStreamLink() { }

    public static String cleanUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        int bar = u.indexOf('|');
        return bar >= 0 ? u.substring(0, bar).trim() : u;
    }

    public static Map<String, String> headersOf(String url) {
        HashMap<String, String> out = new HashMap<String, String>();
        if (url == null) return out;
        int bar = url.indexOf('|');
        if (bar < 0) return out;
        for (String part : url.substring(bar + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            try { v = URLDecoder.decode(v, "UTF-8"); } catch (Throwable ignored) { }
            if (CigramHttp.safeHeader(k, v)) out.put(k, v);
        }
        return out;
    }

    /** Container / manifest type the link itself suggests ("hls", "dash", "mp4", "mkv", "webm", "ts", "") – a hint only. */
    public static String kindHint(String url) {
        String full = cleanUrl(url).toLowerCase(Locale.US);
        int q = full.indexOf('?');
        String path = q >= 0 ? full.substring(0, q) : full;
        if (path.endsWith(".m3u8") || path.endsWith(".m3u") || path.contains(".m3u8/")) return "hls";
        if (path.endsWith(".mpd") || path.contains(".mpd/")) return "dash";
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov")) return "mp4";
        if (path.endsWith(".mkv")) return "mkv";
        if (path.endsWith(".webm")) return "webm";
        if (path.endsWith(".ts") || path.endsWith(".m2ts") || path.endsWith(".mts")) return "ts";
        if (full.contains("m3u8") || full.contains("type=hls") || full.contains("/hls/")) return "hls";
        if (full.contains("format=mpd") || full.contains("/dash/") || full.contains("type=dash")) return "dash";
        return "";
    }
}
