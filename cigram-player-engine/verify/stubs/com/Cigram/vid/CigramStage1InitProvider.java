package com.Cigram.vid;
import android.app.Activity;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
public final class CigramStage1InitProvider {
    static JSONArray castEpisodes(JSONObject series) { return new JSONArray(); }
    public static void putMinimalDetailsExtras(Intent intent, JSONObject item, String fallbackTitle) { }
    // added by patches/apply_patches.py (4-argument version stays as it was and calls this one with false)
    static void openPlayerForTitleItem(Activity activity, JSONObject source, JSONObject parentSeries, boolean episodeTransition, boolean resolved) { }
}
