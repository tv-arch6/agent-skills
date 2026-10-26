package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;

/**
 * Engine settings that must never live in the source: the metadata token, provider API bases and
 * provider keys. They are stored in the app's private preferences (or supplied by a secured
 * backend) and are read through this class only. Nothing here is written to logs.
 *
 * Keys:  tmdb.base  tmdb.token  tmdb.apikey  tmdb.appkey  tmdb.lang
 *        provider.&lt;id&gt;.enabled   provider.&lt;id&gt;.api   provider.&lt;id&gt;.token   provider.&lt;id&gt;.priority
 */
public final class CigramEngineConfig {

    /** Where the settings live; the unit tests use a map. */
    public interface Store {
        String get(String key);
        void put(String key, String value);
    }

    public static final class MapStore implements Store {
        private final HashMap<String, String> map = new HashMap<String, String>();
        @Override public synchronized String get(String key) { return map.get(key); }
        @Override public synchronized void put(String key, String value) {
            if (value == null) map.remove(key); else map.put(key, value);
        }
    }

    private static final class PrefsStore implements Store {
        private final SharedPreferences prefs;
        PrefsStore(Context c) { prefs = c.getApplicationContext().getSharedPreferences("cigram_engine", Context.MODE_PRIVATE); }
        @Override public String get(String key) { return prefs.getString(key, null); }
        @Override public void put(String key, String value) {
            SharedPreferences.Editor e = prefs.edit();
            if (value == null) e.remove(key); else e.putString(key, value);
            e.apply();
        }
    }

    private final Store store;

    public CigramEngineConfig(Store store) { this.store = store; }

    public static CigramEngineConfig forApp(Context c) { return new CigramEngineConfig(new PrefsStore(c)); }

    public String get(String key, String fallback) {
        String v = store.get(key);
        return v == null || v.trim().length() == 0 ? fallback : v.trim();
    }

    public void put(String key, String value) { store.put(key, value); }

    // ---- metadata (TMDB or a backend that proxies it; a proxy keeps the key out of the app)

    public String tmdbBase() { return stripSlash(get("tmdb.base", "https://api.themoviedb.org/3")); }
    public String tmdbToken() { return get("tmdb.token", ""); }
    public String tmdbApiKey() { return get("tmdb.apikey", ""); }
    public String tmdbLanguage() { return get("tmdb.lang", "ar"); }
    public boolean tmdbConfigured() { return tmdbToken().length() > 0 || tmdbApiKey().length() > 0 || !tmdbBase().startsWith("https://api.themoviedb.org"); }

    // ---- providers

    public boolean providerEnabled(String id) { return !"0".equals(get("provider." + id + ".enabled", "1")); }
    public String providerApi(String id) { return stripSlash(get("provider." + id + ".api", "")); }
    public String providerToken(String id) { return get("provider." + id + ".token", ""); }

    public int providerPriority(String id, int fallback) {
        try { return Integer.parseInt(get("provider." + id + ".priority", String.valueOf(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    static String stripSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
