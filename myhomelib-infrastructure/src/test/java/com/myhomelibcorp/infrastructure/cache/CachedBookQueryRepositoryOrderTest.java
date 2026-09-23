package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachedBookQueryRepositoryOrderTest {

    @Test
    void findByIdsPreservesRequestedOrderAcrossMixedCacheHitsAndMisses() {
        CollectionManager manager = mock(CollectionManager.class);
        Collection collection = mock(Collection.class);
        when(collection.getId()).thenReturn("collection-a");
        when(manager.getCurrentCollection()).thenReturn(collection);

        BookQueryRepository delegate = mock(BookQueryRepository.class);
        BookCache cache = new BookCache(manager);
        CachedBookQueryRepository repository = new CachedBookQueryRepository(delegate, cache);

        BookId firstId = BookId.fromString("00000000-0000-0000-0000-000000000001");
        BookId secondId = BookId.fromString("00000000-0000-0000-0000-000000000002");
        BookId thirdId = BookId.fromString("00000000-0000-0000-0000-000000000003");
        Book first = mock(Book.class);
        Book second = mock(Book.class);
        Book third = mock(Book.class);
        when(first.getId()).thenReturn(firstId);
        when(second.getId()).thenReturn(secondId);
        when(third.getId()).thenReturn(thirdId);

        // Only the middle item is warm. The delegate is intentionally allowed to return misses
        // in a different order because SQL IN clauses do not guarantee input ordering.
        cache.put(secondId, second);
        when(delegate.findByIds(List.of(firstId, thirdId))).thenReturn(List.of(third, first));

        assertThat(repository.findByIds(List.of(firstId, secondId, thirdId)))
                .containsExactly(first, second, third);
        verify(delegate).findByIds(List.of(firstId, thirdId));
    }

    @Test
    void findByIdsDoesNotSendDuplicateMissesToDelegateAndKeepsRequestedDuplicates() {
        CollectionManager manager = mock(CollectionManager.class);
        Collection collection = mock(Collection.class);
        when(collection.getId()).thenReturn("collection-a");
        when(manager.getCurrentCollection()).thenReturn(collection);

        BookQueryRepository delegate = mock(BookQueryRepository.class);
        BookCache cache = new BookCache(manager);
        CachedBookQueryRepository repository = new CachedBookQueryRepository(delegate, cache);

        BookId id = BookId.fromString("00000000-0000-0000-0000-000000000011");
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(id);
        when(delegate.findByIds(List.of(id))).thenReturn(List.of(book));

        assertThat(repository.findByIds(List.of(id, id))).containsExactly(book, book);
        verify(delegate).findByIds(List.of(id));
    }
}
