package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.UriPermission;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Remembers, on this device only, which local subtitle file the viewer chose for a movie / episode,
 * as a content URI with its persistable read permission (Storage Access Framework). The file's
 * content is never copied anywhere and never leaves the phone: nothing in here talks to the network
 * or to a Together room.
 *
 * Keeps at most {@link #MAX} entries (oldest first out), so the app never uses up Android's limit of
 * persisted URI permissions.
 */
public final class CigramSubtitleStore {
    private static final String PREFS = "cigram_engine_subs";
    private static final String ORDER = "_order";
    private static final int MAX = 40;

    private CigramSubtitleStore() { }

    public static final class Entry {
        public final Uri uri;
        public final String name;
        Entry(Uri uri, String name) { this.uri = uri; this.name = name; }
    }

    /** Key of one movie / episode: its identity key, so each episode keeps its own subtitle file. */
    public static String keyOf(CigramMediaRequest r) { return r.identityKey(); }

    public static void remember(Context c, String contentKey, Uri uri, String name) {
        if (contentKey == null || contentKey.length() == 0 || uri == null) return;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LinkedHashSet<String> order = new LinkedHashSet<String>();
        for (String k : p.getString(ORDER, "").split("\n")) if (k.length() > 0) order.add(k);
        order.remove(contentKey);
        order.add(contentKey);                                           // newest last
        SharedPreferences.Editor e = p.edit();
        ArrayList<String> dropped = new ArrayList<String>();
        while (order.size() > MAX) {
            String oldest = order.iterator().next();
            order.remove(oldest);
            dropped.add(p.getString(oldest, ""));
            e.remove(oldest);
        }
        e.putString(contentKey, uri.toString() + "\n" + (name == null ? "" : name));
        StringBuilder sb = new StringBuilder();
        for (String k : order) sb.append(k).append('\n');
        e.putString(ORDER, sb.toString());
        e.apply();
        for (String stored : dropped) {
            // a file used by a newer entry as well keeps its permission
            if (!stored.equals("") && !usedElsewhere(p, order, uriOf(stored))) release(c, uriOf(stored));
        }
    }

    /** The remembered file for this content, or null when there is none or its permission is gone. */
    public static Entry recall(Context c, String contentKey) {
        if (contentKey == null || contentKey.length() == 0) return null;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String v = p.getString(contentKey, "");
        if (v.length() == 0) return null;
        int nl = v.indexOf('\n');
        Uri uri = Uri.parse(nl < 0 ? v : v.substring(0, nl));
        String name = nl < 0 ? "" : v.substring(nl + 1);
        if (!hasPermission(c, uri)) return null;
        return new Entry(uri, name);
    }

    public static void forget(Context c, String contentKey) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String v = p.getString(contentKey, "");
        LinkedHashSet<String> order = new LinkedHashSet<String>();
        for (String k : p.getString(ORDER, "").split("\n")) if (k.length() > 0 && !k.equals(contentKey)) order.add(k);
        StringBuilder sb = new StringBuilder();
        for (String k : order) sb.append(k).append('\n');
        p.edit().remove(contentKey).putString(ORDER, sb.toString()).apply();
        if (v.length() > 0 && !usedElsewhere(p, order, uriOf(v))) release(c, uriOf(v));
    }

    private static boolean hasPermission(Context c, Uri uri) {
        try {
            List<UriPermission> list = c.getContentResolver().getPersistedUriPermissions();
            for (UriPermission up : list) if (up.getUri().equals(uri) && up.isReadPermission()) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private static Uri uriOf(String stored) {
        int nl = stored.indexOf('\n');
        return Uri.parse(nl < 0 ? stored : stored.substring(0, nl));
    }

    private static boolean usedElsewhere(SharedPreferences p, LinkedHashSet<String> keys, Uri uri) {
        for (String k : keys) {
            String v = p.getString(k, "");
            if (v.length() > 0 && uriOf(v).equals(uri)) return true;
        }
        return false;
    }

    private static void release(Context c, Uri uri) {
        try {
            c.getContentResolver().releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) { }
    }
}
