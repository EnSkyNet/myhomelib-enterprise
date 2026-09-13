package com.myhomelibcorp.application.tts;

public interface TtsPlayback {
    TtsPlaybackState state();
    void pause();
    void resume();
    void stop();
}
