package com.Cigram.vid;

import android.os.Handler;
import android.os.Looper;

/** Delivers a result on the thread the UI lives on. The unit tests use {@link #DIRECT}. */
public interface CigramPoster {
    void post(Runnable r);

    /** Runs the task right where it is called (tests, or code that is already on the right thread). */
    CigramPoster DIRECT = new CigramPoster() {
        @Override public void post(Runnable r) { r.run(); }
    };

    final class Main implements CigramPoster {
        private final Handler handler = new Handler(Looper.getMainLooper());
        @Override public void post(Runnable r) { handler.post(r); }
    }
}
