package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookmarkRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.domain.model.bookmark.Bookmark;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.audio.AudioPosition;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReaderBookmarkProjectionTest {

    @Test
    void textBookmarkProjectionPreservesNavigationAndDisplayFields() {
        BookmarkRepository bookmarks = mock(BookmarkRepository.class);
        when(bookmarks.save(any(Bookmark.class))).thenAnswer(invocation -> invocation.getArgument(0));
        NewReaderPersistenceService service = newPersistence(bookmarks);
        ReaderPosition position = new ReaderPosition(2, 321L, 7, 4);

        ReaderBookmarkItem item = service.saveBookmarkItem(
                "c1", "book-1", position, 1_000L, "Chapter", "context");

        assertThat(item.id()).isNotBlank();
        assertThat(item.bookId()).isEqualTo("book-1");
        assertThat(item.paragraphId()).startsWith("rp:");
        assertThat(item.chapterTitle()).isEqualTo("Chapter");
        assertThat(item.context()).isEqualTo("context");
        assertThat(item.createdAt()).isNotNull();
        assertThat(service.bookmarkToPosition(item, 1_000L)).isEqualTo(position);
    }

    @Test
    void loadProjectionPreservesPersistedBookmarkFields() {
        BookmarkRepository bookmarks = mock(BookmarkRepository.class);
        LocalDateTime created = LocalDateTime.of(2026, 9, 28, 12, 0);
        Bookmark persisted = Bookmark.builder()
                .id("bookmark-1")
                .bookId("book-1")
                .paragraphId("p7")
                .charOffset(3)
                .position(25.0)
                .chapterTitle("Legacy")
                .context("ctx")
                .createdAt(created)
                .build();
        when(bookmarks.findByBookId("book-1")).thenReturn(List.of(persisted));
        NewReaderPersistenceService service = newPersistence(bookmarks);

        assertThat(service.loadBookmarkItems("c1", "book-1"))
                .containsExactly(new ReaderBookmarkItem(
                        "bookmark-1", "book-1", "p7", 3, 25.0, "Legacy", "ctx", created));
    }

    @Test
    void audioBookmarkProjectionPreservesExactAudioAnchor() {
        BookmarkRepository bookmarks = mock(BookmarkRepository.class);
        when(bookmarks.save(any(Bookmark.class))).thenAnswer(invocation -> invocation.getArgument(0));
        NewReaderPersistenceService service = newPersistence(bookmarks);
        AudioPosition position = new AudioPosition(5_000L, 1, 2);

        ReaderBookmarkItem item = service.saveAudioBookmarkItem(
                "c1", "book-1", position, 40.0, "Spot", "");

        assertThat(item.paragraphId()).isEqualTo("audio:5000:1:2");
        assertThat(service.audioBookmarkToPosition(item)).contains(position);
    }

    private static NewReaderPersistenceService newPersistence(BookmarkRepository bookmarks) {
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(
                new Collection("c1", "Test", null, null, 0, null, null, null, null));
        return new NewReaderPersistenceService(
                mock(ReadingProgressRepository.class),
                bookmarks,
                mock(LocalizationService.class),
                lifecycle,
                new LibraryOperationCoordinator());
    }
}
