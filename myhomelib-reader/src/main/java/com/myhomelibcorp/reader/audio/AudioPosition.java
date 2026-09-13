package com.myhomelibcorp.reader.audio;

import java.util.Optional;

/** Stable audiobook resume anchor. Milliseconds are local to the selected track. */
public record AudioPosition(long millis, int trackIndex, int chapterIndex) {
    public AudioPosition {
        millis = Math.max(0L, millis);
        trackIndex = Math.max(0, trackIndex);
        chapterIndex = Math.max(0, chapterIndex);
    }

    public static AudioPosition start() { return new AudioPosition(0L, 0, 0); }

    public String serialize() {
        return "audio:" + millis + ":" + trackIndex + ":" + chapterIndex;
    }

    public static Optional<AudioPosition> parse(String value) {
        if (value == null || !value.startsWith("audio:")) return Optional.empty();
        String[] p = value.split(":", -1);
        if (p.length != 4) return Optional.empty();
        try {
            return Optional.of(new AudioPosition(Long.parseLong(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }
}
