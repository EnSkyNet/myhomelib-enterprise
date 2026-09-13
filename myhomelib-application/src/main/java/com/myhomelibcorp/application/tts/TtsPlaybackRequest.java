package com.myhomelibcorp.application.tts;

public record TtsPlaybackRequest(String text, long sourceStartOffset, String languageTag, String voiceId, double rate) {
    public TtsPlaybackRequest {
        text = text == null ? "" : text;
        languageTag = languageTag == null ? "" : languageTag.trim();
        voiceId = voiceId == null ? "" : voiceId.trim();
        if (sourceStartOffset < 0) throw new IllegalArgumentException("sourceStartOffset must be >= 0");
        if (!Double.isFinite(rate) || rate < 0.5 || rate > 2.0) throw new IllegalArgumentException("rate must be 0.5..2.0");
    }
}
