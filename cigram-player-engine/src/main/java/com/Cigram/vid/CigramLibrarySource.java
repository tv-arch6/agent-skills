package com.Cigram.vid;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Catalog source that reads Cigram's own library (through {@link CigramLibraryData}). Results are
 * already playable, so they are ranked a little above catalog-only results.
 */
public final class CigramLibrarySource implements CigramMediaCatalog.Source {
    public static final String ID = "library";

    private final CigramLibraryData data;

    public CigramLibrarySource(CigramLibraryData data) { this.data = data; }

    @Override public String id() { return ID; }

    @Override public boolean available() { return true; }

    @Override public boolean owns(CigramMediaRef ref) { return CigramMediaRef.ORIGIN_LIBRARY.equals(ref.origin); }

    @Override public List<CigramMediaRef> search(String query, CigramCancel cancel) {
        String q = CigramText.normalize(CigramText.withoutYear(query));
        ArrayList<CigramMediaRef> out = new ArrayList<CigramMediaRef>();
        if (q.length() == 0) return out;
        for (JSONObject item : data.items()) {
            if (item == null || !matches(item, q)) continue;
            out.add(CigramLibraryItems.toRef(item));
            if (out.size() >= 30) break;
        }
        return out;
    }

    private static boolean matches(JSONObject item, String q) {
        String[] keys = {"title", "name", "title_ar", "title_en", "series_title", "original_title"};
        for (String k : keys) {
            String t = CigramText.normalize(item.optString(k, ""));
            if (t.length() > 0 && t.contains(q)) return true;
        }
        return false;
    }

    @Override public CigramMediaRef details(CigramMediaRef ref, CigramCancel cancel) { return ref; }

    private JSONObject itemOf(CigramMediaRef ref) {
        return CigramLibraryItems.find(data.items(), ref.libraryId, ref.tmdbId);
    }

    @Override public List<CigramSeason> seasons(CigramMediaRef ref, CigramCancel cancel) throws CigramEngineException {
        ArrayList<CigramSeason> out = new ArrayList<CigramSeason>();
        JSONObject item = itemOf(ref);
        if (item == null) throw new CigramEngineException(CigramEngineException.NOT_FOUND, "العمل لم يعد في المكتبة.");
        TreeMap<Integer, Integer> counts = new TreeMap<Integer, Integer>();
        List<JSONObject> raw = data.episodesOf(item);
        for (int i = 0; i < raw.size(); i++) {
            CigramEpisode e = CigramLibraryItems.toEpisode(raw.get(i), i);
            Integer c = counts.get(e.season);
            counts.put(e.season, c == null ? 1 : c + 1);
        }
        for (java.util.Map.Entry<Integer, Integer> en : counts.entrySet()) {
            out.add(new CigramSeason(en.getKey(), "", en.getValue(), "", 0));
        }
        return out;
    }

    @Override public List<CigramEpisode> episodes(CigramMediaRef ref, int season, CigramCancel cancel) throws CigramEngineException {
        ArrayList<CigramEpisode> out = new ArrayList<CigramEpisode>();
        JSONObject item = itemOf(ref);
        if (item == null) throw new CigramEngineException(CigramEngineException.NOT_FOUND, "العمل لم يعد في المكتبة.");
        List<JSONObject> raw = data.episodesOf(item);
        for (int i = 0; i < raw.size(); i++) {
            CigramEpisode e = CigramLibraryItems.toEpisode(raw.get(i), i);
            if (e.season == season) out.add(e);
        }
        return out;
    }
}
