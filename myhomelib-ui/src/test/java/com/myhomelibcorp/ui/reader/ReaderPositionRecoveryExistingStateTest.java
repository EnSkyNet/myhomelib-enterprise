package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReaderPositionRecoveryExistingStateTest {
    @TempDir Path temporary;

    @Test
    void replayUsesAnchorBeforeRepositoryDtoWasMutated() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("journal"));
        String before = "1:10:2:0";
        var dto = ReadingProgressDto.builder().bookId("book").anchorId(before).build();
        AtomicReference<ReadingProgressDto> value = new AtomicReference<>(dto);
        AtomicBoolean fail = new AtomicBoolean(true);
        var repo = mock(ReadingProgressRepository.class);
        when(repo.findByBookId("book")).thenAnswer(invocation -> Optional.ofNullable(value.get()));
        doAnswer(invocation -> {
            if (fail.get()) throw new IllegalStateException("database unavailable");
            value.set(invocation.getArgument(0));
            return null;
        }).when(repo).save(any(ReadingProgressDto.class));
        var expected = new ReaderPosition(1, 33, 2, 1);
        assertThat(newService(repo, journal).savePosition("c", "book", expected, 100)).isFalse();
        assertThat(journal.load("c", "book")).get()
                .extracting(ReaderPositionRecoveryJournal.Pending::originalAnchor).isEqualTo(before);

        // Simulate a crash and a reopened SQLite connection. The failing
        // repository's in-memory object had been mutated but not committed.
        value.set(ReadingProgressDto.builder().bookId("book").anchorId(before).build());
        fail.set(false);
        assertThat(newService(repo, journal).loadPosition("c", "book")).contains(expected);
        assertThat(value.get().getAnchorId()).isEqualTo(expected.serialize());
    }

    @Test
    void damagedRecoveryEvidenceDoesNotBlockValidSqliteBook() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("journal"));
        journal.prepare("c", "book", "0:0:0:0", new ReaderPosition(0, 10, 0, 0), 100);
        Path file = journal.file("c", "book");
        Files.writeString(file, "truncated");
        var repo = mock(ReadingProgressRepository.class);
        var current = new ReaderPosition(0, 25, 0, 0);
        when(repo.findByBookId("book")).thenReturn(Optional.of(
                ReadingProgressDto.builder().bookId("book").anchorId(current.serialize()).build()));
        assertThat(newService(repo, journal).loadPosition("c", "book")).contains(current);
        assertThat(Files.readString(file)).isEqualTo("truncated");
    }

    @Test
    void newTextSaveMustNotEraseCorruptEarlierRecoveryEvidence() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("protected-text"));
        journal.prepare("c", "book", null, new ReaderPosition(0, 10, 0, 0), 100);
        Path file = journal.file("c", "book");
        Files.writeString(file, "corrupt-text-evidence");
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenReturn(Optional.empty());

        assertThat(newService(repository, journal).savePosition("c", "book",
                new ReaderPosition(0, 20, 0, 0), 100)).isFalse();
        assertThat(Files.readString(file)).isEqualTo("corrupt-text-evidence");
        verify(repository, never()).save(any());
    }

    @Test
    void newerTextPositionCanReplaceVerifiedPendingEvidence() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("valid-text-replace"));
        journal.prepare("c", "book", null, new ReaderPosition(0, 10, 0, 0), 100);
        journal.prepare("c", "book", null, new ReaderPosition(0, 20, 0, 0), 100);
        assertThat(journal.load("c", "book")).get()
                .extracting(ReaderPositionRecoveryJournal.Pending::position)
                .isEqualTo(new ReaderPosition(0, 20, 0, 0));
    }

    private static NewReaderPersistenceService newService(ReadingProgressRepository progress,
            ReaderPositionRecoveryJournal journal) {
        var lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(
                new com.myhomelibcorp.domain.model.collection.Collection(
                        "c", "Test", null, null, 0, null, null, null, null));
        return new NewReaderPersistenceService(progress, mock(BookmarkRepository.class),
                mock(LocalizationService.class), lifecycle,
                new LibraryOperationCoordinator(), journal);
    }
}
