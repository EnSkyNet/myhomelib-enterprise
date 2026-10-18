package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewReaderPersistenceServiceFailureTest {

    private final ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
    private final BookmarkRepository bookmarks = mock(BookmarkRepository.class);
    private final LocalizationService i18n = mock(LocalizationService.class);
    private final NewReaderPersistenceService service = newPersistence(progress, bookmarks, i18n);

    NewReaderPersistenceServiceFailureTest() {
        when(i18n.text("ui.reader.persistence.position_load_error"))
                .thenReturn("Не вдалося завантажити позицію читання");
        when(i18n.text("ui.reader.persistence.bookmarks_load_error"))
                .thenReturn("Не вдалося завантажити закладки");
        when(i18n.text("ui.reader.persistence.bookmark_count_error"))
                .thenReturn("Не вдалося підрахувати закладки");
    }

    @Test
    void positionReadFailureIsNotReportedAsNoSavedPosition() {
        when(progress.findByBookId("book-1")).thenThrow(new IllegalStateException("database damaged"));

        assertThatThrownBy(() -> service.loadPosition("book-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("позицію читання");
    }

    @Test
    void bookmarkReadFailureIsNotReportedAsEmptyList() {
        when(bookmarks.findByBookId("book-1")).thenThrow(new IllegalStateException("database locked"));

        assertThatThrownBy(() -> service.loadBookmarks("book-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("закладки");
    }

    @Test
    void bookmarkCountFailureIsNotReportedAsZero() {
        when(bookmarks.countByBookId(anyString())).thenThrow(new IllegalStateException("database damaged"));

        assertThatThrownBy(() -> service.getBookmarkCount("book-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("підрахувати закладки");
    }

    @Test
    void autosaveFailureRemainsRetryableInsteadOfThrowingFromScheduler() {
        when(progress.findByBookId("book-1")).thenThrow(new IllegalStateException("database locked"));

        boolean saved = service.savePosition("book-1", ReaderPosition.start(), 1000L);

        assertThat(saved).isFalse();
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
