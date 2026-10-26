package com.Cigram.vid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * PlaybackCoordinator: turns "play this movie / this episode" into ONE validated source.
 *
 *  - one active request at a time: a request for something else cancels the old one (its network
 *    work is cut and its late answers are dropped); a second tap on the same thing while it is still
 *    running is recognised as a duplicate and does not start a second run;
 *  - providers are tried in priority order, one after the other (the library first); every
 *    candidate is validated (see {@link CigramSourceValidator}) before the player is told to use it;
 *  - when the source being played fails, {@link #reportPlaybackFailure} moves to another source OF THE
 *    SAME movie / episode, never to another episode;
 *  - every provider call and every validation has a time limit;
 *  - a provider that is not integrated is reported with what it lacks, it is never faked.
 */
public final class CigramPlaybackCoordinator {

    public static final int IDLE = 0;
    public static final int RESOLVING = 1;
    public static final int VALIDATING = 2;
    public static final int READY = 3;
    public static final int FAILED = 4;
    public static final int CANCELLED = 5;

    /** What happened with one provider during a run (shown in the "details" of an error). */
    public static final class Report {
        public final String providerId;
        public final String displayName;
        public final int status;           // CigramPlaybackSource.* availability code
        public final String note;          // Arabic, no URLs / tokens
        public final boolean retryable;

        Report(String providerId, String displayName, int status, String note, boolean retryable) {
            this.providerId = providerId; this.displayName = displayName; this.status = status; this.note = note; this.retryable = retryable;
        }

        @Override public String toString() { return displayName + ": " + note; }
    }

    public interface Listener {
        /** Loading / progress text for the UI (state is RESOLVING or VALIDATING). */
        void onState(CigramMediaRequest request, int state, String message);

        /** A validated source. {@code alternates} are other sources of the SAME request, not yet validated. */
        void onReady(CigramMediaRequest request, CigramPlaybackSource chosen, List<CigramPlaybackSource> alternates);

        void onFailed(CigramMediaRequest request, String message, List<Report> reports, boolean retryable);
    }

    public interface ListCallback {
        void onSources(CigramMediaRequest request, List<CigramPlaybackSource> sources, List<Report> reports);
    }

    private static final int MAX_FALLBACKS = 6;

    private final List<CigramSourceProvider> providers;
    private final Executor io;
    private final CigramPoster poster;
    private final Object lock = new Object();
    private long resolveTimeoutMs = 15000L;
    private long validateTimeoutMs = 25000L;
    private Run active;

    public CigramPlaybackCoordinator(List<CigramSourceProvider> providers, Executor io, CigramPoster poster) {
        this.providers = new ArrayList<CigramSourceProvider>(providers);
        CigramProviders.sort(this.providers);
        this.io = io;
        this.poster = poster;
    }

    public CigramPlaybackCoordinator timeouts(long resolveMs, long validateMs) {
        this.resolveTimeoutMs = resolveMs;
        this.validateTimeoutMs = validateMs;
        return this;
    }

    public List<CigramSourceProvider> providers() { return Collections.unmodifiableList(providers); }

    // ================================================================== public API

    /**
     * Starts resolving. Returns the id of the run that serves this call: the new request's id, or the
     * id of the identical request that is already running (the listener is attached to that one).
     */
    public String play(CigramMediaRequest request, Listener listener) {
        return play(request, listener, null);
    }

    /** Same, but a source that already failed for this viewer ({@code excludedSourceKey}) is not offered again. */
    public String play(CigramMediaRequest request, Listener listener, String excludedSourceKey) {
        Run run;
        synchronized (lock) {
            // Duplicate = the same thing is still being looked for. After READY / FAILED a new tap starts a fresh run
            // (links expire; the viewer may have come back from the player).
            if (active != null && !active.finished && active.state != READY
                    && active.request.identityKey().equals(request.identityKey())) {
                active.listeners.add(listener);
                CigramLog.d("coord", "duplicate request ignored: " + active.request.requestId);
                return active.request.requestId;
            }
            cancelLocked();
            run = new Run(request, listener);
            if (excludedSourceKey != null && excludedSourceKey.length() > 0) run.tried.add(excludedSourceKey);
            active = run;
        }
        final Run r = run;
        io.execute(new Runnable() {
            @Override public void run() { advance(r, true); }
        });
        return request.requestId;
    }

    /** The source being played failed: continue with another source of the same movie / episode. */
    public void reportPlaybackFailure(CigramMediaRequest request, CigramPlaybackSource failed, String reason, Listener listener) {
        final Run run;
        synchronized (lock) {
            run = active;
            if (run == null || !run.request.identityKey().equals(request.identityKey())) {
                // The search session of this content is gone (cancelled, or the viewer started something else):
                // say so, so the caller can offer "retry" instead of waiting for an answer that never comes.
                if (listener != null) {
                    final Listener l = listener;
                    final CigramMediaRequest rq = request;
                    poster.post(new Runnable() {
                        @Override public void run() {
                            l.onFailed(rq, "انتهت جلسة البحث عن المصدر. أعد المحاولة.", new ArrayList<Report>(), true);
                        }
                    });
                }
                return;
            }
            if (failed != null) {
                run.tried.add(failed.sourceKey());
                run.reports.add(new Report(failed.providerId, failed.displayName, CigramPlaybackSource.UNAVAILABLE,
                        reason == null || reason.length() == 0 ? "فشل التشغيل." : reason, false));
            }
            if (listener != null) run.listeners.add(listener);
            run.finished = false;
            run.cancel = new CigramCancel();
        }
        io.execute(new Runnable() {
            @Override public void run() { advance(run, false); }
        });
    }

    /** Cancels the running request, if any. */
    public void cancel() {
        synchronized (lock) { cancelLocked(); }
    }

    public void cancel(String requestId) {
        synchronized (lock) {
            if (active != null && active.request.requestId.equals(requestId)) cancelLocked();
        }
    }

    /** Cancels the running request only when it is for this movie / episode (a screen closing must not cancel someone else's run). */
    public void cancelIdentity(String identityKey) {
        synchronized (lock) {
            if (active != null && active.request.identityKey().equals(identityKey)) cancelLocked();
        }
    }

    private void cancelLocked() {
        if (active == null) return;
        Run old = active;
        old.finished = true;
        old.cancel.cancel();
        for (CigramSourceProvider p : providers) {
            try { p.cancel(old.request.requestId); } catch (Throwable ignored) { }
        }
        active = null;
    }

    public int state() {
        synchronized (lock) { return active == null ? IDLE : active.state; }
    }

    /**
     * Every provider's sources for this request, each one validated (for the "choose source" list).
     * Independent of the running request; cancelled with {@link CigramCancel}.
     */
    public CigramCancel listSources(final CigramMediaRequest request, final ListCallback cb) {
        final CigramCancel cancel = new CigramCancel();
        io.execute(new Runnable() {
            @Override public void run() {
                final ArrayList<CigramPlaybackSource> all = new ArrayList<CigramPlaybackSource>();
                final ArrayList<Report> reports = new ArrayList<Report>();
                int validations = 0;
                for (CigramSourceProvider p : providers) {
                    if (cancel.isCancelled()) return;
                    List<CigramPlaybackSource> found = resolveProvider(p, request, cancel, reports);
                    for (CigramPlaybackSource s : found) {
                        if (cancel.isCancelled()) return;
                        if (s.availability == CigramPlaybackSource.UNKNOWN && validations < 8) {
                            validations++;
                            applyValidation(p, s, cancel);
                        }
                        all.add(s);
                    }
                }
                Collections.sort(all, BY_PRIORITY);
                final ArrayList<CigramPlaybackSource> done = all;
                poster.post(new Runnable() {
                    @Override public void run() {
                        if (!cancel.isCancelled()) cb.onSources(request, done, reports);
                    }
                });
            }
        });
        return cancel;
    }

    /** The viewer chose this source from the list: validate it and report ready / failed. */
    public void playSpecific(final CigramMediaRequest request, final CigramPlaybackSource source, final Listener listener) {
        final Run run;
        synchronized (lock) {
            cancelLocked();
            run = new Run(request, listener);
            run.pinned = source;
            active = run;
        }
        io.execute(new Runnable() {
            @Override public void run() { advance(run, true); }
        });
    }

    // ================================================================== the run

    private static final Comparator<CigramPlaybackSource> BY_PRIORITY = new Comparator<CigramPlaybackSource>() {
        @Override public int compare(CigramPlaybackSource a, CigramPlaybackSource b) {
            return a.priority == b.priority ? 0 : (a.priority < b.priority ? -1 : 1);
        }
    };

    private static final class Run {
        final CigramMediaRequest request;
        final ArrayList<Listener> listeners = new ArrayList<Listener>();
        final HashSet<String> tried = new HashSet<String>();
        final ArrayList<Report> reports = new ArrayList<Report>();
        final ArrayList<CigramPlaybackSource> pending = new ArrayList<CigramPlaybackSource>();
        CigramCancel cancel = new CigramCancel();
        CigramPlaybackSource pinned;
        int providerIndex;
        int fallbacks;
        volatile int state = IDLE;
        volatile boolean finished;

        Run(CigramMediaRequest request, Listener first) {
            this.request = request;
            if (first != null) listeners.add(first);
        }
    }

    private void advance(Run run, boolean first) {
        final CigramCancel cancel = run.cancel;
        try {
            emitState(run, RESOLVING, first ? "جاري البحث عن مصدر التشغيل…" : "جاري تجربة مصدر آخر…");

            if (run.pinned != null) {
                CigramPlaybackSource p = run.pinned;
                run.pinned = null;
                CigramSourceProvider owner = providerOf(p.providerId);
                if (owner != null && validate(run, owner, p, cancel)) { ready(run, p); return; }
                fail(run, cancel);
                return;
            }

            while (!cancel.isCancelled() && !run.finished) {
                // 1. sources already found for this request and not tried yet
                while (!run.pending.isEmpty() && run.fallbacks < MAX_FALLBACKS) {
                    CigramPlaybackSource s = run.pending.remove(0);
                    if (!s.requestKey.equals(run.request.identityKey())) continue;     // never a source of another episode
                    if (!run.tried.add(s.sourceKey())) continue;
                    CigramSourceProvider owner = providerOf(s.providerId);
                    if (owner == null) continue;
                    run.fallbacks++;
                    if (validate(run, owner, s, cancel)) { ready(run, s); return; }
                }
                if (run.fallbacks >= MAX_FALLBACKS) break;

                // 2. the next provider
                if (run.providerIndex >= providers.size()) break;
                CigramSourceProvider p = providers.get(run.providerIndex++);
                emitState(run, RESOLVING, "جاري الفحص: " + p.displayName());
                List<CigramPlaybackSource> found = resolveProvider(p, run.request, cancel, run.reports);
                Collections.sort(found, BY_PRIORITY);
                run.pending.addAll(found);
            }
            fail(run, cancel);
        } catch (Throwable t) {
            CigramLog.w("coord", "run crashed", t);
            if (!cancel.isCancelled()) {
                run.reports.add(new Report("engine", "المحرك", CigramPlaybackSource.UNAVAILABLE, "خطأ داخلي غير متوقع.", false));
                fail(run, cancel);
            }
        }
    }

    /** Asks one provider, with a time limit; every outcome ends up in {@code reports}. Never throws. */
    private List<CigramPlaybackSource> resolveProvider(final CigramSourceProvider p, final CigramMediaRequest req,
                                                       CigramCancel parent, List<Report> reports) {
        ArrayList<CigramPlaybackSource> none = new ArrayList<CigramPlaybackSource>();
        int st = p.status();
        if (st == CigramSourceProvider.NEEDS_API) {
            reports.add(new Report(p.id(), p.displayName(), CigramPlaybackSource.NOT_INTEGRATED, p.statusNote(), false));
            return none;
        }
        if (st == CigramSourceProvider.DISABLED) {
            reports.add(new Report(p.id(), p.displayName(), CigramPlaybackSource.NOT_INTEGRATED, "متوقف من الإعدادات.", false));
            return none;
        }
        if (!p.supports(req)) {
            reports.add(new Report(p.id(), p.displayName(), CigramPlaybackSource.UNKNOWN, "لا يملك بيانات كافية لهذا العمل.", false));
            return none;
        }
        final CigramCancel pc = parent.child();
        FutureTask<List<CigramPlaybackSource>> task = new FutureTask<List<CigramPlaybackSource>>(new Callable<List<CigramPlaybackSource>>() {
            @Override public List<CigramPlaybackSource> call() throws Exception {
                return p.resolve(req, pc);
            }
        });
        io.execute(task);
        try {
            List<CigramPlaybackSource> found = task.get(resolveTimeoutMs, TimeUnit.MILLISECONDS);
            if (found == null) found = none;
            if (found.isEmpty()) {
                reports.add(new Report(p.id(), p.displayName(), CigramPlaybackSource.UNAVAILABLE, "لا يوجد مصدر لهذا العمل لدى هذا المزود.", false));
            }
            ArrayList<CigramPlaybackSource> mine = new ArrayList<CigramPlaybackSource>();
            for (CigramPlaybackSource s : found) {
                if (s.requestKey.equals(req.identityKey())) mine.add(s);
            }
            return mine;
        } catch (TimeoutException e) {
            pc.cancel();
            task.cancel(true);
            reports.add(new Report(p.id(), p.displayName(), CigramPlaybackSource.UNAVAILABLE, "انتهت المهلة قبل أن يردّ المزود.", true));
        } catch (InterruptedException e) {
            pc.cancel();
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof CigramEngineException) {
                CigramEngineException ce = (CigramEngineException) c;
                if (ce.kind != CigramEngineException.CANCELLED) {
                    int code = ce.kind == CigramEngineException.AUTH ? CigramPlaybackSource.NEEDS_AUTH
                            : ce.kind == CigramEngineException.NOT_CONFIGURED ? CigramPlaybackSource.NOT_INTEGRATED : CigramPlaybackSource.UNAVAILABLE;
                    reports.add(new Report(p.id(), p.displayName(), code, ce.getMessage(), ce.retryable()));
                }
            } else {
                CigramLog.w("coord", "provider " + p.id() + " crashed", c);
                reports.add(new Report(p.id(), p.displayName(), CigramPlaybackSource.UNAVAILABLE, "خطأ غير متوقع في المزود.", false));
            }
        }
        return none;
    }

    /** Validates with a time limit; fills the source's availability. */
    private boolean validate(Run run, CigramSourceProvider p, CigramPlaybackSource s, CigramCancel cancel) {
        if (s.availability != CigramPlaybackSource.UNKNOWN && s.availability != CigramPlaybackSource.AVAILABLE) {
            run.reports.add(new Report(p.id(), p.displayName(), s.availability, s.failureReason, false));
            return false;
        }
        emitState(run, VALIDATING, "جاري التحقق من المصدر: " + p.displayName());
        boolean ok = applyValidation(p, s, cancel);
        if (!ok && !cancel.isCancelled()) {
            run.reports.add(new Report(p.id(), p.displayName(), s.availability, s.failureReason, false));
        }
        return ok;
    }

    private boolean applyValidation(final CigramSourceProvider p, final CigramPlaybackSource s, CigramCancel parent) {
        final CigramCancel vc = parent.child();
        FutureTask<CigramSourceValidator.Result> task = new FutureTask<CigramSourceValidator.Result>(new Callable<CigramSourceValidator.Result>() {
            @Override public CigramSourceValidator.Result call() { return p.validate(s, vc); }
        });
        io.execute(task);
        try {
            CigramSourceValidator.Result r = task.get(validateTimeoutMs, TimeUnit.MILLISECONDS);
            if (r.ok) { s.markAvailable(r.mime); return true; }
            s.markFailed(r.status, r.reason);
            return false;
        } catch (TimeoutException e) {
            vc.cancel();
            task.cancel(true);
            s.markFailed(CigramPlaybackSource.UNAVAILABLE, "انتهت مهلة فحص المصدر.");
        } catch (InterruptedException e) {
            vc.cancel();
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            s.markFailed(CigramPlaybackSource.UNAVAILABLE, "تعذر فحص المصدر.");
        }
        return false;
    }

    private CigramSourceProvider providerOf(String id) {
        for (CigramSourceProvider p : providers) if (p.id().equals(id)) return p;
        return null;
    }

    // ------------------------------------------------------------------ outcomes (always on the UI thread)

    private void emitState(final Run run, final int state, final String message) {
        run.state = state;
        if (run.cancel.isCancelled() || run.finished) return;
        final ArrayList<Listener> ls = new ArrayList<Listener>(run.listeners);
        poster.post(new Runnable() {
            @Override public void run() {
                if (run.cancel.isCancelled() || run.finished) return;
                for (Listener l : ls) l.onState(run.request, state, message);
            }
        });
    }

    private void ready(final Run run, final CigramPlaybackSource chosen) {
        if (run.cancel.isCancelled()) return;
        run.state = READY;
        final ArrayList<CigramPlaybackSource> alternates = new ArrayList<CigramPlaybackSource>(run.pending);
        final ArrayList<Listener> ls = new ArrayList<Listener>(run.listeners);
        poster.post(new Runnable() {
            @Override public void run() {
                if (run.cancel.isCancelled()) return;
                for (Listener l : ls) l.onReady(run.request, chosen, alternates);
            }
        });
    }

    private void fail(final Run run, CigramCancel cancel) {
        if (cancel.isCancelled()) return;
        run.finished = true;
        run.state = FAILED;
        boolean retryable = false;
        boolean anyIntegrated = false;
        for (Report r : run.reports) {
            if (r.retryable) retryable = true;
            if (r.status != CigramPlaybackSource.NOT_INTEGRATED) anyIntegrated = true;
        }
        final String msg;
        if (!anyIntegrated && !run.reports.isEmpty()) msg = "لا يوجد مزود مصادر مفعّل لهذا العمل حالياً.";
        else if (run.fallbacks >= MAX_FALLBACKS) msg = "فشلت جميع المصادر المتاحة لهذا العمل.";
        else msg = run.request.isEpisode() ? "لا توجد مصادر صالحة لهذه الحلقة حالياً." : "لا توجد مصادر صالحة لهذا الفيلم حالياً.";
        final boolean canRetry = retryable;
        final ArrayList<Report> reports = new ArrayList<Report>(run.reports);
        final ArrayList<Listener> ls = new ArrayList<Listener>(run.listeners);
        poster.post(new Runnable() {
            @Override public void run() {
                for (Listener l : ls) l.onFailed(run.request, msg, reports, canRetry);
            }
        });
    }
}
