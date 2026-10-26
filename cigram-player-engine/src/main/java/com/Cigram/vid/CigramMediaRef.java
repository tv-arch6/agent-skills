package com.Cigram.vid;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A movie or a series as the catalog layer knows it: identity and metadata only. It says nothing
 * about where the video comes from (that is the providers' job) and nothing about the player.
 *
 * {@link #catalogId} is the stable key ("tmdb:movie:603", "tmdb:tv:1396", "lib:42"). Two works with
 * the same title have different ids, so a playback request is always tied to an id and never to a
 * title alone.
 */
public final class CigramMediaRef {
    public static final String MOVIE = "movie";
    public static final String SERIES = "series";
    public static final String ORIGIN_TMDB = "tmdb";
    public static final String ORIGIN_LIBRARY = "library";

    public final String kind;
    public final String origin;
    public final String catalogId;
    public final String libraryId;
    public final String tmdbId;
    public final String imdbId;
    public final String title;
    public final String originalTitle;
    public final List<String> altTitles;
    public final int year;
    public final String poster;
    public final String backdrop;
    public final String overview;
    public final double rating;
    public final int runtimeMin;
    public final int seasonCount;
    public final int episodeCount;

    private CigramMediaRef(Builder b) {
        this.kind = SERIES.equals(b.kind) ? SERIES : MOVIE;
        this.origin = b.origin;
        this.catalogId = b.catalogId;
        this.libraryId = b.libraryId;
        this.tmdbId = b.tmdbId;
        this.imdbId = b.imdbId;
        this.title = b.title;
        this.originalTitle = b.originalTitle;
        this.altTitles = Collections.unmodifiableList(new ArrayList<String>(b.altTitles));
        this.year = b.year;
        this.poster = b.poster;
        this.backdrop = b.backdrop;
        this.overview = b.overview;
        this.rating = b.rating;
        this.runtimeMin = b.runtimeMin;
        this.seasonCount = b.seasonCount;
        this.episodeCount = b.episodeCount;
    }

    public boolean isSeries() { return SERIES.equals(kind); }

    public Builder rebuild() {
        Builder b = new Builder();
        b.kind = kind; b.origin = origin; b.catalogId = catalogId; b.libraryId = libraryId; b.tmdbId = tmdbId;
        b.imdbId = imdbId; b.title = title; b.originalTitle = originalTitle; b.altTitles.addAll(altTitles);
        b.year = year; b.poster = poster; b.backdrop = backdrop; b.overview = overview; b.rating = rating;
        b.runtimeMin = runtimeMin; b.seasonCount = seasonCount; b.episodeCount = episodeCount;
        return b;
    }

    /** The extras / JSON the existing details page understands (see CigramStage1InitProvider.mergeIntentDetails). */
    public JSONObject toDetailsJson() {
        JSONObject o = new JSONObject();
        try {
            String id = libraryId.length() > 0 ? libraryId : catalogId;
            o.put("id", id);
            o.put("type", kind);
            o.put("title", title);
            if (isSeries()) {
                o.put("series_id", id);
                o.put("series_title", title);
            }
            if (originalTitle.length() > 0) o.put("title_en", originalTitle);
            if (poster.length() > 0) o.put("poster", poster);
            if (backdrop.length() > 0) o.put("backdrop", backdrop);
            if (overview.length() > 0) o.put("description", overview);
            if (year > 0) o.put("year", String.valueOf(year));
            if (tmdbId.length() > 0) o.put("tmdb_id", tmdbId);
            if (imdbId.length() > 0) o.put("imdb", imdbId);
            if (rating > 0) o.put("rating", String.format(java.util.Locale.US, "%.1f", rating));
            o.put("cg_origin", origin);
            o.put("cg_catalog_id", catalogId);
        } catch (Throwable ignored) { }
        return o;
    }

    @Override public boolean equals(Object other) {
        return other instanceof CigramMediaRef && ((CigramMediaRef) other).catalogId.equals(catalogId);
    }

    @Override public int hashCode() { return catalogId.hashCode(); }

    @Override public String toString() { return catalogId + " " + title + (year > 0 ? " (" + year + ")" : ""); }

    public static final class Builder {
        String kind = MOVIE;
        String origin = ORIGIN_TMDB;
        String catalogId = "";
        String libraryId = "";
        String tmdbId = "";
        String imdbId = "";
        String title = "";
        String originalTitle = "";
        final ArrayList<String> altTitles = new ArrayList<String>();
        int year;
        String poster = "";
        String backdrop = "";
        String overview = "";
        double rating;
        int runtimeMin;
        int seasonCount;
        int episodeCount;

        public Builder kind(String v) { kind = v; return this; }
        public Builder origin(String v) { origin = v; return this; }
        public Builder catalogId(String v) { catalogId = nz(v); return this; }
        public Builder libraryId(String v) { libraryId = nz(v); return this; }
        public Builder tmdbId(String v) { tmdbId = nz(v); return this; }
        public Builder imdbId(String v) { imdbId = nz(v); return this; }
        public Builder title(String v) { title = nz(v); return this; }
        public Builder originalTitle(String v) { originalTitle = nz(v); return this; }
        public Builder altTitle(String v) { if (v != null && v.trim().length() > 0 && !altTitles.contains(v.trim())) altTitles.add(v.trim()); return this; }
        public Builder year(int v) { year = v; return this; }
        public Builder poster(String v) { poster = nz(v); return this; }
        public Builder backdrop(String v) { backdrop = nz(v); return this; }
        public Builder overview(String v) { overview = nz(v); return this; }
        public Builder rating(double v) { rating = v; return this; }
        public Builder runtimeMin(int v) { runtimeMin = v; return this; }
        public Builder seasonCount(int v) { seasonCount = v; return this; }
        public Builder episodeCount(int v) { episodeCount = v; return this; }

        public CigramMediaRef build() {
            if (catalogId.length() == 0) {
                if (ORIGIN_LIBRARY.equals(origin) && libraryId.length() > 0) catalogId = "lib:" + libraryId;
                else if (tmdbId.length() > 0) catalogId = "tmdb:" + (SERIES.equals(kind) ? "tv" : "movie") + ":" + tmdbId;
            }
            if (catalogId.length() == 0) throw new IllegalStateException("catalogId");
            return new CigramMediaRef(this);
        }
    }

    static String nz(String s) { return s == null ? "" : s.trim(); }
}
