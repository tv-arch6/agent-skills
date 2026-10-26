package com.Cigram.vid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Metadata source backed by The Movie Database (TMDB) API v3, or by a backend that proxies it (set
 * {@code tmdb.base}; a proxy is the better choice for a published app because the key stays on the
 * server). The credentials come from {@link CigramEngineConfig} only.
 *
 * TMDB provides titles, posters, seasons and episodes. It provides NO video links, and this class
 * never pretends otherwise. TMDB's terms ask for the attribution "This product uses the TMDB API
 * but is not endorsed or certified by TMDB." somewhere in the app.
 */
public final class CigramTmdbSource implements CigramMediaCatalog.Source {
    public static final String ID = "tmdb";
    static final String IMG = "https://image.tmdb.org/t/p/";

    private final CigramEngineConfig config;

    public CigramTmdbSource(CigramEngineConfig config) { this.config = config; }

    @Override public String id() { return ID; }

    @Override public boolean available() { return config.tmdbConfigured(); }

    @Override public boolean owns(CigramMediaRef ref) { return CigramMediaRef.ORIGIN_TMDB.equals(ref.origin) && ref.tmdbId.length() > 0; }

    // ------------------------------------------------------------------ search

    @Override public List<CigramMediaRef> search(String query, CigramCancel cancel) throws CigramEngineException {
        int year = CigramText.yearIn(query);
        String text = year > 0 ? CigramText.stripYear(query) : query;
        if (text.length() == 0) text = query;
        JSONObject root = get("/search/multi?include_adult=false&page=1&query=" + enc(text), cancel);
        JSONArray arr = root.optJSONArray("results");
        ArrayList<CigramMediaRef> out = new ArrayList<CigramMediaRef>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length() && out.size() < 30; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("media_type", "");
            if (!"movie".equals(type) && !"tv".equals(type)) continue;       // people etc.
            CigramMediaRef r = toRef(o, type);
            if (r != null) out.add(r);
        }
        return out;
    }

    static CigramMediaRef toRef(JSONObject o, String type) {
        boolean tv = "tv".equals(type);
        String id = String.valueOf(o.optLong("id", 0));
        if ("0".equals(id)) return null;
        String title = o.optString(tv ? "name" : "title", "");
        String original = o.optString(tv ? "original_name" : "original_title", "");
        if (title.length() == 0) title = original;
        if (title.length() == 0) return null;
        return new CigramMediaRef.Builder()
                .kind(tv ? CigramMediaRef.SERIES : CigramMediaRef.MOVIE)
                .origin(CigramMediaRef.ORIGIN_TMDB)
                .tmdbId(id)
                .title(title)
                .originalTitle(original.equals(title) ? "" : original)
                .year(yearOf(o.optString(tv ? "first_air_date" : "release_date", "")))
                .poster(image(o.optString("poster_path", ""), "w342"))
                .backdrop(image(o.optString("backdrop_path", ""), "w780"))
                .overview(o.optString("overview", ""))
                .rating(o.optDouble("vote_average", 0))
                .build();
    }

    // ------------------------------------------------------------------ details

    @Override public CigramMediaRef details(CigramMediaRef ref, CigramCancel cancel) throws CigramEngineException {
        boolean tv = ref.isSeries();
        JSONObject o = get("/" + (tv ? "tv" : "movie") + "/" + ref.tmdbId + "?append_to_response=external_ids,alternative_titles", cancel);
        CigramMediaRef.Builder b = ref.rebuild();
        String imdb = tv ? o.optJSONObject("external_ids") == null ? "" : o.optJSONObject("external_ids").optString("imdb_id", "")
                : o.optString("imdb_id", "");
        if (imdb != null && imdb.length() > 0 && !"null".equals(imdb)) b.imdbId(imdb);
        JSONObject alts = o.optJSONObject("alternative_titles");
        if (alts != null) {
            JSONArray arr = alts.optJSONArray(tv ? "results" : "titles");
            if (arr != null) {
                for (int i = 0; i < arr.length() && i < 40; i++) {
                    JSONObject a = arr.optJSONObject(i);
                    if (a != null) b.altTitle(a.optString("title", ""));
                }
            }
        }
        if (ref.overview.length() == 0) b.overview(o.optString("overview", ""));
        if (!tv) b.runtimeMin(o.optInt("runtime", 0));
        if (tv) {
            b.seasonCount(o.optInt("number_of_seasons", 0));
            b.episodeCount(o.optInt("number_of_episodes", 0));
            JSONArray rt = o.optJSONArray("episode_run_time");
            if (rt != null && rt.length() > 0) b.runtimeMin(rt.optInt(0, 0));
        }
        if (o.optDouble("vote_average", 0) > 0) b.rating(o.optDouble("vote_average", 0));
        return b.build();
    }

    // ------------------------------------------------------------------ seasons / episodes

    @Override public List<CigramSeason> seasons(CigramMediaRef ref, CigramCancel cancel) throws CigramEngineException {
        ArrayList<CigramSeason> out = new ArrayList<CigramSeason>();
        if (!ref.isSeries()) return out;
        JSONObject o = get("/tv/" + ref.tmdbId, cancel);
        JSONArray arr = o.optJSONArray("seasons");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            int n = s.optInt("season_number", -1);
            if (n < 0) continue;
            out.add(new CigramSeason(n, s.optString("name", ""), s.optInt("episode_count", 0),
                    image(s.optString("poster_path", ""), "w342"), yearOf(s.optString("air_date", ""))));
        }
        return out;
    }

    @Override public List<CigramEpisode> episodes(CigramMediaRef ref, int season, CigramCancel cancel) throws CigramEngineException {
        ArrayList<CigramEpisode> out = new ArrayList<CigramEpisode>();
        if (!ref.isSeries()) return out;
        JSONObject o = get("/tv/" + ref.tmdbId + "/season/" + season, cancel);
        JSONArray arr = o.optJSONArray("episodes");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            long eid = e.optLong("id", 0);
            out.add(new CigramEpisode(eid > 0 ? "tmdb:ep:" + eid : "", e.optInt("season_number", season), e.optInt("episode_number", -1), i,
                    e.optString("name", ""), e.optString("overview", ""), image(e.optString("still_path", ""), "w300"),
                    e.optString("air_date", ""), e.optInt("runtime", 0), "", ""));
        }
        return out;
    }

    // ------------------------------------------------------------------ http

    private JSONObject get(String path, CigramCancel cancel) throws CigramEngineException {
        StringBuilder url = new StringBuilder(config.tmdbBase()).append(path);
        url.append(path.indexOf('?') >= 0 ? '&' : '?').append("language=").append(enc(config.tmdbLanguage()));
        CigramHttp.Request req = new CigramHttp.Request("").header("Accept", "application/json").max(768 * 1024).timeouts(8000, 12000);
        String token = config.tmdbToken();
        String appKey = config.get("tmdb.appkey", "");
        if (appKey.length() > 0) req.header("X-Cigram-Key", appKey);          // the catalog proxy's optional speed bump
        if (token.length() > 0) req.header("Authorization", "Bearer " + token);
        else if (config.tmdbApiKey().length() > 0) url.append("&api_key=").append(enc(config.tmdbApiKey()));
        req.url = url.toString();
        CigramHttp.Response res = CigramHttp.execute(req, cancel);
        if (res.code == 401) throw new CigramEngineException(CigramEngineException.AUTH, 401, "مفتاح قاعدة البيانات غير صالح.", null);
        if (res.code == 404) throw new CigramEngineException(CigramEngineException.NOT_FOUND, 404, "لم يُعثر على هذا العمل في قاعدة البيانات.", null);
        if (!res.ok()) throw CigramEngineException.http(res.code);
        try {
            return new JSONObject(res.text());
        } catch (Throwable e) {
            throw new CigramEngineException(CigramEngineException.PARSE, 0, "ردّ قاعدة البيانات غير مفهوم.", e);
        }
    }

    static String image(String path, String size) {
        return path == null || path.length() == 0 || "null".equals(path) ? "" : IMG + size + path;
    }

    static int yearOf(String date) {
        if (date == null || date.length() < 4) return 0;
        try { return Integer.parseInt(date.substring(0, 4)); } catch (NumberFormatException e) { return 0; }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (UnsupportedEncodingException impossible) { return s; }
    }
}
