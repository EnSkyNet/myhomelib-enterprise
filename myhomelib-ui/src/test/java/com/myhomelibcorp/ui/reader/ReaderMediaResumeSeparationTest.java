package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.audio.AudioPosition;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReaderMediaResumeSeparationTest {
    @TempDir Path temporary;

    @Test
    void resumeBothMediaAfterAlternatingSavesAndServiceReopen() {
        AtomicReference<ReadingProgressDto> current = new AtomicReference<>();
        var repository = simulatedRepository(current);
        var service = service(repository);
        var text = new ReaderPosition(1, 1234, 5, 6);
        var audio = new AudioPosition(84_000L, 1, 2);
        assertThat(service.savePosition("c", "book", text, 5_000)).isTrue();
        assertThat(service.saveAudioPosition("c", "book", audio, 42)).isTrue();
        assertThat(service(repository).loadPosition("c", "book")).contains(text);
        assertThat(service(repository).loadAudioPosition("c", "book")).contains(audio);
        var newerText = new ReaderPosition(1, 2456, 7, 0);
        assertThat(service.savePosition("c", "book", newerText, 5_000)).isTrue();
        assertThat(service(repository).loadPosition("c", "book")).contains(newerText);
        assertThat(service(repository).loadAudioPosition("c", "book")).contains(audio);
    }

    @Test
    void pendingTextRecoveryDoesNotOverwriteCommittedAudioResume() throws Exception {
        AtomicReference<ReadingProgressDto> current = new AtomicReference<>(ReadingProgressDto.builder()
                .bookId("book").anchorId("1:100:0:0").textAnchorId("1:100:0:0").build());
        var delegate = simulatedRepository(current);
        var failing = mock(ReadingProgressRepository.class);
        when(failing.findByBookId("book")).thenAnswer(invocation -> delegate.findByBookId("book"));
        java.util.concurrent.atomic.AtomicBoolean unavailable = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> {
            if (unavailable.get()) throw new IllegalStateException("database offline");
            delegate.save(invocation.getArgument(0));
            return null;
        }).when(failing).save(any(ReadingProgressDto.class));
        var service = service(failing);
        var nextText = new ReaderPosition(1, 260, 1, 0);
        var audio = new AudioPosition(18000L, 0, 2);
        assertThat(service.savePosition("c", "book", nextText, 5000)).isFalse();
        unavailable.set(false);
        assertThat(service.saveAudioPosition("c", "book", audio, 30)).isTrue();
        assertThat(service(failing).loadPosition("c", "book")).contains(nextText);
        assertThat(service(failing).loadAudioPosition("c", "book")).contains(audio);
    }

    private ReadingProgressRepository simulatedRepository(AtomicReference<ReadingProgressDto> current) {
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenAnswer(invocation -> Optional.ofNullable(current.get()));
        doAnswer(invocation -> {
            ReadingProgressDto saved = invocation.getArgument(0);
            ReadingProgressDto before = current.get();
            // Model the actual SQLite UPSERT COALESCE columns, including last-active projection.
            if (before != null) {
                if (saved.getTextAnchorId() == null) saved.setTextAnchorId(before.getTextAnchorId());
                if (saved.getAudioAnchorId() == null) saved.setAudioAnchorId(before.getAudioAnchorId());
            }
            current.set(saved);
            return null;
        }).when(repository).save(any(ReadingProgressDto.class));
        return repository;
    }

    private NewReaderPersistenceService service(ReadingProgressRepository repository) {
        var lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(
                new Collection("c", "Test", null, null, 0, null, null, null, null));
        return new NewReaderPersistenceService(repository, mock(BookmarkRepository.class),
                mock(LocalizationService.class), lifecycle, new LibraryOperationCoordinator(),
                new ReaderPositionRecoveryJournal(temporary.resolve("text")),
                new ReaderAudioRecoveryJournal(temporary.resolve("audio")));
    }
}
