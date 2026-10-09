package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.reader.audio.AudioPosition;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NewReaderAudiobookPersistenceTest {
    @Test void savesExactAudioAnchorAndPercentIntoSharedProgressRepository() {
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        BookmarkRepository bookmarks = mock(BookmarkRepository.class);
        LocalizationService i18n = mock(LocalizationService.class);
        when(progress.findByBookId("book-1")).thenReturn(Optional.empty());
        NewReaderPersistenceService service = newPersistence(progress, bookmarks, i18n);

        assertThat(service.saveAudioPosition("book-1", new AudioPosition(12_345L, 2, 4), 67.5)).isTrue();
        ArgumentCaptor<ReadingProgressDto> captor = ArgumentCaptor.forClass(ReadingProgressDto.class);
        verify(progress).save(captor.capture());
        assertThat(captor.getValue().getAnchorId()).isEqualTo("audio:12345:2:4");
        assertThat(captor.getValue().getParagraphId()).isEqualTo("audio-track:2");
        assertThat(captor.getValue().getPercent()).isEqualTo(67.5);
    }

    @Test void loadsOnlyAudioAnchorsWithoutTreatingTextPositionsAsAudio() {
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        BookmarkRepository bookmarks = mock(BookmarkRepository.class);
        LocalizationService i18n = mock(LocalizationService.class);
        NewReaderPersistenceService service = newPersistence(progress, bookmarks, i18n);

        when(progress.findByBookId("audio")).thenReturn(Optional.of(ReadingProgressDto.builder().bookId("audio").anchorId("audio:99:1:3").build()));
        when(progress.findByBookId("text")).thenReturn(Optional.of(ReadingProgressDto.builder().bookId("text").anchorId("0:99:1:3").build()));
        assertThat(service.loadAudioPosition("audio")).contains(new AudioPosition(99, 1, 3));
        assertThat(service.loadAudioPosition("text")).isEmpty();
    }

    @Test void audiobookBookmarkKeepsExactTrackAndChapterAnchor() {
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        BookmarkRepository bookmarks = mock(BookmarkRepository.class);
        LocalizationService i18n = mock(LocalizationService.class);
        when(bookmarks.save(any(Bookmark.class))).thenAnswer(invocation -> invocation.getArgument(0));
        NewReaderPersistenceService service = newPersistence(progress, bookmarks, i18n);

        Bookmark saved = service.saveAudioBookmark("book-1", new AudioPosition(5000, 1, 2), 40.0, "Spot", "");
        assertThat(saved.getParagraphId()).isEqualTo("audio:5000:1:2");
        assertThat(service.audioBookmarkToPosition(saved)).contains(new AudioPosition(5000, 1, 2));
    }


    @Test void nonFiniteAudioPercentIsSanitizedBeforePersistence() {
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        BookmarkRepository bookmarks = mock(BookmarkRepository.class);
        LocalizationService i18n = mock(LocalizationService.class);
        when(progress.findByBookId("book-nan")).thenReturn(Optional.empty());
        NewReaderPersistenceService service = newPersistence(progress, bookmarks, i18n);

        assertThat(service.saveAudioPosition("book-nan", new AudioPosition(100L, 0, 0), Double.NaN)).isTrue();
        ArgumentCaptor<ReadingProgressDto> captor = ArgumentCaptor.forClass(ReadingProgressDto.class);
        verify(progress).save(captor.capture());
        assertThat(captor.getValue().getPercent()).isZero();
    }

    private static NewReaderPersistenceService newPersistence(
            ReadingProgressRepository progress, BookmarkRepository bookmarks, LocalizationService i18n) {
        var lifecycle = org.mockito.Mockito.mock(
                com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort.class);
        org.mockito.Mockito.when(lifecycle.getCurrentCollection()).thenReturn(
                new com.myhomelibcorp.domain.model.collection.Collection(
                        "c1", "Test", null, null, 0, null, null, null, null));
        return new NewReaderPersistenceService(progress, bookmarks, i18n, lifecycle,
                new com.myhomelibcorp.application.operation.LibraryOperationCoordinator());
    }
}
