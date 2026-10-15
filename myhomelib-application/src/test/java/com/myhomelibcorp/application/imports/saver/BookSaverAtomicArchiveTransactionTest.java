package com.myhomelibcorp.application.imports.saver;

import com.myhomelibcorp.application.imports.duplicate.DuplicateDetector;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookSaverAtomicArchiveTransactionTest {
    @Test
    void lateFailureRollsBackAndInvalidatesDuplicateCache() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        DuplicateDetector duplicates = mock(DuplicateDetector.class);
        BookSaver saver = saver(manager, duplicates);
        assertThrows(IllegalStateException.class, () -> saver.inAtomicArchiveImport(() -> {
            throw new IllegalStateException("late archive failure");
        }));
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
        verify(duplicates).clearCache();
    }

    @Test
    void completedArchiveCommitsWithoutDroppingDuplicateCache() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        DuplicateDetector duplicates = mock(DuplicateDetector.class);
        BookSaver saver = saver(manager, duplicates);
        assertEquals(42, saver.inAtomicArchiveImport(() -> 42));
        verify(manager).commit(any());
        verify(manager, never()).rollback(any());
        verify(duplicates, never()).clearCache();
    }

    private static BookSaver saver(PlatformTransactionManager manager, DuplicateDetector duplicates) {
        return new BookSaver(mock(BookCommandRepository.class), mock(BookQueryRepository.class),
                mock(ApplicationEventPublisher.class), duplicates, mock(SearchIndexer.class),
                new TransactionTemplate(manager), new LibraryOperationCoordinator(),
                mock(CollectionLifecyclePort.class));
    }
}
