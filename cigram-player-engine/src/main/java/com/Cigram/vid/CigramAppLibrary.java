package com.Cigram.vid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Connects the engine to the app's existing catalog code (CigramContent / CigramStage1InitProvider).
 * Read-only: nothing here changes the library.
 */
final class CigramAppLibrary implements CigramLibraryData {

    @Override public List<JSONObject> items() {
        try {
            List<JSONObject> list = CigramContent.catalog();
            return list == null ? new ArrayList<JSONObject>() : list;
        } catch (Throwable t) {
            CigramLog.w("library", "catalog unavailable", t);
            return new ArrayList<JSONObject>();
        }
    }

    @Override public List<JSONObject> episodesOf(JSONObject series) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        try {
            JSONArray arr = CigramStage1InitProvider.castEpisodes(series);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(o);
            }
        } catch (Throwable t) {
            CigramLog.w("library", "episodes unavailable", t);
        }
        return out;
    }
}
