package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SavedSearchOperationExecutorTest {

    @Test
    void queryDoesNotTouchRepositoryWorkWhileSwitchOwnsLifecycle() {
        var coordinator = new LibraryOperationCoordinator();
        var transaction = mock(TransactionTemplate.class);
        var executor = new SavedSearchOperationExecutor(coordinator, transaction);
        var invoked = new AtomicBoolean(false);

        try (var ignored = coordinator.acquireDetached(LibraryOperationType.SWITCH)) {
            assertThrows(LibraryOperationConflictException.class,
                    () -> executor.query(() -> {
                        invoked.set(true);
                        return "unexpected";
                    }));
        }

        assertFalse(invoked.get());
        verifyNoInteractions(transaction);
    }

    @Test
    void mutationDoesNotOpenTransactionWhileSwitchOwnsLifecycle() {
        var coordinator = new LibraryOperationCoordinator();
        var transaction = mock(TransactionTemplate.class);
        var executor = new SavedSearchOperationExecutor(coordinator, transaction);
        var invoked = new AtomicBoolean(false);

        try (var ignored = coordinator.acquireDetached(LibraryOperationType.SWITCH)) {
            assertThrows(LibraryOperationConflictException.class,
                    () -> executor.mutate(() -> {
                        invoked.set(true);
                        return "unexpected";
                    }));
        }

        assertFalse(invoked.get());
        verifyNoInteractions(transaction);
    }
}
