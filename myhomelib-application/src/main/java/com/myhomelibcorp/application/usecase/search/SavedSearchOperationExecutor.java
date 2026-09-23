package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Keeps saved-search and smart-collection operations bound to one active collection.
 * Reads hold a shared QUERY lifecycle barrier; mutations acquire EDIT before opening
 * the collection transaction so a SWITCH/RESTORE/DELETE cannot split a multi-step change.
 */
@Component
public class SavedSearchOperationExecutor {

    private final LibraryOperationCoordinator operationCoordinator;
    private final TransactionTemplate transactionTemplate;

    public SavedSearchOperationExecutor(
            LibraryOperationCoordinator operationCoordinator,
            @Qualifier("collectionTransactionTemplate") TransactionTemplate transactionTemplate) {
        this.operationCoordinator = Objects.requireNonNull(operationCoordinator, "operationCoordinator");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    }

    public <T> T query(Supplier<T> query) {
        Objects.requireNonNull(query, "query");
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            T result = query.get();
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    public <T> T mutate(Supplier<T> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.EDIT);
        try {
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

    public void mutate(Runnable mutation) {
        Objects.requireNonNull(mutation, "mutation");
        mutate(() -> {
            mutation.run();
            return null;
        });
    }
}
