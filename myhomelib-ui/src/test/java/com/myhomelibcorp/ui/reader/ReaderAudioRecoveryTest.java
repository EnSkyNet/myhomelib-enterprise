package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.reader.audio.AudioPosition;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReaderAudioRecoveryTest {
    @TempDir Path temporary;

    @Test
    void failedAudioSqliteWriteCanRecoverAfterRestartWithoutMutatingStoredDto() throws Exception {
        var journal = new ReaderAudioRecoveryJournal(temporary.resolve("audio"));
        AudioPosition old = new AudioPosition(1000, 1, 2);
        AudioPosition next = new AudioPosition(55443, 1, 2);
        var stored = new AtomicReference<>(ReadingProgressDto.builder()
                .bookId("book").anchorId(old.serialize()).percent(20).build());
        var failing = new AtomicBoolean(true);
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenAnswer(inv -> Optional.ofNullable(stored.get()));
        doAnswer(inv -> {
            if (failing.get()) throw new IllegalStateException("SQLite unavailable");
            stored.set(inv.getArgument(0));
            return null;
        }).when(repository).save(any());

        assertThat(service(repository, journal).saveAudioPosition("c", "book", next, 63.0)).isFalse();
        // Important: the object returned by a repository is not an uncommitted write buffer.
        assertThat(stored.get().getAnchorId()).isEqualTo(old.serialize());
        assertThat(journal.load("c", "book")).isPresent();
        failing.set(false);
        assertThat(service(repository, journal).loadAudioPosition("c", "book")).contains(next);
        assertThat(stored.get().getPercent()).isEqualTo(63.0);
        assertThat(journal.load("c", "book")).isEmpty();
    }

    @Test
    void successfulWriteAcknowledgesAudioJournalAndDoesNotDamageTextNamespace() throws Exception {
        var audioJournal = new ReaderAudioRecoveryJournal(temporary.resolve("audio"));
        var textJournal = new ReaderPositionRecoveryJournal(temporary.resolve("text"));
        textJournal.prepare("c", "book", null,
                new com.myhomelibcorp.reader.api.ReaderPosition(0, 20, 0, 0), 100);
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenReturn(Optional.empty());
        assertThat(service(repository, audioJournal).saveAudioPosition("c", "book",
                new AudioPosition(123, 0, 0), 23.0)).isTrue();
        assertThat(audioJournal.load("c", "book")).isEmpty();
        assertThat(textJournal.load("c", "book")).isPresent();
    }

    @Test
    void conflictingNewerSqliteAnchorIsPreservedAndRecoveryEvidenceRemains() throws Exception {
        var journal = new ReaderAudioRecoveryJournal(temporary.resolve("audio"));
        journal.prepare("c", "book", "audio:100:0:0", new AudioPosition(200, 0, 0), 40);
        var repository = mock(ReadingProgressRepository.class);
        var newer = new AudioPosition(300, 0, 0);
        when(repository.findByBookId("book")).thenReturn(Optional.of(
                ReadingProgressDto.builder().bookId("book").anchorId(newer.serialize()).build()));
        assertThat(service(repository, journal).loadAudioPosition("c", "book")).contains(newer);
        verify(repository, never()).save(any());
        assertThat(journal.load("c", "book")).isPresent();
    }

    @Test
    void damagedRecoveryFileIsPreservedWhileValidSqliteAudioOpens() throws Exception {
        var journal = new ReaderAudioRecoveryJournal(temporary.resolve("audio"));
        journal.prepare("c", "book", null, new AudioPosition(200, 0, 0), 40);
        Path file = journal.file("c", "book");
        Files.writeString(file, "truncated");
        assertThatThrownBy(() -> journal.load("c", "book")).isInstanceOf(IOException.class);
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenReturn(Optional.of(
                ReadingProgressDto.builder().bookId("book").anchorId("audio:300:0:0").build()));
        assertThat(service(repository, journal).loadAudioPosition("c", "book"))
                .contains(new AudioPosition(300, 0, 0));
        assertThat(Files.readString(file)).isEqualTo("truncated");
    }

    @Test
    void newAudioSaveMustNotEraseCorruptEarlierRecoveryEvidence() throws Exception {
        var journal = new ReaderAudioRecoveryJournal(temporary.resolve("audio-corrupt-protected"));
        var previous = new AudioPosition(10, 0, 0);
        journal.prepare("c", "book", null, previous, 1);
        Path file = journal.file("c", "book");
        Files.writeString(file, "corrupt-audio-evidence");
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenReturn(Optional.empty());

        assertThat(service(repository, journal).saveAudioPosition("c", "book",
                new AudioPosition(20, 0, 0), 2)).isFalse();
        assertThat(Files.readString(file)).isEqualTo("corrupt-audio-evidence");
        verify(repository, never()).save(any());
    }

    @Test
    void newerAudioPositionCanReplaceVerifiedPendingEvidence() throws Exception {
        var journal = new ReaderAudioRecoveryJournal(temporary.resolve("audio-valid-replace"));
        journal.prepare("c", "book", null, new AudioPosition(10, 0, 0), 1);
        journal.prepare("c", "book", null, new AudioPosition(20, 0, 0), 2);
        assertThat(journal.load("c", "book")).get()
                .extracting(ReaderAudioRecoveryJournal.Pending::position)
                .isEqualTo(new AudioPosition(20, 0, 0));
    }

    @Test
    void refuseAudioRecoverySymlinkAndInvalidPercent() throws Exception {
        var journal = new ReaderAudioRecoveryJournal(temporary.resolve("audio"));
        assertThatThrownBy(() -> journal.prepare("c", "book", null,
                new AudioPosition(10, 0, 0), Double.NaN)).isInstanceOf(IOException.class);
        journal.prepare("c", "book", null, new AudioPosition(10, 0, 0), 2);
        Path path = journal.file("c", "book");
        var outside = temporary.resolve("outside");
        Files.writeString(outside, "untouched");
        Files.delete(path);
        try { Files.createSymbolicLink(path, outside); }
        catch (UnsupportedOperationException | IOException | SecurityException unavailable) { return; }
        assertThatThrownBy(() -> journal.load("c", "book")).isInstanceOf(IOException.class);
        assertThat(Files.readString(outside)).isEqualTo("untouched");
    }

    @Test
    void failedTextWriteDoesNotMutateRepositoryObject() throws Exception {
        Path blockingFile = temporary.resolve("not-a-directory");
        Files.writeString(blockingFile, "blocked");
        var textJournal = new ReaderPositionRecoveryJournal(blockingFile.resolve("journal"));
        var initial = ReadingProgressDto.builder().bookId("book").anchorId("0:10:0:0").build();
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenReturn(Optional.of(initial));
        var service = new NewReaderPersistenceService(repository, mock(BookmarkRepository.class),
                mock(LocalizationService.class), lifecycle(), new LibraryOperationCoordinator(), textJournal,
                new ReaderAudioRecoveryJournal(temporary.resolve("audio")));
        assertThat(service.savePosition("c", "book",
                new com.myhomelibcorp.reader.api.ReaderPosition(0, 33, 0, 0), 100)).isFalse();
        assertThat(initial.getAnchorId()).isEqualTo("0:10:0:0");
        verify(repository, never()).save(any());
    }

    @Test
    void failedAudioJournalPublicationCannotMutateOrSaveSqliteState() throws Exception {
        Path blockingParent = temporary.resolve("blocking-file");
        Files.writeString(blockingParent, "leave untouched");
        var journal = new ReaderAudioRecoveryJournal(blockingParent.resolve("recovery"));
        var stored = ReadingProgressDto.builder().bookId("book").anchorId("audio:12:0:0")
                .chapterTitle("Stored title").build();
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenReturn(Optional.of(stored));
        assertThat(service(repository, journal).saveAudioPosition("c", "book",
                new AudioPosition(18, 0, 0), 10)).isFalse();
        assertThat(stored.getAnchorId()).isEqualTo("audio:12:0:0");
        assertThat(stored.getChapterTitle()).isEqualTo("Stored title");
        verify(repository, never()).save(any());
    }

    @Test
    void existingCommittedAudioAnchorAcknowledgesJournalWithoutExtraSqlWrite() throws Exception {
        var journal = new ReaderAudioRecoveryJournal(temporary.resolve("audio"));
        var pos = new AudioPosition(444, 2, 3);
        journal.prepare("c", "book", "audio:100:2:3", pos, 70);
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenReturn(Optional.of(
                ReadingProgressDto.builder().bookId("book").anchorId(pos.serialize()).build()));
        assertThat(service(repository, journal).loadAudioPosition("c", "book")).contains(pos);
        verify(repository, never()).save(any());
        assertThat(journal.load("c", "book")).isEmpty();
    }

    @Test
    void audioRecoveryRevisionPreventsAbaWithSameAnchor() throws Exception {
        var journal = new ReaderAudioRecoveryJournal(temporary.resolve("audio-generation"));
        var old = new AudioPosition(100, 0, 0);
        var newPosition = new AudioPosition(500, 0, 0);
        journal.prepare("c", "book", old.serialize(), newPosition, 60, 2L);
        var reopened = new ReaderAudioRecoveryJournal(temporary.resolve("audio-generation"));
        assertThat(reopened.load("c", "book")).get()
                .extracting(ReaderAudioRecoveryJournal.Pending::expectedRevision).isEqualTo(2L);
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenReturn(Optional.of(
                ReadingProgressDto.builder().bookId("book").revision(7L)
                        .audioAnchorId(old.serialize()).anchorId(old.serialize()).build()));
        assertThat(service(repository, reopened).loadAudioPosition("c", "book")).contains(old);
        verify(repository, never()).save(any());
        assertThat(reopened.load("c", "book")).isPresent();
    }

    @Test
    void audioRecoveryReplaysWhenVersionedSqliteMatches() throws Exception {
        var journal = new ReaderAudioRecoveryJournal(temporary.resolve("audio-replay"));
        var old = new AudioPosition(100, 0, 0);
        var newPosition = new AudioPosition(500, 0, 0);
        journal.prepare("c", "book", old.serialize(), newPosition, 60, 2L);
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenReturn(Optional.of(
                ReadingProgressDto.builder().bookId("book").revision(2L)
                        .audioAnchorId(old.serialize()).anchorId(old.serialize()).build()));
        assertThat(service(repository, journal).loadAudioPosition("c", "book")).contains(newPosition);
        verify(repository).save(argThat(saved -> saved.getRevision().equals(2L)
                && newPosition.serialize().equals(saved.getAudioAnchorId())));
        assertThat(journal.load("c", "book")).isEmpty();
    }

    @Test
    void audioRecoveryAfterDeletionRejectsStaleAbsentGeneration() throws Exception {
        var journal = new ReaderAudioRecoveryJournal(temporary.resolve("audio-deleted"));
        journal.prepare("c", "book", null, new AudioPosition(200, 0, 0), 30, -1L);
        var repository = mock(ReadingProgressRepository.class);
        when(repository.findByBookId("book")).thenReturn(Optional.empty());
        when(repository.revisionWhenAbsent("book")).thenReturn(-7L);
        assertThat(service(repository, journal).loadAudioPosition("c", "book")).isEmpty();
        verify(repository, never()).save(any());
        assertThat(journal.load("c", "book")).isPresent();
    }

    @Test
    void corruptedAudioJournalRevisionMustBeRetainedForDiagnosis() throws Exception {
        var journal = new ReaderAudioRecoveryJournal(temporary.resolve("audio-revision-corrupt"));
        journal.prepare("c", "book", null, new AudioPosition(200, 0, 0), 30, -1L);
        Path path = journal.file("c", "book");
        Files.writeString(path, Files.readString(path).replace("\n-1\n", "\n-7\n"));
        assertThatThrownBy(() -> journal.load("c", "book")).isInstanceOf(IOException.class);
        assertThat(Files.readString(path)).contains("\n-7\n");
    }

    private static NewReaderPersistenceService service(ReadingProgressRepository repository,
                                                       ReaderAudioRecoveryJournal journal) {
        return new NewReaderPersistenceService(repository, mock(BookmarkRepository.class),
                mock(LocalizationService.class), lifecycle(), new LibraryOperationCoordinator(),
                new ReaderPositionRecoveryJournal(Path.of(System.getProperty("java.io.tmpdir"),
                        "mhl-test-text", java.util.UUID.randomUUID().toString())), journal);
    }

    private static CollectionLifecyclePort lifecycle() {
        var lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(
                new com.myhomelibcorp.domain.model.collection.Collection(
                        "c", "Test", null, null, 0, null, null, null, null));
        return lifecycle;
    }
}
