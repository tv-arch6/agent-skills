package com.Cigram.vid;

import java.util.List;

/**
 * One provider of playable sources. One adapter per provider; every adapter reports its sources in
 * the same {@link CigramPlaybackSource} model, and says plainly whether it is integrated or not.
 *
 * Implementations run on worker threads and must return (or throw) promptly when
 * {@link CigramCancel#isCancelled()} turns true.
 */
public interface CigramSourceProvider {
    /** Working integration: it can resolve sources now. */
    int INTEGRATED = 1;
    /** Known provider, but no documented / authorised API is configured: it resolves nothing. */
    int NEEDS_API = 2;
    /** Switched off by the owner of the app. */
    int DISABLED = 3;
    /** Integration exists but the provider is unreachable or refuses us right now. */
    int UNAVAILABLE = 4;

    String id();

    String displayName();

    /** Lower numbers are tried first. */
    int priority();

    /** {@link #INTEGRATED}, {@link #NEEDS_API}, {@link #DISABLED} or {@link #UNAVAILABLE}. */
    int status();

    /** Why the status is what it is, and what is missing (shown in the diagnostic report, never contains secrets). */
    String statusNote();

    /** Cheap, offline: can this provider answer this kind of request at all? */
    boolean supports(CigramMediaRequest request);

    /**
     * Two-step providers: finds the provider-side entries that match (title, contentId, season,
     * episode). Providers with a single lookup return an empty list and do everything in resolve().
     */
    List<String> search(String title, String contentId, int season, int episode, CigramCancel cancel) throws CigramEngineException;

    /** Sources for exactly this movie / episode. Never invents a link; empty list when there is none. */
    List<CigramPlaybackSource> resolve(CigramMediaRequest request, CigramCancel cancel) throws CigramEngineException;

    /** Judges one of this provider's sources (default: the shared media validator). */
    CigramSourceValidator.Result validate(CigramPlaybackSource source, CigramCancel cancel);

    /** Stops whatever this provider is doing for the request (closes its open connections). */
    void cancel(String requestId);
}
