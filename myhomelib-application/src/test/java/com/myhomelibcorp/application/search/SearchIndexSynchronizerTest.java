package com.myhomelibcorp.application.search;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.Mockito.*;

class SearchIndexSynchronizerTest {

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void immediateSyncReindexesCurrentRowsAndDeletesMissingRowsAtomically() {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexLifecycle lifecycle = mock(SearchIndexLifecycle.class);
        SearchIndexSynchronizer synchronizer = new SearchIndexSynchronizer(repository, indexer, lifecycle);
        BookId presentId = BookId.generate();
        BookId missingId = BookId.generate();
        Book present = mock(Book.class);
        when(present.getId()).thenReturn(presentId);
        when(present.isDeleted()).thenReturn(false);
        when(repository.findByIds(List.of(presentId, missingId))).thenReturn(List.of(present));

        synchronizer.synchronizeNow(List.of(presentId, missingId, presentId));

        verify(indexer).beginAtomicUpdate();
        verify(indexer).indexBook(present);
        verify(indexer).deleteBook(missingId);
        verify(indexer).commit();
        verify(indexer, never()).rollbackAtomicUpdate();
        InOrder freshness = inOrder(lifecycle, indexer);
        freshness.verify(lifecycle).markCurrentIndexDirty();
        freshness.verify(indexer).beginAtomicUpdate();
        freshness.verify(indexer).commit();
        freshness.verify(lifecycle).markCurrentIndexSynchronized();
    }

    @Test
    void failureRollsBackAtomicLuceneMutation() {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexLifecycle lifecycle = mock(SearchIndexLifecycle.class);
        SearchIndexSynchronizer synchronizer = new SearchIndexSynchronizer(repository, indexer, lifecycle);
        BookId id = BookId.generate();
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(id);
        when(repository.findByIds(List.of(id))).thenReturn(List.of(book));
        doThrow(new IllegalStateException("index failure")).when(indexer).indexBook(book);

        try {
            synchronizer.synchronizeNow(List.of(id));
        } catch (IllegalStateException expected) {
            // expected
        }

        verify(indexer).rollbackAtomicUpdate();
        verify(indexer, never()).commit();
        verify(lifecycle).markCurrentIndexDirty();
        verify(lifecycle, never()).markCurrentIndexSynchronized();
    }

    @Test
    void transactionalCallDoesNotTouchLuceneBeforeCommit() {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexLifecycle lifecycle = mock(SearchIndexLifecycle.class);
        SearchIndexSynchronizer synchronizer = new SearchIndexSynchronizer(repository, indexer, lifecycle);
        BookId id = BookId.generate();
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(id);
        when(repository.findByIds(List.of(id))).thenReturn(List.of(book));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        synchronizer.synchronizeAfterCommit(List.of(id));

        verifyNoInteractions(indexer);
        verify(lifecycle).markCurrentIndexDirty();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());

        verify(lifecycle, times(2)).markCurrentIndexDirty();
        verify(indexer).beginAtomicUpdate();
        verify(indexer).indexBook(book);
        verify(indexer).commit();
        verify(lifecycle).markCurrentIndexSynchronized();
    }

    @Test
    void transactionalRollbackLeavesPersistentDirtyIntentAndNeverTouchesLucene() {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexLifecycle lifecycle = mock(SearchIndexLifecycle.class);
        SearchIndexSynchronizer synchronizer = new SearchIndexSynchronizer(repository, indexer, lifecycle);
        BookId id = BookId.generate();

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        synchronizer.synchronizeAfterCommit(List.of(id));

        // Simulate transaction rollback: Spring never invokes afterCommit(). Dirty is conservative and
        // intentionally survives so restart cannot silently trust an index after an uncertain DB outcome.
        verify(lifecycle).markCurrentIndexDirty();
        verifyNoInteractions(indexer);
    }

    @Test
    void safeSyncFallsBackToFullRebuildAfterSelectiveFailure() {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexLifecycle lifecycle = mock(SearchIndexLifecycle.class);
        SearchIndexSynchronizer synchronizer = new SearchIndexSynchronizer(repository, indexer, lifecycle);
        BookId id = BookId.generate();
        when(repository.findByIds(List.of(id))).thenThrow(new IllegalStateException("query failure"));

        boolean recovered = synchronizer.synchronizeSafelyNow(List.of(id));

        verify(indexer).beginAtomicUpdate();
        verify(indexer).rollbackAtomicUpdate();
        verify(indexer).rebuildIndex();
        verify(lifecycle).markCurrentIndexDirty();
        verify(lifecycle).markCurrentIndexSynchronized();
        org.assertj.core.api.Assertions.assertThat(recovered).isTrue();
    }

    @Test
    void failedSelectiveAndFullRebuildLeavesFreshnessDirty() {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexLifecycle lifecycle = mock(SearchIndexLifecycle.class);
        SearchIndexSynchronizer synchronizer = new SearchIndexSynchronizer(repository, indexer, lifecycle);
        BookId id = BookId.generate();
        when(repository.findByIds(List.of(id))).thenThrow(new IllegalStateException("query failure"));
        doThrow(new IllegalStateException("rebuild failure")).when(indexer).rebuildIndex();

        boolean recovered = synchronizer.synchronizeSafelyNow(List.of(id));

        org.assertj.core.api.Assertions.assertThat(recovered).isFalse();
        verify(lifecycle).markCurrentIndexDirty();
        verify(lifecycle, never()).markCurrentIndexSynchronized();
    }

    @Test
    void luceneCommitFailureRollsBackAndLeavesFreshnessDirtyWhenFallbackRebuildAlsoFails() {
        BookQueryRepository repository = mock(BookQueryRepository.class);
        SearchIndexer indexer = mock(SearchIndexer.class);
        SearchIndexLifecycle lifecycle = mock(SearchIndexLifecycle.class);
        SearchIndexSynchronizer synchronizer = new SearchIndexSynchronizer(repository, indexer, lifecycle);
        BookId id = BookId.generate();
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(id);
        when(repository.findByIds(List.of(id))).thenReturn(List.of(book));
        doThrow(new IllegalStateException("commit failure")).when(indexer).commit();
        doThrow(new IllegalStateException("rebuild failure")).when(indexer).rebuildIndex();

        boolean recovered = synchronizer.synchronizeSafelyNow(List.of(id));

        org.assertj.core.api.Assertions.assertThat(recovered).isFalse();
        verify(indexer).beginAtomicUpdate();
        verify(indexer).indexBook(book);
        verify(indexer).commit();
        verify(indexer).rollbackAtomicUpdate();
        verify(indexer).rebuildIndex();
        verify(lifecycle, atLeastOnce()).markCurrentIndexDirty();
        verify(lifecycle, never()).markCurrentIndexSynchronized();
    }
}
