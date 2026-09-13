package com.myhomelibcorp.application.sync;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AudiobookProgressSyncTest {
    @Test void preservesOpaqueAudioAnchorAcrossSyncProjection() {
        ReadingProgressDto source = ReadingProgressDto.builder()
                .bookId("book-1").anchorId("audio:123456:2:7")
                .paragraphIndex(7).paragraphId("audio-track:2").charOffset(0).percent(52.25)
                .updatedAt(LocalDateTime.of(2026, 9, 13, 12, 0)).readingTimeSeconds(900).lastDevice("desktop").build();
        SyncRecord record = new UserDataSyncRecordFactory().readingProgress(source, 3, 4, Instant.parse("2026-09-13T09:00:00Z"), "laptop");
        ReadingProgressRepository repository = mock(ReadingProgressRepository.class);
        ReadingProgressDto projected = new ReadingProgressSyncProjector(repository).apply(record);
        assertThat(projected.getAnchorId()).isEqualTo("audio:123456:2:7");
        assertThat(projected.getParagraphId()).isEqualTo("audio-track:2");
        assertThat(projected.getPercent()).isEqualTo(52.25);
        verify(repository).save(any(ReadingProgressDto.class));
    }
}
