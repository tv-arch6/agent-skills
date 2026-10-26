package com.Cigram.vid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared skeleton of every provider adapter: request bookkeeping for cancel(), and one place that
 * checks what a provider returned (no empty links, no javascript:/file: links, no duplicates,
 * identity stamped) so the rest of the engine can trust the model.
 */
public abstract class CigramBaseProvider implements CigramSourceProvider {
    protected final String id;
    protected final String displayName;
    protected final int priority;
    private final ConcurrentHashMap<String, CigramCancel> inflight = new ConcurrentHashMap<String, CigramCancel>();
    private volatile CigramSourceValidator validator;

    protected CigramBaseProvider(String id, String displayName, int priority) {
        this.id = id;
        this.displayName = displayName;
        this.priority = priority;
    }

    public CigramBaseProvider useValidator(CigramSourceValidator v) { this.validator = v; return this; }

    @Override public final String id() { return id; }
    @Override public final String displayName() { return displayName; }
    @Override public int priority() { return priority; }

    @Override public List<String> search(String title, String contentId, int season, int episode, CigramCancel cancel) throws CigramEngineException {
        return Collections.emptyList();
    }

    /** The provider-specific part: returns raw sources (identity is stamped by {@link #resolve}). */
    protected abstract List<CigramPlaybackSource> doResolve(CigramMediaRequest request, CigramCancel cancel) throws CigramEngineException;

    @Override public final List<CigramPlaybackSource> resolve(CigramMediaRequest request, CigramCancel cancel) throws CigramEngineException {
        int st = status();
        if (st == NEEDS_API) {
            throw new CigramEngineException(CigramEngineException.NOT_CONFIGURED, displayName + ": " + statusNote());
        }
        if (st == DISABLED) {
            throw new CigramEngineException(CigramEngineException.NOT_CONFIGURED, displayName + " متوقف.");
        }
        if (!supports(request)) return Collections.emptyList();
        cancel.check();
        inflight.put(request.requestId, cancel);
        try {
            List<CigramPlaybackSource> raw = doResolve(request, cancel);
            cancel.check();
            return sanitize(raw, request);
        } finally {
            inflight.remove(request.requestId);
        }
    }

    private List<CigramPlaybackSource> sanitize(List<CigramPlaybackSource> raw, CigramMediaRequest request) {
        ArrayList<CigramPlaybackSource> out = new ArrayList<CigramPlaybackSource>();
        if (raw == null) return out;
        HashSet<String> seen = new HashSet<String>();
        int n = 0;
        for (CigramPlaybackSource s : raw) {
            if (s == null) continue;
            s.providerId = id;
            if (s.displayName.length() == 0) s.displayName = displayName;
            s.requestKey = request.identityKey();
            s.priority = priority * 100 + (n++);
            String uri = s.playbackUri == null ? "" : s.playbackUri.trim();
            s.playbackUri = uri;
            boolean web = uri.startsWith("https://") || uri.startsWith("http://");
            if (s.availability == CigramPlaybackSource.UNKNOWN && !web) {
                continue;                      // an empty or non-web link is not a source
            }
            if (uri.length() > 0 && !web) continue;
            if (!seen.add(s.sourceKey())) continue;
            out.add(s);
        }
        return out;
    }

    @Override public CigramSourceValidator.Result validate(CigramPlaybackSource source, CigramCancel cancel) {
        CigramSourceValidator v = validator;
        if (v == null) v = CigramSourceValidator.shared();
        return v.validate(source, cancel);
    }

    @Override public void cancel(String requestId) {
        CigramCancel c = inflight.get(requestId);
        if (c != null) c.cancel();
    }
}
