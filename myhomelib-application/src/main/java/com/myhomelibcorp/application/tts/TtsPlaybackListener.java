package com.myhomelibcorp.application.tts;

public interface TtsPlaybackListener {
    default void onStateChanged(TtsPlaybackState state) { }
    default void onSentenceStarted(TtsSentence sentence) { }
    default void onFailed(Throwable error) { }
}
