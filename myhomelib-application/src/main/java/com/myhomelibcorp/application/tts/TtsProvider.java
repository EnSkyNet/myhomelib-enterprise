package com.myhomelibcorp.application.tts;

import java.util.List;

/** Platform TTS SPI. Implementations may block while one utterance is spoken; callers run them off the UI thread. */
public interface TtsProvider {
    String id();
    String displayName();
    boolean isAvailable();
    List<TtsVoice> availableVoices();
    void speak(String text, String voiceId, double rate) throws Exception;
    void cancelCurrent();
}
