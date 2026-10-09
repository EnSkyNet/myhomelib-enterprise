package com.myhomelibcorp.application.kobo;

import com.myhomelibcorp.application.dto.ReadingProgressDto;

import java.time.Instant;
import java.time.ZoneOffset;

/** Pure deterministic mapping between Kobo progress and the existing MyHomeLib reading-progress model. */
public final class KoboProgressTranslator {
    private KoboProgressTranslator() { }

    public static KoboProgressState toKobo(ReadingProgressDto progress, long version) {
        if (progress == null || progress.getBookId() == null || progress.getBookId().isBlank()) {
            throw new IllegalArgumentException("Reading progress with book id is required");
        }
        double percent = clamp(progress.getPercent());
        String status = percent >= 99.999 ? "Finished" : percent > 0.0001 ? "Reading" : "ReadyToRead";
        Instant modified = progress.getUpdatedAt() == null
                ? Instant.EPOCH : progress.getUpdatedAt().toInstant(ZoneOffset.UTC);
        String location = progress.getAnchorId() == null ? "" : progress.getAnchorId();
        return new KoboProgressState(progress.getBookId(), percent, status, location, modified, version);
    }

    public static ReadingProgressDto toMyHomeLib(KoboProgressUpdate update, ReadingProgressDto canonical) {
        int paragraphIndex = canonical == null ? 0 : canonical.getParagraphIndex();
        int charOffset = canonical == null ? 0 : canonical.getCharOffset();
        long readingTime = canonical == null ? 0L : canonical.getReadingTimeSeconds();
        String chapterId = canonical == null ? "" : safe(canonical.getChapterId());
        String chapterTitle = canonical == null ? "" : safe(canonical.getChapterTitle());
        String location = update.location().isBlank()
                ? canonical == null ? "" : safe(canonical.getAnchorId())
                : update.location();
        return ReadingProgressDto.builder()
                .bookId(update.bookId())
                .anchorId(location)
                .paragraphId(location)
                .paragraphIndex(paragraphIndex)
                .charOffset(charOffset)
                .percent(clamp(update.percent()))
                .chapterId(chapterId)
                .chapterTitle(chapterTitle)
                .updatedAt(java.time.LocalDateTime.ofInstant(update.modifiedAt(), ZoneOffset.UTC))
                .readingTimeSeconds(readingTime)
                .lastDevice("kobo")
                .build();
    }

    /**
     * Reject updates based on an older server version. For equal versions, timestamp is only a
     * tie-breaker; it is never the sole conflict authority.
     */
    public static boolean shouldAccept(KoboProgressUpdate incoming, KoboProgressState canonical) {
        if (canonical == null) return true;
        if (incoming.baseVersion() < canonical.version()) return false;
        if (incoming.baseVersion() > canonical.version()) return true;
        return !incoming.modifiedAt().isBefore(canonical.modifiedAt());
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) return 0.0;
        return Math.max(0.0, Math.min(100.0, value));
    }
    private static String safe(String value) { return value == null ? "" : value; }
}
