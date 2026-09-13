package com.myhomelibcorp.application.tts;

public record TtsVoice(String id, String displayName, String languageTag) {
    public TtsVoice {
        id = clean(id);
        displayName = clean(displayName);
        languageTag = clean(languageTag);
        if (id.isBlank()) throw new IllegalArgumentException("id is required");
        if (displayName.isBlank()) displayName = id;
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    @Override public String toString() { return languageTag.isBlank() ? displayName : displayName + " (" + languageTag + ")"; }
}
