package com.Cigram.vid;

import java.net.HttpURLConnection;
import java.util.ArrayList;

/**
 * Cancellation flag shared by everything one engine request does. Cancelling it also closes the
 * connections that are open at that moment, so a blocked read ends immediately instead of waiting
 * for its timeout.
 */
public final class CigramCancel {
    private volatile boolean cancelled;
    private final ArrayList<HttpURLConnection> open = new ArrayList<HttpURLConnection>();

    private final ArrayList<CigramCancel> children = new ArrayList<CigramCancel>();

    public boolean isCancelled() { return cancelled; }

    public void cancel() {
        ArrayList<HttpURLConnection> copy;
        ArrayList<CigramCancel> kids;
        synchronized (open) {
            cancelled = true;
            copy = new ArrayList<HttpURLConnection>(open);
            open.clear();
            kids = new ArrayList<CigramCancel>(children);
            children.clear();
        }
        for (HttpURLConnection c : copy) {
            try { c.disconnect(); } catch (Throwable ignored) { }
        }
        for (CigramCancel k : kids) k.cancel();
    }

    /** A cancel that fires together with this one (one per provider call, for example). */
    public CigramCancel child() {
        CigramCancel k = new CigramCancel();
        boolean dead;
        synchronized (open) {
            dead = cancelled;
            if (!dead) children.add(k);
        }
        if (dead) k.cancel();
        return k;
    }

    public void check() throws CigramEngineException {
        if (cancelled) throw CigramEngineException.cancelled();
    }

    void track(HttpURLConnection c) {
        boolean dead;
        synchronized (open) {
            dead = cancelled;
            if (!dead) open.add(c);
        }
        if (dead) {
            try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    void untrack(HttpURLConnection c) {
        synchronized (open) { open.remove(c); }
    }
}
