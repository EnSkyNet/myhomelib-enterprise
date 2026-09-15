package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewReaderPersistencePositionRoundTripTest {

    @Test
    void semanticReaderPositionSurvivesPersistenceRoundTripExactly() {
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        AtomicReference<ReadingProgressDto> stored = new AtomicReference<>();
        when(progress.findByBookId("book-1")).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        doAnswer(invocation -> {
            ReadingProgressDto value = invocation.getArgument(0);
            stored.set(value);
            return null;
        }).when(progress).save(any(ReadingProgressDto.class));
        NewReaderPersistenceService service = new NewReaderPersistenceService(
                progress, mock(BookmarkRepository.class), mock(LocalizationService.class));

        ReaderPosition expected = new ReaderPosition(9, 123_456L, 37, 18);
        assertThat(service.savePosition("book-1", expected, 500_000L)).isTrue();

        ReaderPosition restored = service.loadPosition("book-1").orElseThrow();
        assertThat(restored).isEqualTo(expected);
        assertThat(stored.get().getAnchorId()).isEqualTo("9:123456:37:18");
        assertThat(stored.get().getParagraphIndex()).isEqualTo(37);
        assertThat(stored.get().getCharOffset()).isEqualTo(18);
        assertThat(stored.get().getPercent()).isCloseTo(24.6912, org.assertj.core.data.Offset.offset(0.0001));
    }
}
