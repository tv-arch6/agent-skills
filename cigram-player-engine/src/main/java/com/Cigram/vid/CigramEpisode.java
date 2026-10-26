package com.Cigram.vid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * One episode of a series with its own stable id, and the season it belongs to.
 *
 * Ordering rules (the order of the source JSON never matters):
 *  1. seasons 1, 2, 3 ... in numeric order; season 0 (specials) comes last;
 *  2. inside a season, numbered episodes in numeric order;
 *  3. episodes without a usable number keep their original relative order, after the numbered ones.
 */
public final class CigramEpisode {
    public final String episodeId;
    public final int season;
    public final int number;       // <= 0 when the source gives no usable number
    public final int orderIndex;   // position in the source list (tie-breaker only)
    public final String title;
    public final String overview;
    public final String still;
    public final String airDate;
    public final int runtimeMin;
    /** Direct link and subtitle of a library episode (empty for catalog-only episodes). */
    public final String libraryVideo;
    public final String librarySubtitle;

    public CigramEpisode(String episodeId, int season, int number, int orderIndex, String title, String overview,
                         String still, String airDate, int runtimeMin, String libraryVideo, String librarySubtitle) {
        this.season = Math.max(0, season);
        this.number = number;
        this.orderIndex = orderIndex;
        this.title = CigramMediaRef.nz(title);
        this.overview = CigramMediaRef.nz(overview);
        this.still = CigramMediaRef.nz(still);
        this.airDate = CigramMediaRef.nz(airDate);
        this.runtimeMin = runtimeMin;
        this.libraryVideo = CigramMediaRef.nz(libraryVideo);
        this.librarySubtitle = CigramMediaRef.nz(librarySubtitle);
        String id = CigramMediaRef.nz(episodeId);
        this.episodeId = id.length() > 0 ? id : "s" + this.season + (number > 0 ? "e" + number : "o" + orderIndex);
    }

    public boolean isSpecial() { return season == 0; }

    /** Identity inside one series: season + number (or position when the episode is unnumbered). */
    public String key() {
        return season + ":" + (number > 0 ? String.valueOf(number) : "o" + orderIndex);
    }

    public String label() {
        String n = number > 0 ? "الحلقة " + number : "حلقة";
        return title.length() > 0 ? n + " — " + title : n;
    }

    public static final Comparator<CigramEpisode> ORDER = new Comparator<CigramEpisode>() {
        @Override public int compare(CigramEpisode a, CigramEpisode b) {
            int sa = seasonRank(a.season), sb = seasonRank(b.season);
            if (sa != sb) return sa < sb ? -1 : 1;
            boolean na = a.number > 0, nb = b.number > 0;
            if (na != nb) return na ? -1 : 1;
            if (na && a.number != b.number) return a.number < b.number ? -1 : 1;
            if (a.orderIndex != b.orderIndex) return a.orderIndex < b.orderIndex ? -1 : 1;
            return 0;
        }
    };

    static int seasonRank(int season) { return season <= 0 ? Integer.MAX_VALUE : season; }

    /** Sorted copy without duplicates (same season + number keeps the first one given). */
    public static List<CigramEpisode> normalize(List<CigramEpisode> in) {
        LinkedHashMap<String, CigramEpisode> unique = new LinkedHashMap<String, CigramEpisode>();
        if (in != null) {
            for (CigramEpisode e : in) {
                if (e == null) continue;
                if (!unique.containsKey(e.key())) unique.put(e.key(), e);
            }
        }
        ArrayList<CigramEpisode> out = new ArrayList<CigramEpisode>(unique.values());
        Collections.sort(out, ORDER);
        return out;
    }

    /** The episode after {@code current} in viewing order (may be the first of the next season), or null. */
    public static CigramEpisode next(List<CigramEpisode> sorted, CigramEpisode current) {
        int i = indexOf(sorted, current);
        return i >= 0 && i + 1 < sorted.size() ? sorted.get(i + 1) : null;
    }

    public static CigramEpisode previous(List<CigramEpisode> sorted, CigramEpisode current) {
        int i = indexOf(sorted, current);
        return i > 0 ? sorted.get(i - 1) : null;
    }

    public static int indexOf(List<CigramEpisode> sorted, CigramEpisode current) {
        if (sorted == null || current == null) return -1;
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).key().equals(current.key())) return i;
        }
        return -1;
    }

    public static CigramEpisode find(List<CigramEpisode> sorted, int season, int number) {
        if (sorted == null) return null;
        for (CigramEpisode e : sorted) if (e.season == season && e.number == number) return e;
        return null;
    }
}
