package com.myhomelibcorp.reader.audio;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record AudioTrack(Path path, String title, long durationMillis, List<AudioChapter> chapters) {
    public AudioTrack {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        title = title == null || title.isBlank() ? path.getFileName().toString() : title.trim();
        durationMillis = Math.max(1L, durationMillis);
        chapters = chapters == null || chapters.isEmpty()
                ? List.of(new AudioChapter(title, 0L, durationMillis))
                : List.copyOf(chapters);
    }

    public int chapterIndexAt(long millis) {
        for (int i = 0; i < chapters.size(); i++) if (chapters.get(i).contains(millis)) return i;
        return Math.max(0, chapters.size() - 1);
    }
}
