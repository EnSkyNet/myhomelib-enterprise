package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class BookSearchIndexRefreshServiceTest {
    @Test
    void refreshesDistinctExistingBooksAndCommitsOnce() {
        BookQueryRepository books = mock(BookQueryRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        Book a = Book.builder().id(BookId.fromString("11111111-1111-1111-1111-111111111111")).title("A").build();
        Book b = Book.builder().id(BookId.fromString("22222222-2222-2222-2222-222222222222")).title("B").build();
        when(books.findByIds(List.of(a.getId(), b.getId()))).thenReturn(List.of(a, b));
        BookSearchIndexRefreshService service = new BookSearchIndexRefreshService(books, indexer);

        service.refreshBooks(List.of(a.getId().asString(), b.getId().asString(), a.getId().asString()));

        verify(indexer).indexAll(List.of(a, b));
        verify(indexer, times(1)).commit();
    }

    @Test
    void indexFailureIsBestEffortAndDoesNotEscapeMutationBoundary() {
        BookQueryRepository books = mock(BookQueryRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        BookId id = BookId.fromString("33333333-3333-3333-3333-333333333333");
        Book book = Book.builder().id(id).title("C").build();
        when(books.findByIds(List.of(id))).thenReturn(List.of(book));
        doThrow(new IllegalStateException("index unavailable")).when(indexer).indexAll(List.of(book));
        BookSearchIndexRefreshService service = new BookSearchIndexRefreshService(books, indexer);

        assertThatCode(() -> service.refreshBook(id.asString())).doesNotThrowAnyException();
        verify(indexer, never()).commit();
    }
}
