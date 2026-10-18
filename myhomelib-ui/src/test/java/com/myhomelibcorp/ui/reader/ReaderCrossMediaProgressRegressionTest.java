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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The book's SQLite progress row is shared by audio and text, not the in-memory text cache. */
class ReaderCrossMediaProgressRegressionTest {
    @TempDir Path temp;

    @Test
    void textAnchorSurvivesAudioPlaybackWithoutRedundantTextResave() {
        var row = new AtomicReference<ReadingProgressDto>();
        var repo = repository(row);
        var service = service(repo);
        var text = new ReaderPosition(1, 78, 3, 2);
        var audio = new AudioPosition(1200, 1, 0);
        assertThat(service.savePosition("c", "book", text, 100)).isTrue();
        assertThat(service.saveAudioPosition("c", "book", audio, 30)).isTrue();
        assertThat(row.get().getAnchorId()).isEqualTo(audio.serialize());
        // V71 keeps the text and audio anchors independently. Re-saving the same
        // text position must not erase the last-active audio anchor or write again.
        assertThat(row.get().getTextAnchorId()).isEqualTo(text.serialize());
        assertThat(row.get().getAudioAnchorId()).isEqualTo(audio.serialize());
        assertThat(service.savePosition("c", "book", text, 100)).isTrue();
        assertThat(row.get().getAnchorId()).isEqualTo(audio.serialize());
        assertThat(service.loadPosition("c", "book")).contains(text);
        assertThat(service.loadAudioPosition("c", "book")).contains(audio);
        verify(repo, times(2)).save(any(ReadingProgressDto.class));
    }

    @Test
    void selectingCommittedPositionReplacesObsoleteFailedRecoveryRatherThanReplayingIt() throws Exception {
        var original = new ReaderPosition(0, 10, 0, 0);
        var stale = new ReaderPosition(0, 45, 0, 0);
        var journal = new ReaderPositionRecoveryJournal(temp.resolve("text"));
        var row = new AtomicReference<>(ReadingProgressDto.builder().bookId("book")
                .anchorId(original.serialize()).build());
        journal.prepare("c", "book", original.serialize(), stale, 100);
        var repo = repository(row);
        var service = service(repo, journal);
        assertThat(service.savePosition("c", "book", original, 100)).isTrue();
        assertThat(journal.load("c", "book")).isEmpty();
        assertThat(service.loadPosition("c", "book")).contains(original);
        verify(repo, times(1)).save(any(ReadingProgressDto.class));
    }

    @Test
    void audiobookAnchorDoesNotMasqueradeAsTextStartPosition() {
        var row = new AtomicReference<>(ReadingProgressDto.builder()
                .bookId("book").anchorId("audio:125:0:0").build());
        var repo = repository(row);
        assertThat(service(repo).loadPosition("c", "book")).isEmpty();
        assertThat(service(repo).loadAudioPosition("c", "book"))
                .contains(new AudioPosition(125, 0, 0));
        verify(repo, never()).save(any());
    }

    @Test
    void replayFailureNeverMutatesRepositoryOwnedTextDto() throws Exception {
        var before = ReadingProgressDto.builder().bookId("book").anchorId("0:10:0:0")
                .chapterTitle("committed title").build();
        var row = new AtomicReference<>(before);
        var repo = repository(row);
        var fail = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (fail.get()) throw new IllegalStateException("SQLite locked");
            row.set(invocation.getArgument(0));
            return null;
        }).when(repo).save(any());
        var journal = new ReaderPositionRecoveryJournal(temp.resolve("text"));
        journal.prepare("c", "book", before.getAnchorId(), new ReaderPosition(0, 55, 0, 0), 100);
        var service = service(repo, journal);
        try {
            service.loadPosition("c", "book");
        } catch (IllegalStateException expected) {
            // Loading correctly reports SQLite failure rather than swallowing it.
        }
        assertThat(before.getAnchorId()).isEqualTo("0:10:0:0");
        assertThat(before.getChapterTitle()).isEqualTo("committed title");
        assertThat(journal.load("c", "book")).isPresent();
        fail.set(false);
        assertThat(service.loadPosition("c", "book")).contains(new ReaderPosition(0, 55, 0, 0));
    }

    private static ReadingProgressRepository repository(AtomicReference<ReadingProgressDto> row) {
        var repo = mock(ReadingProgressRepository.class);
        when(repo.findByBookId("book")).thenAnswer(invocation -> Optional.ofNullable(row.get()));
        doAnswer(invocation -> { row.set(invocation.getArgument(0)); return null; })
                .when(repo).save(any(ReadingProgressDto.class));
        return repo;
    }

    private NewReaderPersistenceService service(ReadingProgressRepository repo) {
        return service(repo, new ReaderPositionRecoveryJournal(temp.resolve("text")));
    }

    private NewReaderPersistenceService service(ReadingProgressRepository repo, ReaderPositionRecoveryJournal journal) {
        var lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(
                new Collection("c", "Test", null, null, 0, null, null, null, null));
        return new NewReaderPersistenceService(repo, mock(BookmarkRepository.class),
                mock(LocalizationService.class), lifecycle, new LibraryOperationCoordinator(),
                journal, new ReaderAudioRecoveryJournal(temp.resolve("audio")));
    }
}
