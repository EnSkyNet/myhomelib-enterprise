package com.myhomelibcorp.application.readinglist;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

@Component
public final class ReadingListMutationExecutor {
    private final LibraryOperationCoordinator operationCoordinator;
    private final TransactionTemplate transactionTemplate;
    private final CollectionLifecyclePort collectionLifecyclePort;

    public ReadingListMutationExecutor(LibraryOperationCoordinator operationCoordinator,
            @Qualifier("collectionTransactionTemplate") TransactionTemplate transactionTemplate,
            CollectionLifecyclePort collectionLifecyclePort) {
        this.operationCoordinator = Objects.requireNonNull(operationCoordinator);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
        this.collectionLifecyclePort = Objects.requireNonNull(collectionLifecyclePort);
    }

    public <T> T execute(String expectedCollectionId, Supplier<T> work) {
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.EDIT);
        try {
            assertCollection(expectedCollectionId);
            T result = transactionTemplate.execute(status -> work.get());
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally { lease.close(); }
    }

    public void execute(String expectedCollectionId, Runnable work) {
        execute(expectedCollectionId, () -> { work.run(); return null; });
    }

    private void assertCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) return;
        Collection active = collectionLifecyclePort.getCurrentCollection();
        if (active == null || active.getId() == null || !expectedCollectionId.trim().equals(active.getId().trim())) {
            throw new IllegalStateException("Активна колекція змінилася до операції зі списком читання");
        }
    }
}
