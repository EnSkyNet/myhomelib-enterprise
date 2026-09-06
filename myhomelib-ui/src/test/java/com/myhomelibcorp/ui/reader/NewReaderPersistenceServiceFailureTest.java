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
    private final NewReaderPersistenceService service = new NewReaderPersistenceService(progress, bookmarks, i18n);

    NewReaderPersistenceServiceFailureTest() {
        when(i18n.format("ui.reader.persistence.position_load_error", "book-1"))
                .thenReturn("Не вдалося завантажити позицію читання для book-1");
        when(i18n.format("ui.reader.persistence.bookmarks_load_error", "book-1"))
                .thenReturn("Не вдалося завантажити закладки для book-1");
        when(i18n.format("ui.reader.persistence.bookmark_count_error", "book-1"))
                .thenReturn("Не вдалося підрахувати закладки для book-1");
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
}
