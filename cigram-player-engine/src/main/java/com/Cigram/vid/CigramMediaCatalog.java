package com.Cigram.vid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * MediaCatalogRepository: search by name, details, seasons and episodes. Metadata only; it never
 * looks for a video link (see {@link CigramPlaybackCoordinator} for that).
 *
 *  - Several sources are asked at once (the app's own library, then the external metadata source);
 *    one failing source does not hide the results of the other (it is reported as a warning).
 *  - Similar titles are all returned and ranked; the viewer picks the right one. A work is
 *    identified by its catalog id from then on, never by its title.
 *  - A new search cancels the previous one; its late answer is dropped.
 *  - Seasons / episodes are cached for a few minutes, always returned in numeric order.
 */
public final class CigramMediaCatalog {

    public interface Source {
        String id();

        /** False when the source cannot work yet (for example no metadata token is configured). */
        boolean available();

        /** Does this source hold the data of that work? */
        boolean owns(CigramMediaRef ref);

        List<CigramMediaRef> search(String query, CigramCancel cancel) throws CigramEngineException;

        /** Adds alternative titles, imdb id, runtime ... Returns the same ref when there is nothing to add. */
        CigramMediaRef details(CigramMediaRef ref, CigramCancel cancel) throws CigramEngineException;

        List<CigramSeason> seasons(CigramMediaRef ref, CigramCancel cancel) throws CigramEngineException;

        List<CigramEpisode> episodes(CigramMediaRef ref, int season, CigramCancel cancel) throws CigramEngineException;
    }

    public interface Callback<T> {
        void onResult(T value);
        void onError(CigramEngineException error);
    }

    public static final class SearchResult {
        public final String query;
        public final List<CigramMediaRef> results;
        /** One Arabic line per source that failed while the others answered. */
        public final List<String> warnings;

        SearchResult(String query, List<CigramMediaRef> results, List<String> warnings) {
            this.query = query; this.results = results; this.warnings = warnings;
        }

        /**
         * True when two results share a title or an original title (two films called "Dune"; an Arabic
         * title whose original title is the other one's title): the viewer must choose with the year and poster.
         */
        public boolean ambiguous() {
            HashSet<String> seen = new HashSet<String>();
            for (CigramMediaRef r : results) {
                HashSet<String> mine = new HashSet<String>();
                String a = CigramText.normalize(r.title), b = CigramText.normalize(r.originalTitle);
                if (a.length() > 0) mine.add(a);
                if (b.length() > 0) mine.add(b);
                for (String k : mine) if (seen.contains(k)) return true;
                seen.addAll(mine);
            }
            return false;
        }
    }

    private static final long CACHE_MS = 10L * 60L * 1000L;

    private final List<Source> sources;
    private final Executor io;
    private final CigramPoster poster;
    private final Object lock = new Object();
    private CigramCancel activeSearch;
    private final HashMap<String, Object[]> cache = new HashMap<String, Object[]>();   // key -> {time, value}

    public CigramMediaCatalog(List<Source> sources, Executor io, CigramPoster poster) {
        this.sources = sources;
        this.io = io;
        this.poster = poster;
    }

    // ================================================================== search

    /** Cancels whatever search is running and starts this one. */
    public CigramCancel search(final String rawQuery, final Callback<SearchResult> cb) {
        final String query = rawQuery == null ? "" : rawQuery.trim();
        final CigramCancel cancel = new CigramCancel();
        synchronized (lock) {
            if (activeSearch != null) activeSearch.cancel();
            activeSearch = cancel;
        }
        if (query.length() < 2) {
            deliver(cancel, cb, new SearchResult(query, new ArrayList<CigramMediaRef>(), new ArrayList<String>()), null);
            return cancel;
        }
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    deliver(cancel, cb, searchNow(query, cancel), null);
                } catch (CigramEngineException e) {
                    deliver(cancel, cb, null, e);
                }
            }
        });
        return cancel;
    }

    SearchResult searchNow(String query, CigramCancel cancel) throws CigramEngineException {
        ArrayList<CigramMediaRef> library = new ArrayList<CigramMediaRef>();
        ArrayList<CigramMediaRef> external = new ArrayList<CigramMediaRef>();
        ArrayList<String> warnings = new ArrayList<String>();
        boolean anyAnswered = false;
        CigramEngineException last = null;
        for (Source s : sources) {
            cancel.check();
            if (!s.available()) continue;
            try {
                List<CigramMediaRef> r = s.search(query, cancel);
                anyAnswered = true;
                (CigramLibrarySource.ID.equals(s.id()) ? library : external).addAll(r);
            } catch (CigramEngineException e) {
                if (e.kind == CigramEngineException.CANCELLED) throw e;
                last = e;
                warnings.add(e.getMessage());
            }
        }
        if (!anyAnswered && last != null) throw last;
        ArrayList<CigramMediaRef> merged = merge(library, external);
        rank(merged, query);
        return new SearchResult(query, merged, warnings);
    }

    /** Library entries win; an external entry is dropped only when it is certainly the same work. */
    static ArrayList<CigramMediaRef> merge(List<CigramMediaRef> library, List<CigramMediaRef> external) {
        ArrayList<CigramMediaRef> out = new ArrayList<CigramMediaRef>(library);
        for (CigramMediaRef e : external) {
            boolean same = false;
            for (CigramMediaRef l : library) {
                if (!l.kind.equals(e.kind)) continue;
                if (l.tmdbId.length() > 0 && l.tmdbId.equals(e.tmdbId)) { same = true; break; }
                if (l.tmdbId.length() == 0 && l.year > 0 && l.year == e.year
                        && CigramText.normalize(l.title).equals(CigramText.normalize(e.title))) { same = true; break; }
            }
            if (!same) out.add(e);
        }
        return out;
    }

    static void rank(List<CigramMediaRef> list, final String query) {
        final String q = CigramText.normalize(CigramText.withoutYear(query));
        final int wantedYear = CigramText.yearIn(query);
        final HashMap<CigramMediaRef, Integer> score = new HashMap<CigramMediaRef, Integer>();
        final HashMap<CigramMediaRef, Integer> position = new HashMap<CigramMediaRef, Integer>();
        for (int i = 0; i < list.size(); i++) {
            CigramMediaRef r = list.get(i);
            position.put(r, i);
            score.put(r, scoreOf(r, q, wantedYear));
        }
        Collections.sort(list, new Comparator<CigramMediaRef>() {
            @Override public int compare(CigramMediaRef a, CigramMediaRef b) {
                int sa = score.get(a), sb = score.get(b);
                if (sa != sb) return sa > sb ? -1 : 1;
                return position.get(a).compareTo(position.get(b));
            }
        });
    }

    static int scoreOf(CigramMediaRef r, String q, int wantedYear) {
        int best = 0;
        best = Math.max(best, titleScore(CigramText.normalize(r.title), q));
        best = Math.max(best, titleScore(CigramText.normalize(r.originalTitle), q) - 5);
        for (String alt : r.altTitles) best = Math.max(best, titleScore(CigramText.normalize(alt), q) - 10);
        if (wantedYear > 0) best += r.year == wantedYear ? 25 : (r.year > 0 ? -15 : 0);
        if (CigramMediaRef.ORIGIN_LIBRARY.equals(r.origin)) best += 4;      // playable right now
        return best;
    }

    private static int titleScore(String title, String q) {
        if (title.length() == 0 || q.length() == 0) return 0;
        if (title.equals(q)) return 100;
        if (title.startsWith(q + " ") || title.startsWith(q)) return 75;
        if ((" " + title + " ").contains(" " + q + " ")) return 60;
        if (title.contains(q)) return 45;
        String[] words = q.split(" ");
        int hit = 0;
        for (String w : words) if (w.length() > 1 && (" " + title + " ").contains(" " + w + " ")) hit++;
        return words.length == 0 ? 0 : hit * 40 / words.length;
    }

    // ================================================================== details / seasons / episodes

    public void details(final CigramMediaRef ref, final Callback<CigramMediaRef> cb) {
        run(new CigramCancel(), "details:" + ref.catalogId, cb, new Job<CigramMediaRef>() {
            @Override public CigramMediaRef run(CigramCancel c) throws CigramEngineException {
                Source s = ownerOf(ref);
                return s == null ? ref : s.details(ref, c);
            }
        });
    }

    public void seasons(final CigramMediaRef ref, final Callback<List<CigramSeason>> cb) {
        run(new CigramCancel(), "seasons:" + ref.catalogId, cb, new Job<List<CigramSeason>>() {
            @Override public List<CigramSeason> run(CigramCancel c) throws CigramEngineException {
                Source s = requireOwner(ref);
                return CigramSeason.sorted(s.seasons(ref, c));
            }
        });
    }

    public void episodes(final CigramMediaRef ref, final int season, final Callback<List<CigramEpisode>> cb) {
        run(new CigramCancel(), "episodes:" + ref.catalogId + ":" + season, cb, new Job<List<CigramEpisode>>() {
            @Override public List<CigramEpisode> run(CigramCancel c) throws CigramEngineException {
                Source s = requireOwner(ref);
                return CigramEpisode.normalize(s.episodes(ref, season, c));
            }
        });
    }

    /** Every episode of every season, in viewing order (used for next / previous episode). */
    public void allEpisodes(final CigramMediaRef ref, final Callback<List<CigramEpisode>> cb) {
        run(new CigramCancel(), "all:" + ref.catalogId, cb, new Job<List<CigramEpisode>>() {
            @Override public List<CigramEpisode> run(CigramCancel c) throws CigramEngineException {
                Source s = requireOwner(ref);
                ArrayList<CigramEpisode> all = new ArrayList<CigramEpisode>();
                for (CigramSeason season : CigramSeason.sorted(s.seasons(ref, c))) {
                    c.check();
                    all.addAll(s.episodes(ref, season.number, c));
                }
                return CigramEpisode.normalize(all);
            }
        });
    }

    private Source ownerOf(CigramMediaRef ref) {
        for (Source s : sources) if (s.available() && s.owns(ref)) return s;
        return null;
    }

    private Source requireOwner(CigramMediaRef ref) throws CigramEngineException {
        Source s = ownerOf(ref);
        if (s == null) throw new CigramEngineException(CigramEngineException.NOT_CONFIGURED, "مصدر بيانات هذا العمل غير مهيأ.");
        return s;
    }

    private interface Job<T> { T run(CigramCancel c) throws CigramEngineException; }

    @SuppressWarnings("unchecked")
    private <T> void run(final CigramCancel cancel, final String key, final Callback<T> cb, final Job<T> job) {
        Object[] hit;
        synchronized (cache) { hit = cache.get(key); }
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < CACHE_MS) {
            final T cached = (T) hit[1];
            deliver(cancel, cb, cached, null);
            return;
        }
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    T value = job.run(cancel);
                    synchronized (cache) { cache.put(key, new Object[]{System.currentTimeMillis(), value}); }
                    deliver(cancel, cb, value, null);
                } catch (CigramEngineException e) {
                    deliver(cancel, cb, null, e);
                } catch (Throwable t) {
                    CigramLog.w("catalog", "unexpected failure", t);
                    deliver(cancel, cb, null, new CigramEngineException(CigramEngineException.PARSE, 0, "تعذر قراءة بيانات العمل.", t));
                }
            }
        });
    }

    private <T> void deliver(final CigramCancel cancel, final Callback<T> cb, final T value, final CigramEngineException error) {
        if (cb == null) return;
        poster.post(new Runnable() {
            @Override public void run() {
                if (cancel.isCancelled()) return;           // a newer search replaced this one
                if (error != null) cb.onError(error); else cb.onResult(value);
            }
        });
    }

    public void clearCache() {
        synchronized (cache) { cache.clear(); }
    }
}
