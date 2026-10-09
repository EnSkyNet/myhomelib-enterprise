package com.myhomelibcorp.application.kobo;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class KoboProgressTranslatorTest {
    @Test
    void mapsProgressBothDirectionsDeterministically() {
        ReadingProgressDto source = ReadingProgressDto.builder()
                .bookId("book-1").anchorId("chapter-2").paragraphId("chapter-2")
                .paragraphIndex(7).charOffset(11).percent(42.5).chapterId("c2")
                .chapterTitle("Two").updatedAt(LocalDateTime.parse("2026-10-01T12:00:00"))
                .readingTimeSeconds(123).lastDevice("desktop").build();

        KoboProgressState kobo = KoboProgressTranslator.toKobo(source, 9);
        assertThat(kobo.percent()).isEqualTo(42.5);
        assertThat(kobo.status()).isEqualTo("Reading");
        assertThat(kobo.location()).isEqualTo("chapter-2");
        assertThat(kobo.version()).isEqualTo(9);

        KoboProgressUpdate incoming = new KoboProgressUpdate("book-1", 55.25, "Reading", "chapter-3",
                Instant.parse("2026-10-01T13:00:00Z"), 9);
        ReadingProgressDto mapped = KoboProgressTranslator.toMyHomeLib(incoming, source);
        assertThat(mapped.getPercent()).isEqualTo(55.25);
        assertThat(mapped.getAnchorId()).isEqualTo("chapter-3");
        assertThat(mapped.getReadingTimeSeconds()).isEqualTo(123);
        assertThat(mapped.getLastDevice()).isEqualTo("kobo");
    }

    @Test
    void staleServerVersionCanNeverOverwriteNewerCanonicalProgress() {
        KoboProgressState canonical = new KoboProgressState("book-1", 80, "Reading", "c8",
                Instant.parse("2026-10-01T10:00:00Z"), 12);

        KoboProgressUpdate staleEvenWithFutureClock = new KoboProgressUpdate("book-1", 20, "Reading", "c2",
                Instant.parse("2030-01-01T00:00:00Z"), 11);
        assertThat(KoboProgressTranslator.shouldAccept(staleEvenWithFutureClock, canonical)).isFalse();

        KoboProgressUpdate sameVersionOlderClock = new KoboProgressUpdate("book-1", 70, "Reading", "c7",
                Instant.parse("2026-10-01T09:59:59Z"), 12);
        assertThat(KoboProgressTranslator.shouldAccept(sameVersionOlderClock, canonical)).isFalse();

        KoboProgressUpdate nextVersion = new KoboProgressUpdate("book-1", 90, "Reading", "c9",
                Instant.parse("2026-09-30T00:00:00Z"), 13);
        assertThat(KoboProgressTranslator.shouldAccept(nextVersion, canonical)).isTrue();
    }

    @Test
    void finishedAndReadyStatesAreStable() {
        ReadingProgressDto zero = ReadingProgressDto.builder().bookId("b").percent(0).build();
        ReadingProgressDto done = ReadingProgressDto.builder().bookId("b").percent(100).build();
        assertThat(KoboProgressTranslator.toKobo(zero, 1).status()).isEqualTo("ReadyToRead");
        assertThat(KoboProgressTranslator.toKobo(done, 2).status()).isEqualTo("Finished");
    }
}
