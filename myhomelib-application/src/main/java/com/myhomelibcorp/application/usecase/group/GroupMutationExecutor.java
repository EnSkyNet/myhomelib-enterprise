package com.myhomelibcorp.application.usecase.group;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Serializes collection-bound group mutations against collection lifecycle changes and
 * opens the collection transaction only after the lifecycle barrier is held.
 */
@Component
public class GroupMutationExecutor {

    private final LibraryOperationCoordinator operationCoordinator;
    private final TransactionTemplate transactionTemplate;
    private final CollectionLifecyclePort collectionLifecyclePort;

    public GroupMutationExecutor(
            LibraryOperationCoordinator operationCoordinator,
            @Qualifier("collectionTransactionTemplate") TransactionTemplate transactionTemplate,
            CollectionLifecyclePort collectionLifecyclePort) {
        this.operationCoordinator = Objects.requireNonNull(operationCoordinator, "operationCoordinator");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.collectionLifecyclePort = Objects.requireNonNull(collectionLifecyclePort, "collectionLifecyclePort");
    }

    public <T> T execute(Supplier<T> mutation) {
        return execute(null, mutation);
    }

    public <T> T execute(String expectedCollectionId, Supplier<T> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.EDIT);
        try {
            assertExpectedCollection(expectedCollectionId);
            T result = transactionTemplate.execute(status -> mutation.get());
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    public void execute(Runnable mutation) {
        execute(null, mutation);
    }

    public void execute(String expectedCollectionId, Runnable mutation) {
        Objects.requireNonNull(mutation, "mutation");
        execute(expectedCollectionId, () -> {
            mutation.run();
            return null;
        });
    }

    private void assertExpectedCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) return;
        Collection active = collectionLifecyclePort.getCurrentCollection();
        if (active == null || active.getId() == null
                || !expectedCollectionId.trim().equals(active.getId().trim())) {
            throw new IllegalStateException("Активна колекція змінилася до операції з групою");
        }
    }
}
