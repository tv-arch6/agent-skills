package com.Cigram.vid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Adapter for a provider that offers a documented, authorised HTTP API returning this JSON:
 *
 * <pre>
 * GET {api}/resolve?type=movie|episode&amp;tmdb=..&amp;imdb=..&amp;title=..&amp;year=..&amp;season=..&amp;episode=..
 *     Authorization: Bearer {token}            (only when a token is configured)
 * 200 { "sources": [ { "url": "https://.../index.m3u8", "type": "hls|dash|mp4|mkv|webm|embed",
 *                       "quality": "1080p", "headers": { "Referer": "..." }, "drm": false,
 *                       "subtitles": [ { "url": "https://.../ar.vtt", "lang": "ar", "label": "العربية" } ] } ] }
 * 200 { "error": "login_required" }     401/403                 -> needs credentials
 * </pre>
 *
 * It is the integration point for any provider that gives the app an authorised API (or for a
 * resolver service the app's owner runs). A provider whose real API differs subclasses this and
 * overrides {@link #buildUrl} / {@link #parse}. With no API configured it reports NEEDS_API and
 * resolves nothing: it never guesses a link.
 */
public class CigramJsonProvider extends CigramBaseProvider {
    protected final CigramEngineConfig config;
    private final String note;

    public CigramJsonProvider(String id, String displayName, int priority, CigramEngineConfig config, String note) {
        super(id, displayName, config.providerPriority(id, priority));
        this.config = config;
        this.note = note;
    }

    @Override public int status() {
        if (!config.providerEnabled(id)) return DISABLED;
        return apiBaseOk(config.providerApi(id)) ? INTEGRATED : NEEDS_API;
    }

    @Override public String statusNote() {
        if (!config.providerEnabled(id)) return "متوقف من الإعدادات.";
        String api = config.providerApi(id);
        if (api.length() == 0) return note + " لم يُضبط عنوان API مصرّح به لهذا المزود.";
        if (!apiBaseOk(api)) return "عنوان الـ API يجب أن يكون https (المفتاح لا يُرسل عبر اتصال غير آمن).";
        return "متكامل عبر واجهة API مضبوطة.";
    }

    @Override public boolean supports(CigramMediaRequest r) {
        return r.tmdbId.length() > 0 || r.imdbId.length() > 0 || r.title.length() > 0;
    }

    /** https only; plain http is accepted for loopback (a resolver running on the same device / in tests). */
    static boolean apiBaseOk(String api) {
        if (api == null || api.length() == 0) return false;
        if (api.startsWith("https://")) return true;
        if (!api.startsWith("http://")) return false;
        try {
            String h = new URI(api).getHost();
            return "127.0.0.1".equals(h) || "localhost".equals(h);
        } catch (Throwable e) {
            return false;
        }
    }

    protected String buildUrl(CigramMediaRequest r) {
        StringBuilder sb = new StringBuilder(config.providerApi(id)).append("/resolve?type=").append(r.isEpisode() ? "episode" : "movie");
        add(sb, "tmdb", r.tmdbId);
        add(sb, "imdb", r.imdbId);
        add(sb, "title", r.title);
        if (r.year > 0) add(sb, "year", String.valueOf(r.year));
        if (r.isEpisode()) {
            add(sb, "season", String.valueOf(r.season));
            add(sb, "episode", String.valueOf(r.episode));
        }
        return sb.toString();
    }

    private static void add(StringBuilder sb, String k, String v) {
        if (v == null || v.length() == 0) return;
        try {
            sb.append('&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8"));
        } catch (UnsupportedEncodingException impossible) { }
    }

    @Override protected List<CigramPlaybackSource> doResolve(CigramMediaRequest r, CigramCancel cancel) throws CigramEngineException {
        CigramHttp.Request req = new CigramHttp.Request(buildUrl(r)).header("Accept", "application/json").max(256 * 1024).timeouts(8000, 12000);
        String token = config.providerToken(id);
        if (token.length() > 0) req.header("Authorization", "Bearer " + token);
        CigramHttp.Response res = CigramHttp.execute(req, cancel);
        if (res.code == 404) return new ArrayList<CigramPlaybackSource>();
        if (!res.ok()) throw CigramEngineException.http(res.code);
        try {
            return parse(new JSONObject(res.text()));
        } catch (CigramEngineException e) {
            throw e;
        } catch (Throwable e) {
            throw new CigramEngineException(CigramEngineException.PARSE, 0, displayName + ": ردّ غير مفهوم.", e);
        }
    }

    protected List<CigramPlaybackSource> parse(JSONObject root) throws CigramEngineException {
        String error = root.optString("error", "");
        if ("login_required".equals(error) || "invalid_token".equals(error) || "forbidden".equals(error)) {
            throw new CigramEngineException(CigramEngineException.AUTH, displayName + " يحتاج تسجيل دخول أو مفتاحاً صالحاً.");
        }
        ArrayList<CigramPlaybackSource> out = new ArrayList<CigramPlaybackSource>();
        JSONArray arr = root.optJSONArray("sources");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("url", "").trim();
            String type = o.optString("type", "").trim().toLowerCase(java.util.Locale.US);
            CigramPlaybackSource s = new CigramPlaybackSource();
            s.playbackUri = url;
            s.quality = o.optString("quality", "");
            s.mimeType = mimeOfType(type);
            JSONObject h = o.optJSONObject("headers");
            if (h != null) {
                Iterator<String> keys = h.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = h.optString(k, "");
                    if (CigramHttp.safeHeader(k, v)) s.headers.put(k, v);
                }
            }
            JSONArray subs = o.optJSONArray("subtitles");
            if (subs != null) {
                for (int j = 0; j < subs.length(); j++) {
                    JSONObject so = subs.optJSONObject(j);
                    if (so == null) continue;
                    s.supportedSubtitles.add(new CigramPlaybackSource.Subtitle(so.optString("url", ""), so.optString("lang", ""), so.optString("label", ""), true));
                }
            }
            if (o.optBoolean("drm", false)) {
                s.markFailed(CigramPlaybackSource.DRM, "المصدر محمي بـ DRM ويحتاج ترخيص صاحب المحتوى.");
            } else if ("embed".equals(type)) {
                s.markFailed(CigramPlaybackSource.EMBED_ONLY, "المصدر صفحة تضمين فقط، لا يوجد رابط وسائط مباشر للمشغل الأصلي.");
            }
            out.add(s);
        }
        return out;
    }

    static String mimeOfType(String type) {
        if ("hls".equals(type)) return "application/x-mpegURL";
        if ("dash".equals(type)) return "application/dash+xml";
        if ("mp4".equals(type)) return "video/mp4";
        if ("mkv".equals(type)) return "video/x-matroska";
        if ("webm".equals(type)) return "video/webm";
        return "";
    }
}
