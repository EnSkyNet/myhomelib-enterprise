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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReaderPositionRecoveryJournalTest {
    @TempDir Path temporary;

    @Test
    void recordSurvivesStoreReconstructionAndUsesCollisionSafeNames() throws Exception {
        Path root = temporary.resolve("recovery");
        var journal = new ReaderPositionRecoveryJournal(root);
        var latest = new ReaderPosition(3, 87L, 7, 2);
        journal.prepare("a/b", "c", null, latest, 100L);
        journal.prepare("a", "b/c", "0:0:0:0", latest, 100L);
        assertThat(journal.file("a/b", "c")).isNotEqualTo(journal.file("a", "b/c"));
        var reopened = new ReaderPositionRecoveryJournal(root);
        assertThat(reopened.load("a/b", "c")).get().extracting(ReaderPositionRecoveryJournal.Pending::position)
                .isEqualTo(latest);
        reopened.acknowledge("a/b", "c");
        assertThat(reopened.load("a/b", "c")).isEmpty();
        assertThat(reopened.load("a", "b/c")).isPresent();
    }

    @Test
    void oversizedOrNegativePositionsAreRejectedBeforeRecoveryEvidenceIsPublished() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("journal"));
        String giantBookId = "long-id-" + "ї".repeat(3000);
        assertThatThrownBy(() -> journal.prepare("c", giantBookId, null,
                new ReaderPosition(0, 4, 0, 0), 10)).isInstanceOf(IOException.class);
        assertThat(journal.load("c", giantBookId)).isEmpty();

        assertThatThrownBy(() -> journal.prepare("c", "book", null,
                new ReaderPosition(-1, 4, 0, 0), 10)).isInstanceOf(IOException.class);
        assertThat(journal.load("c", "book")).isEmpty();
    }

    @Test
    void corruptJournalIsNotAppliedOrDeleted() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("journal"));
        journal.prepare("c", "b", null, new ReaderPosition(0, 10, 0, 0), 20);
        Path path = journal.file("c", "b");
        Files.writeString(path, Files.readString(path, StandardCharsets.UTF_8).replace("0:10:0:0", "0:90:0:0"));
        assertThatThrownBy(() -> journal.load("c", "b")).isInstanceOf(IOException.class);
        assertThat(path).exists();
    }

    @Test
    void refusesSymlinkAsRecoveryRecord() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("journal"));
        journal.prepare("c", "b", null, new ReaderPosition(0, 3, 0, 0), 20);
        Path path = journal.file("c", "b");
        Path replacement = temporary.resolve("replacement");
        Files.writeString(replacement, "invalid");
        Files.delete(path);
        try {
            Files.createSymbolicLink(path, replacement);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }
        assertThatThrownBy(() -> journal.load("c", "b")).isInstanceOf(IOException.class);
        assertThat(Files.readString(replacement)).isEqualTo("invalid");
    }

    @Test
    void failedDatabaseWriteRecoversAfterServiceRestart() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("journal"));
        Map<String, ReadingProgressDto> records = new HashMap<>();
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenAnswer(invocation -> Optional.ofNullable(records.get("book")));
        doAnswer(invocation -> {
            throw new IllegalStateException("SQLite temporarily unavailable");
        }).when(repository).save(any(ReadingProgressDto.class));

        var latest = new ReaderPosition(2, 123, 4, 5);
        assertThat(service(repository, journal).savePosition("collection", "book", latest, 1000)).isFalse();
        assertThat(journal.load("collection", "book")).isPresent();

        reset(repository);
        when(repository.findByBookId("book")).thenAnswer(invocation -> Optional.ofNullable(records.get("book")));
        doAnswer(invocation -> { ReadingProgressDto dto = invocation.getArgument(0);
            records.put("book", dto); return null; }).when(repository).save(any(ReadingProgressDto.class));
        assertThat(service(repository, journal).loadPosition("collection", "book")).contains(latest);
        assertThat(records.get("book").getAnchorId()).isEqualTo(latest.serialize());
        assertThat(journal.load("collection", "book")).isEmpty();
    }

    @Test
    void unrelatedNewerDatabaseStateIsNeverOverwrittenByOldRecovery() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("journal"));
        var old = new ReaderPosition(0, 10, 0, 0);
        var pending = new ReaderPosition(0, 30, 0, 0);
        var current = new ReaderPosition(0, 99, 0, 0);
        journal.prepare("collection", "book", old.serialize(), pending, 1000);
        var repo = mock(ReadingProgressRepository.class);
        when(repo.findByBookId("book")).thenReturn(Optional.of(
                ReadingProgressDto.builder().bookId("book").anchorId(current.serialize()).build()));
        assertThat(service(repo, journal).loadPosition("collection", "book")).contains(current);
        verify(repo, never()).save(any());
        assertThat(journal.load("collection", "book")).isPresent();
    }

    @Test
    void alreadyCommittedDatabaseValueClearsObsoleteJournal() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("journal"));
        var position = new ReaderPosition(1, 25, 0, 0);
        journal.prepare("collection", "book", null, position, 100);
        var repo = mock(ReadingProgressRepository.class);
        when(repo.findByBookId("book")).thenReturn(Optional.of(
                ReadingProgressDto.builder().bookId("book").anchorId(position.serialize()).build()));
        assertThat(service(repo, journal).loadPosition("collection", "book")).contains(position);
        verify(repo, never()).save(any());
        assertThat(journal.load("collection", "book")).isEmpty();
    }

    @Test
    void revisionBoundTextRecoveryRefusesSameAnchorFromNewerSqliteGeneration() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("text-generation"));
        var old = new ReaderPosition(0, 10, 0, 0);
        var pending = new ReaderPosition(0, 80, 0, 0);
        journal.prepare("collection", "book", old.serialize(), pending, 100, 5L);
        var restored = new ReaderPositionRecoveryJournal(temporary.resolve("text-generation"));
        assertThat(restored.load("collection", "book")).get()
                .extracting(ReaderPositionRecoveryJournal.Pending::expectedRevision).isEqualTo(5L);
        var repo = mock(ReadingProgressRepository.class);
        when(repo.findByBookId("book")).thenReturn(Optional.of(
                ReadingProgressDto.builder().bookId("book").revision(8L)
                        .anchorId(old.serialize()).textAnchorId(old.serialize()).build()));
        assertThat(service(repo, restored).loadPosition("collection", "book")).contains(old);
        verify(repo, never()).save(any());
        assertThat(restored.load("collection", "book")).isPresent();
    }

    @Test
    void revisionBoundTextRecoveryReplaysOnlyMatchingSqliteGeneration() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("text-replay"));
        var old = new ReaderPosition(0, 10, 0, 0);
        var pending = new ReaderPosition(0, 80, 0, 0);
        journal.prepare("collection", "book", old.serialize(), pending, 100, 5L);
        var repo = mock(ReadingProgressRepository.class);
        when(repo.findByBookId("book")).thenReturn(Optional.of(
                ReadingProgressDto.builder().bookId("book").revision(5L)
                        .anchorId(old.serialize()).textAnchorId(old.serialize()).build()));
        assertThat(service(repo, journal).loadPosition("collection", "book")).contains(pending);
        verify(repo).save(argThat(saved -> saved.getRevision().equals(5L)
                && pending.serialize().equals(saved.getTextAnchorId())));
        assertThat(journal.load("collection", "book")).isEmpty();
    }

    @Test
    void legacyTextRecoveryIsRetainedButNeverReplayedOntoVersionedSqlite() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("text-legacy"));
        var old = new ReaderPosition(0, 10, 0, 0);
        journal.prepare("collection", "book", old.serialize(), new ReaderPosition(0, 80, 0, 0), 100);
        var repo = mock(ReadingProgressRepository.class);
        when(repo.findByBookId("book")).thenReturn(Optional.of(
                ReadingProgressDto.builder().bookId("book").revision(9L)
                        .anchorId(old.serialize()).textAnchorId(old.serialize()).build()));
        assertThat(service(repo, journal).loadPosition("collection", "book")).contains(old);
        verify(repo, never()).save(any());
        assertThat(journal.load("collection", "book")).isPresent();
    }

    @Test
    void textRecoveryAfterDeletionMustNotReplayStaleAbsentGeneration() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("deleted-generation"));
        journal.prepare("collection", "book", null, new ReaderPosition(0, 80, 0, 0), 100, -1L);
        var repo = mock(ReadingProgressRepository.class);
        when(repo.findByBookId("book")).thenReturn(Optional.empty());
        // Another writer created and deleted the row after this journal was prepared.
        when(repo.revisionWhenAbsent("book")).thenReturn(-8L);
        assertThat(service(repo, journal).loadPosition("collection", "book")).isEmpty();
        verify(repo, never()).save(any());
        assertThat(journal.load("collection", "book")).isPresent();
    }

    @Test
    void modifiedTextRevisionIsRejectedWithoutDeletingEvidence() throws Exception {
        var journal = new ReaderPositionRecoveryJournal(temporary.resolve("modified-revision"));
        journal.prepare("collection", "book", null, new ReaderPosition(0, 80, 0, 0), 100, -1L);
        Path path = journal.file("collection", "book");
        String original = Files.readString(path);
        Files.writeString(path, original.replace("\n-1\n", "\n-9\n"));
        assertThatThrownBy(() -> journal.load("collection", "book")).isInstanceOf(IOException.class);
        assertThat(Files.readString(path)).contains("\n-9\n");
    }

    private static NewReaderPersistenceService service(ReadingProgressRepository progress,
                                                        ReaderPositionRecoveryJournal journal) {
        var lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(
                new com.myhomelibcorp.domain.model.collection.Collection(
                        "collection", "Test", null, null, 0, null, null, null, null));
        return new NewReaderPersistenceService(progress, mock(BookmarkRepository.class),
                mock(LocalizationService.class), lifecycle, new LibraryOperationCoordinator(), journal);
    }
}
