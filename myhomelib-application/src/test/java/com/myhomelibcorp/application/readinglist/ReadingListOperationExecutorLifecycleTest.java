package com.myhomelibcorp.application.readinglist;

import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ReadingListOperationExecutorLifecycleTest {

    @Test
    void readingListQueryIsRejectedBeforeWorkWhileSwitchOwnsLifecycle() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        ReadingListQueryExecutor executor = new ReadingListQueryExecutor(coordinator);
        AtomicBoolean invoked = new AtomicBoolean(false);

        try (var ignored = coordinator.acquireDetached(LibraryOperationType.SWITCH)) {
            assertThrows(LibraryOperationConflictException.class, () -> executor.execute(() -> {
                invoked.set(true);
                return 1;
            }));
        }

        assertFalse(invoked.get());
    }

    @Test
    void staleCollectionIsRejectedBeforeOpeningReadingListMutationTransaction() {
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        Collection active = mock(Collection.class);
        when(active.getId()).thenReturn("collection-b");
        when(lifecycle.getCurrentCollection()).thenReturn(active);
        ReadingListMutationExecutor executor = new ReadingListMutationExecutor(coordinator, transaction, lifecycle);
        AtomicBoolean invoked = new AtomicBoolean(false);

        assertThrows(IllegalStateException.class,
                () -> executor.execute("collection-a", () -> invoked.set(true)));

        assertFalse(invoked.get());
        verifyNoInteractions(transaction);
    }
}
