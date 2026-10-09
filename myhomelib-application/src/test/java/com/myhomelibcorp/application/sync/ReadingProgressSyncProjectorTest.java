package com.myhomelibcorp.application.sync;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingProgressSyncProjectorTest {
    @Test
    void remoteProgressBecomesSharedShelfStateWithOriginatingDevice() {
        AtomicReference<ReadingProgressDto> saved = new AtomicReference<>();
        ReadingProgressRepository repo = new ReadingProgressRepository() {
            @Override public void save(ReadingProgressDto progress) { saved.set(progress); }
            @Override public Optional<ReadingProgressDto> findByBookId(String bookId) { return Optional.ofNullable(saved.get()); }
            @Override public void deleteByBookId(String bookId) { saved.set(null); }
        };
        var projector = new ReadingProgressSyncProjector(repo);
        SyncRecord remote = SyncRecord.live("progress:book-1", SyncEntityType.READING_PROGRESS, "book-1",
                2, 3, Instant.parse("2026-09-12T12:30:00Z"), "laptop-kyiv", Map.of(
                        "anchorId", "1:120:0:5", "paragraphIndex", "0", "paragraphId", "web:c2",
                        "charOffset", "5", "percent", "62.5", "chapterTitle", "Second", "chapterId", "c2",
                        "updatedAt", "2026-09-12T15:30:00", "readingTimeSeconds", "900"));

        ReadingProgressDto applied = projector.apply(remote);

        assertThat(applied.getBookId()).isEqualTo("book-1");
        assertThat(applied.getPercent()).isEqualTo(62.5);
        assertThat(applied.getLastDevice()).isEqualTo("laptop-kyiv");
        assertThat(applied.getChapterTitle()).isEqualTo("Second");
        assertThat(saved.get()).isSameAs(applied);
    }

    @Test
    void tombstoneRemovesProgressFromActiveShelfSource() {
        AtomicReference<String> deleted = new AtomicReference<>();
        ReadingProgressRepository repo = new ReadingProgressRepository() {
            @Override public void save(ReadingProgressDto progress) { }
            @Override public Optional<ReadingProgressDto> findByBookId(String bookId) { return Optional.empty(); }
            @Override public void deleteByBookId(String bookId) { deleted.set(bookId); }
        };
        var projector = new ReadingProgressSyncProjector(repo);
        projector.apply(SyncRecord.tombstone("progress:b", SyncEntityType.READING_PROGRESS, "b", 1, 2,
                Instant.parse("2026-09-12T12:30:00Z"), "phone"));
        assertThat(deleted.get()).isEqualTo("b");
    }

    @Test
    void sanitizesInvalidRemoteProgressBeforePersisting() {
        AtomicReference<ReadingProgressDto> saved = new AtomicReference<>();
        ReadingProgressRepository repo = new ReadingProgressRepository() {
            @Override public void save(ReadingProgressDto progress) { saved.set(progress); }
            @Override public Optional<ReadingProgressDto> findByBookId(String bookId) { return Optional.ofNullable(saved.get()); }
            @Override public void deleteByBookId(String bookId) { saved.set(null); }
        };
        var projector = new ReadingProgressSyncProjector(repo);
        SyncRecord remote = SyncRecord.live("progress:bad", SyncEntityType.READING_PROGRESS, "bad",
                1, 2, Instant.parse("2026-10-07T12:00:00Z"), "remote", Map.of(
                        "paragraphIndex", "-12",
                        "charOffset", "-5",
                        "percent", "NaN",
                        "readingTimeSeconds", "-300"));

        ReadingProgressDto applied = projector.apply(remote);

        assertThat(applied.getParagraphIndex()).isZero();
        assertThat(applied.getCharOffset()).isZero();
        assertThat(applied.getPercent()).isZero();
        assertThat(applied.getReadingTimeSeconds()).isZero();
        assertThat(saved.get()).isSameAs(applied);
    }

    @Test
    void clampsRemoteProgressPercentToValidRange() {
        AtomicReference<ReadingProgressDto> saved = new AtomicReference<>();
        ReadingProgressRepository repo = new ReadingProgressRepository() {
            @Override public void save(ReadingProgressDto progress) { saved.set(progress); }
            @Override public Optional<ReadingProgressDto> findByBookId(String bookId) { return Optional.ofNullable(saved.get()); }
            @Override public void deleteByBookId(String bookId) { saved.set(null); }
        };
        var projector = new ReadingProgressSyncProjector(repo);
        ReadingProgressDto applied = projector.apply(SyncRecord.live(
                "progress:high", SyncEntityType.READING_PROGRESS, "high", 1, 2,
                Instant.parse("2026-10-07T12:00:00Z"), "remote", Map.of("percent", "150.5")));

        assertThat(applied.getPercent()).isEqualTo(100.0);
    }

}
