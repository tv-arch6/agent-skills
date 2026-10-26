package com.Cigram.vid;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

/**
 * What the viewer asked to watch: ONE movie or ONE episode, fully identified. The providers, the
 * validator and the coordinator never receive a bare title; the episode a fallback source belongs to
 * is always the episode of this request ({@link #identityKey()}).
 */
public final class CigramMediaRequest {
    public static final String MOVIE = "movie";
    public static final String EPISODE = "episode";

    private static final AtomicLong SEQ = new AtomicLong();

    public final String requestId;
    public final String kind;
    public final String catalogId;
    public final String libraryId;
    public final String tmdbId;
    public final String imdbId;
    public final String title;          // movie title, or the series title for an episode
    public final int year;
    public final int season;            // 0 for movies
    public final int episode;           // 0 for movies / unnumbered episodes
    public final String episodeId;
    public final String episodeTitle;
    /** Library item (movie or episode) when the work is part of Cigram's own library; may be null. */
    public final JSONObject libraryItem;

    private CigramMediaRequest(String kind, String catalogId, String libraryId, String tmdbId, String imdbId, String title,
                               int year, int season, int episode, String episodeId, String episodeTitle, JSONObject libraryItem) {
        this.requestId = "r" + SEQ.incrementAndGet();
        this.kind = kind;
        this.catalogId = CigramMediaRef.nz(catalogId);
        this.libraryId = CigramMediaRef.nz(libraryId);
        this.tmdbId = CigramMediaRef.nz(tmdbId);
        this.imdbId = CigramMediaRef.nz(imdbId);
        this.title = CigramMediaRef.nz(title);
        this.year = year;
        this.season = season;
        this.episode = episode;
        this.episodeId = CigramMediaRef.nz(episodeId);
        this.episodeTitle = CigramMediaRef.nz(episodeTitle);
        this.libraryItem = libraryItem;
    }

    public static CigramMediaRequest movie(CigramMediaRef ref, JSONObject libraryItem) {
        return new CigramMediaRequest(MOVIE, ref.catalogId, ref.libraryId, ref.tmdbId, ref.imdbId, ref.title,
                ref.year, 0, 0, "", "", libraryItem);
    }

    public static CigramMediaRequest episode(CigramMediaRef series, CigramEpisode ep, JSONObject libraryEpisode) {
        return new CigramMediaRequest(EPISODE, series.catalogId, series.libraryId, series.tmdbId, series.imdbId, series.title,
                series.year, ep.season, ep.number, ep.episodeId, ep.title, libraryEpisode);
    }

    /** The same request for another episode of the same series (a new request id). */
    public CigramMediaRequest forEpisode(CigramEpisode ep, JSONObject libraryEpisode) {
        return new CigramMediaRequest(EPISODE, catalogId, libraryId, tmdbId, imdbId, title, year,
                ep.season, ep.number, ep.episodeId, ep.title, libraryEpisode);
    }

    public boolean isEpisode() { return EPISODE.equals(kind); }

    /**
     * Same identity = same video. The request id is NOT part of it, so a second tap on «شاهد الآن»
     * for the same title / episode is recognised as a duplicate.
     */
    public String identityKey() {
        // the episode id only matters for episodes without a number; numbered ones are season + number
        return kind + "|" + catalogId + "|" + season + "|" + episode + "|" + (episode > 0 ? "" : episodeId);
    }

    @Override public String toString() {
        return title + (isEpisode() ? " S" + season + "E" + episode : "");
    }
}
