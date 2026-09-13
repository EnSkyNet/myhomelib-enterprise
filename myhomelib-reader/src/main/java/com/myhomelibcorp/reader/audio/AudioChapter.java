package com.myhomelibcorp.reader.audio;

public record AudioChapter(String title, long startMillis, long endMillis) {
    public AudioChapter {
        title = title == null || title.isBlank() ? "Chapter" : title.trim();
        startMillis = Math.max(0L, startMillis);
        endMillis = Math.max(startMillis, endMillis);
    }

    public boolean contains(long millis) {
        return millis >= startMillis && (millis < endMillis || endMillis == startMillis);
    }
}
