package com.myhomelibcorp.application.sync;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;

/** Applies an already-resolved READING_PROGRESS sync record to the shared desktop/web progress store. */
public final class ReadingProgressSyncProjector {
    private final ReadingProgressRepository repository;

    public ReadingProgressSyncProjector(ReadingProgressRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public ReadingProgressDto apply(SyncRecord record) {
        Objects.requireNonNull(record, "record");
        if (record.entityType() != SyncEntityType.READING_PROGRESS) {
            throw new IllegalArgumentException("Expected READING_PROGRESS record");
        }
        if (record.tombstone()) {
            repository.deleteByBookId(record.localKey());
            return null;
        }
        Map<String, String> p = record.payload();
        ReadingProgressDto dto = ReadingProgressDto.builder()
                .bookId(record.localKey())
                .anchorId(text(p, "anchorId"))
                .paragraphIndex(integer(p, "paragraphIndex", 0))
                .paragraphId(text(p, "paragraphId"))
                .charOffset(integer(p, "charOffset", 0))
                .percent(decimal(p, "percent", 0.0))
                .chapterTitle(text(p, "chapterTitle"))
                .chapterId(text(p, "chapterId"))
                .updatedAt(timestamp(p.get("updatedAt"), record))
                .readingTimeSeconds(longValue(p, "readingTimeSeconds", 0L))
                .lastDevice(record.deviceId())
                .build();
        repository.save(dto);
        return dto;
    }

    private static LocalDateTime timestamp(String value, SyncRecord record) {
        if (value != null && !value.isBlank()) {
            try { return LocalDateTime.parse(value); }
            catch (RuntimeException ignored) { }
        }
        return LocalDateTime.ofInstant(record.updatedAt(), ZoneOffset.UTC);
    }

    private static String text(Map<String, String> p, String key) { return p.getOrDefault(key, ""); }
    private static int integer(Map<String, String> p, String key, int fallback) {
        try { return Integer.parseInt(p.get(key)); } catch (RuntimeException ignored) { return fallback; }
    }
    private static long longValue(Map<String, String> p, String key, long fallback) {
        try { return Long.parseLong(p.get(key)); } catch (RuntimeException ignored) { return fallback; }
    }
    private static double decimal(Map<String, String> p, String key, double fallback) {
        try { return Double.parseDouble(p.get(key)); } catch (RuntimeException ignored) { return fallback; }
    }
}
