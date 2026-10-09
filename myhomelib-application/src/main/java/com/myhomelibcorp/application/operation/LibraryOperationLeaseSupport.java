package com.myhomelibcorp.application.operation;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Centralizes the lifecycle bookkeeping for short synchronous operations that participate in the
 * shared detached library-operation barrier.
 *
 * <p>A {@code null} coordinator is supported for legacy/unit-test construction paths where operation
 * coordination is intentionally absent.</p>
 */
public final class LibraryOperationLeaseSupport {
    private LibraryOperationLeaseSupport() { }

    public static <T> T withSharedLease(
            LibraryOperationCoordinator coordinator,
            LibraryOperationType operation,
            Supplier<T> action) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(action, "action");
        if (coordinator == null) return action.get();

        var lease = coordinator.acquireDetachedShared(operation);
        try {
            T result = action.get();
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    public static <T> T withSharedTransaction(
            LibraryOperationCoordinator coordinator,
            LibraryOperationType operation,
            TransactionTemplate transactionTemplate,
            Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return withSharedLease(coordinator, operation, () -> transactionTemplate == null
                ? action.get()
                : transactionTemplate.execute(status -> action.get()));
    }

    public static void withSharedTransaction(
            LibraryOperationCoordinator coordinator,
            LibraryOperationType operation,
            TransactionTemplate transactionTemplate,
            Runnable action) {
        Objects.requireNonNull(action, "action");
        withSharedTransaction(coordinator, operation, transactionTemplate, () -> {
            action.run();
            return null;
        });
    }

    public static void withSharedLease(
            LibraryOperationCoordinator coordinator,
            LibraryOperationType operation,
            Runnable action) {
        Objects.requireNonNull(action, "action");
        withSharedLease(coordinator, operation, () -> {
            action.run();
            return null;
        });
    }
}
