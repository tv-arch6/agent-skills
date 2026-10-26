package com.Cigram.vid;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * How a library item (the JSON the admin app publishes) maps to the engine's models. The same field
 * names the existing code already accepts: video / link / video_url / movie_url / stream_url /
 * play_url, subtitle / subtitle_url, season / season_number, episode / episode_number / ep / number.
 */
final class CigramLibraryItems {
    static final String[] VIDEO_KEYS = {"video", "link", "video_url", "movie_url", "stream_url", "play_url"};
    static final String[] SUBTITLE_KEYS = {"subtitle", "subtitle_url"};
    static final String[] SEASON_KEYS = {"season", "season_number"};
    static final String[] EPISODE_KEYS = {"episode", "episode_number", "ep", "number"};

    private CigramLibraryItems() { }

    static String text(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            if (!o.has(k) || o.isNull(k)) continue;
            String v = o.optString(k, "").trim();
            if (v.length() > 0 && !"null".equalsIgnoreCase(v)) return v;
        }
        return "";
    }

    static int number(JSONObject o, String... keys) {
        String t = text(o, keys);
        if (t.length() == 0) return 0;
        try {
            return (int) Math.round(Double.parseDouble(t));
        } catch (NumberFormatException e) {
            int n = 0;
            boolean any = false;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c >= '0' && c <= '9') { n = n * 10 + (c - '0'); any = true; if (n > 100000) break; }
                else if (any) break;
            }
            return any ? n : 0;
        }
    }

    static boolean isSeries(JSONObject item) {
        String type = text(item, "type").toLowerCase(Locale.US);
        if ("series".equals(type) || "tv".equals(type) || "show".equals(type)) return true;
        return item != null && (item.has("episodes") || item.has("seasons") || item.has("series_episodes_json"));
    }

    static String title(JSONObject item) {
        return text(item, "title", "name", "title_ar", "title_en", "series_title");
    }

    static String video(JSONObject item) { return text(item, VIDEO_KEYS); }

    static String subtitle(JSONObject item) { return text(item, SUBTITLE_KEYS); }

    /** The library's own id, or "" when the item has none. */
    static String id(JSONObject item) { return text(item, "id", "series_id", "movie_id", "item_id", "content_id"); }

    static int year(JSONObject item) {
        int y = number(item, "year", "release_year");
        if (y >= 1888 && y <= 2200) return y;
        String d = text(item, "release_date", "first_air_date", "date");
        if (d.length() >= 4) {
            try { y = Integer.parseInt(d.substring(0, 4)); if (y >= 1888 && y <= 2200) return y; } catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    static CigramMediaRef toRef(JSONObject item) {
        boolean series = isSeries(item);
        String libId = id(item);
        String title = title(item);
        // A title the viewer found through the external catalog is opened in the details page with these two extras.
        boolean external = CigramMediaRef.ORIGIN_TMDB.equals(text(item, "cg_origin")) && text(item, "cg_catalog_id").length() > 0;
        CigramMediaRef.Builder b = new CigramMediaRef.Builder()
                .kind(series ? CigramMediaRef.SERIES : CigramMediaRef.MOVIE)
                .origin(external ? CigramMediaRef.ORIGIN_TMDB : CigramMediaRef.ORIGIN_LIBRARY)
                .catalogId(external ? text(item, "cg_catalog_id") : "")
                .libraryId(external ? "" : libId.length() > 0 ? libId : "local_" + Integer.toHexString(title.hashCode()))
                .tmdbId(text(item, "tmdb_id", "tmdb"))
                .imdbId(text(item, "imdb_id", "imdb"))
                .title(title)
                .originalTitle(text(item, "title_en", "original_title", "original_name"))
                .altTitle(text(item, "title_ar"))
                .year(year(item))
                .poster(text(item, "poster", "series_poster", "image"))
                .backdrop(text(item, "backdrop"))
                .overview(text(item, "description", "overview", "plot", "synopsis"));
        try { b.rating(Double.parseDouble(text(item, "rating", "imdb_rating", "vote_average", "score"))); } catch (NumberFormatException ignored) { }
        return b.build();
    }

    static CigramEpisode toEpisode(JSONObject ep, int index) {
        int season = number(ep, SEASON_KEYS);
        if (season <= 0 && !ep.has("season") && !ep.has("season_number")) season = 1;
        return new CigramEpisode(text(ep, "episode_id", "eid", "id"), season, number(ep, EPISODE_KEYS), index,
                text(ep, "title", "name", "episode_title"), text(ep, "description", "overview"),
                text(ep, "episode_poster", "still", "backdrop", "poster"), text(ep, "air_date", "date"),
                number(ep, "runtime", "duration"), video(ep), subtitle(ep));
    }

    /** The library item that is the same work as {@code ref} (by library id, then tmdb id), or null. */
    static JSONObject find(List<JSONObject> items, String libraryId, String tmdbId) {
        if (items == null) return null;
        if (libraryId != null && libraryId.length() > 0) {
            for (JSONObject it : items) if (libraryId.equals(id(it))) return it;
        }
        if (tmdbId != null && tmdbId.length() > 0) {
            for (JSONObject it : items) if (tmdbId.equals(text(it, "tmdb_id", "tmdb"))) return it;
        }
        return null;
    }
}
