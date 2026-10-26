package com.Cigram.vid;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The player engine of the app: one catalog, one coordinator, one set of providers, shared by the
 * single player, the details page and the search screen. Built lazily, once per process.
 *
 * The worker pool is unbounded on purpose: the coordinator waits on provider calls that run on the
 * same pool, and a bounded pool could make a request wait for itself.
 */
public final class CigramEngine {
    private static CigramEngine instance;

    public final CigramEngineConfig config;
    public final ExecutorService io;
    public final CigramPoster poster;
    public final List<CigramSourceProvider> providers;
    public final CigramMediaCatalog catalog;
    public final CigramPlaybackCoordinator coordinator;

    public static synchronized CigramEngine get(Context c) {
        if (instance == null) instance = new CigramEngine(c.getApplicationContext());
        return instance;
    }

    private CigramEngine(Context app) {
        config = CigramEngineConfig.forApp(app);
        final AtomicInteger n = new AtomicInteger();
        io = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "cigram-engine-" + n.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
        poster = new CigramPoster.Main();
        CigramLibraryData library = new CigramAppLibrary();
        providers = CigramProviders.createDefault(config, library);
        ArrayList<CigramMediaCatalog.Source> sources = new ArrayList<CigramMediaCatalog.Source>();
        sources.add(new CigramLibrarySource(library));
        sources.add(new CigramTmdbSource(config));
        catalog = new CigramMediaCatalog(sources, io, poster);
        coordinator = new CigramPlaybackCoordinator(providers, io, poster);
    }

    /** One line per provider: what is integrated and what each one still needs (settings / diagnostics screen). */
    public String providerReport() { return CigramProviders.report(providers); }
}
