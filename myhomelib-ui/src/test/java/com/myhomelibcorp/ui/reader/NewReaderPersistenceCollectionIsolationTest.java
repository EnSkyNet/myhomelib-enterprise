package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NewReaderPersistenceCollectionIsolationTest {

    @Test
    void staleReaderCannotWriteProgressIntoNewActiveCollection() {
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        BookmarkRepository bookmarks = mock(BookmarkRepository.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(collection("B"));
        NewReaderPersistenceService service = new NewReaderPersistenceService(
                progress, bookmarks, mock(LocalizationService.class), lifecycle, new LibraryOperationCoordinator());

        boolean saved = service.savePosition("A", "book-1", new ReaderPosition(0, 100, 1, 0), 1_000L);

        assertThat(saved).isFalse();
        verifyNoInteractions(progress);
    }

    @Test
    void staleReaderCannotSaveOrDeleteBookmarkInNewActiveCollection() {
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        BookmarkRepository bookmarks = mock(BookmarkRepository.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        LocalizationService i18n = mock(LocalizationService.class);
        when(lifecycle.getCurrentCollection()).thenReturn(collection("B"));
        when(i18n.format(anyString(), any())).thenReturn("reader state failed");
        NewReaderPersistenceService service = new NewReaderPersistenceService(
                progress, bookmarks, i18n, lifecycle, new LibraryOperationCoordinator());

        assertThatThrownBy(() -> service.saveBookmark(
                "A", "book-1", ReaderPosition.start(), 100L, "mark", ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.deleteBookmark("A", "book-1", "bookmark-1"))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(bookmarks);
    }

    private static Collection collection(String id) {
        return new Collection(id, id, null, null, 0, null, null, null, null);
    }
}
