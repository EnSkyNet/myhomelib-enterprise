package com.myhomelibcorp.application.operation;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;

/**
 * Serializes catalogue-changing and maintenance operations.
 *
 * <p>Only explicit composition is re-entrant: CREATE/UPDATE/IMPORT may call IMPORT internally.
 * Async callers use a detached lease so the lock remains held after the initiating UI thread returns.</p>
 */
@Service
public final class LibraryOperationCoordinator {

    private final Object monitor = new Object();
    private ActiveSession active;
    private final ThreadLocal<LocalSession> local = new ThreadLocal<>();

    public Lease acquire(LibraryOperationType operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (monitor) {
            LocalSession current = local.get();
            if (active == null) {
                UUID sessionId = UUID.randomUUID();
                active = new ActiveSession(sessionId, operation, 1);
                LocalSession created = new LocalSession(sessionId);
                created.stack.push(operation);
                local.set(created);
                return new Lease(this, sessionId, operation, false);
            }

            if (current != null && current.sessionId.equals(active.sessionId)) {
                LibraryOperationType parent = current.stack.peek();
                if (!isAllowedNested(parent, operation)) {
                    throw conflict(operation);
                }
                current.stack.push(operation);
                active.depth++;
                return new Lease(this, active.sessionId, operation, false);
            }

            throw conflict(operation);
        }
    }

    /**
     * Acquires a non-reentrant lease suitable for work represented by a Future. It may be closed
     * from a completion thread different from the thread that initiated the operation.
     */
    public Lease acquireDetached(LibraryOperationType operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (monitor) {
            if (active != null) throw conflict(operation);
            UUID sessionId = UUID.randomUUID();
            active = new ActiveSession(sessionId, operation, 1);
            return new Lease(this, sessionId, operation, true);
        }
    }

    public LibraryOperationType activeOperation() {
        synchronized (monitor) {
            return active == null ? null : active.rootOperation;
        }
    }

    public boolean isBusy() {
        synchronized (monitor) {
            return active != null;
        }
    }

    private LibraryOperationConflictException conflict(LibraryOperationType requested) {
        return new LibraryOperationConflictException(active == null ? null : active.rootOperation, requested);
    }

    private static boolean isAllowedNested(LibraryOperationType parent, LibraryOperationType child) {
        if (parent == null || child == null) return false;
        if (parent == LibraryOperationType.IMPORT && child == LibraryOperationType.IMPORT) return true;
        return (parent == LibraryOperationType.CREATE || parent == LibraryOperationType.UPDATE)
                && child == LibraryOperationType.IMPORT;
    }

    private void release(UUID sessionId, LibraryOperationType operation, boolean detached) {
        synchronized (monitor) {
            if (active == null || !active.sessionId.equals(sessionId)) return;

            if (!detached) {
                LocalSession current = local.get();
                if (current == null || !current.sessionId.equals(sessionId)) {
                    throw new IllegalStateException("Operation lease must be closed by its owning synchronous flow");
                }
                LibraryOperationType top = current.stack.peek();
                if (top != operation) {
                    throw new IllegalStateException("Operation leases must be closed in LIFO order");
                }
                current.stack.pop();
                if (current.stack.isEmpty()) local.remove();
            }

            active.depth--;
            if (active.depth <= 0) active = null;
        }
    }

    private static final class ActiveSession {
        private final UUID sessionId;
        private final LibraryOperationType rootOperation;
        private int depth;

        private ActiveSession(UUID sessionId, LibraryOperationType rootOperation, int depth) {
            this.sessionId = sessionId;
            this.rootOperation = rootOperation;
            this.depth = depth;
        }
    }

    private static final class LocalSession {
        private final UUID sessionId;
        private final Deque<LibraryOperationType> stack = new ArrayDeque<>();

        private LocalSession(UUID sessionId) {
            this.sessionId = sessionId;
        }
    }

    public static final class Lease implements AutoCloseable {
        private final LibraryOperationCoordinator coordinator;
        private final UUID sessionId;
        private final LibraryOperationType operation;
        private final boolean detached;
        private boolean closed;

        private Lease(LibraryOperationCoordinator coordinator, UUID sessionId,
                      LibraryOperationType operation, boolean detached) {
            this.coordinator = coordinator;
            this.sessionId = sessionId;
            this.operation = operation;
            this.detached = detached;
        }

        @Override
        public void close() {
            if (closed) return;
            coordinator.release(sessionId, operation, detached);
            closed = true;
        }
    }
}
