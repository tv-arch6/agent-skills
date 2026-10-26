package com.google.android.exoplayer2;
public interface Player {
    long getCurrentPosition();
    PlaybackException getPlayerError();
    void pause();
}
