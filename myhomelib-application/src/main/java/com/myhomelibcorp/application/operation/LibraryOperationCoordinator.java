package com.myhomelibcorp.application.operation;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Serializes catalogue-changing and maintenance operations.
 *
 * <p>Only explicit composition is re-entrant: CREATE/UPDATE/IMPORT may call IMPORT internally.
 * Async callers use a detached lease so the lock remains held after the initiating UI thread returns.</p>
 *
 * <p>Listeners expose only the root operation and are deliberately framework-neutral. This lets UI/status
 * projections tell the user what background operation currently owns the library without coupling the
 * application layer to JavaFX.</p>
 */
@Service
public final class LibraryOperationCoordinator {

    private final Object monitor = new Object();
    private ActiveSession active;
    private final ThreadLocal<LocalSession> local = new ThreadLocal<>();
    private final CopyOnWriteArrayList<Consumer<LibraryOperationType>> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<LibraryOperationCompletion>> completionListeners = new CopyOnWriteArrayList<>();

    public Lease acquire(LibraryOperationType operation) {
        Objects.requireNonNull(operation, "operation");
        Lease lease;
        boolean rootStarted = false;
        synchronized (monitor) {
            LocalSession current = local.get();
            if (active == null) {
                UUID sessionId = UUID.randomUUID();
                active = new ActiveSession(sessionId, operation, 1);
                LocalSession created = new LocalSession(sessionId);
                created.stack.push(operation);
                local.set(created);
                lease = new Lease(this, sessionId, operation, false);
                rootStarted = true;
            } else if (current != null && current.sessionId.equals(active.sessionId)) {
                LibraryOperationType parent = current.stack.peek();
                if (!isAllowedNested(parent, operation)) {
                    throw conflict(operation);
                }
                current.stack.push(operation);
                active.depth++;
                lease = new Lease(this, active.sessionId, operation, false);
            } else {
                throw conflict(operation);
            }
        }
        if (rootStarted) publishActiveOperation(operation);
        return lease;
    }

    /**
     * Acquires a non-reentrant lease suitable for work represented by a Future. It may be closed
     * from a completion thread different from the thread that initiated the operation.
     */
    public Lease acquireDetached(LibraryOperationType operation) {
        Objects.requireNonNull(operation, "operation");
        Lease lease;
        synchronized (monitor) {
            if (active != null) throw conflict(operation);
            UUID sessionId = UUID.randomUUID();
            active = new ActiveSession(sessionId, operation, 1);
            lease = new Lease(this, sessionId, operation, true);
        }
        publishActiveOperation(operation);
        return lease;
    }

    /**
     * Waits until the current lifecycle operation is fully released, then acquires a detached lease.
     * Intended for queued background maintenance started while a synchronous SWITCH/CREATE flow still owns the coordinator.
     */
    public Lease acquireDetachedAwait(LibraryOperationType operation) {
        Objects.requireNonNull(operation, "operation");
        Lease lease;
        synchronized (monitor) {
            while (active != null) {
                try {
                    monitor.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for library operation lease", interrupted);
                }
            }
            UUID sessionId = UUID.randomUUID();
            active = new ActiveSession(sessionId, operation, 1);
            lease = new Lease(this, sessionId, operation, true);
        }
        publishActiveOperation(operation);
        return lease;
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

    /**
     * Registers an observer of the root library operation and immediately emits the current state.
     * A {@code null} value means that no serialized library operation is active.
     */
    public AutoCloseable addListener(Consumer<LibraryOperationType> listener) {
        if (listener == null) return () -> { };
        listeners.add(listener);
        listener.accept(activeOperation());
        return () -> listeners.remove(listener);
    }

    /** Registers an observer of terminal root-operation outcomes. */
    public AutoCloseable addCompletionListener(Consumer<LibraryOperationCompletion> listener) {
        if (listener == null) return () -> { };
        completionListeners.add(listener);
        return () -> completionListeners.remove(listener);
    }

    /** Returns true only when the current thread owns the active synchronous operation session. */
    public boolean isHeldByCurrentThread() {
        synchronized (monitor) {
            LocalSession current = local.get();
            return active != null && current != null && current.sessionId.equals(active.sessionId);
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

    private void release(UUID sessionId, LibraryOperationType operation, boolean detached,
                         LibraryOperationOutcome outcome, String detail) {
        boolean becameIdle = false;
        LibraryOperationCompletion completion = null;
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

            active.recordOutcome(outcome, detail);
            active.depth--;
            if (active.depth <= 0) {
                completion = new LibraryOperationCompletion(active.rootOperation, active.outcome, active.detail);
                active = null;
                becameIdle = true;
                monitor.notifyAll();
            }
        }
        // Publish the terminal result before the active-state transition. Consumers that keep an
        // operation-history row can finish it accurately before a queued operation starts.
        if (completion != null) publishCompletion(completion);

        // A waiter may acquire the next root lease immediately after notifyAll(). Publish the
        // current root state rather than a stale null so observers cannot finish on an idle state
        // while a queued background operation already owns the coordinator.
        if (becameIdle) publishActiveOperation(activeOperation());
    }

    private void publishActiveOperation(LibraryOperationType operation) {
        for (Consumer<LibraryOperationType> listener : listeners) {
            try {
                listener.accept(operation);
            } catch (RuntimeException ignored) {
                // A status/telemetry observer must never break the coordinated library operation.
            }
        }
    }

    private void publishCompletion(LibraryOperationCompletion completion) {
        for (Consumer<LibraryOperationCompletion> listener : completionListeners) {
            try {
                listener.accept(completion);
            } catch (RuntimeException ignored) {
                // Telemetry/history observers must never break a library operation.
            }
        }
    }

    private static final class ActiveSession {
        private final UUID sessionId;
        private final LibraryOperationType rootOperation;
        private int depth;
        private LibraryOperationOutcome outcome = LibraryOperationOutcome.COMPLETED;
        private String detail = "";

        private ActiveSession(UUID sessionId, LibraryOperationType rootOperation, int depth) {
            this.sessionId = sessionId;
            this.rootOperation = rootOperation;
            this.depth = depth;
        }

        private void recordOutcome(LibraryOperationOutcome candidate, String candidateDetail) {
            LibraryOperationOutcome effective = candidate == null ? LibraryOperationOutcome.COMPLETED : candidate;
            if (effective == LibraryOperationOutcome.FAILED
                    || (effective == LibraryOperationOutcome.CANCELLED && outcome != LibraryOperationOutcome.FAILED)) {
                outcome = effective;
            }
            if (candidateDetail != null && !candidateDetail.isBlank()) detail = candidateDetail;
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
        private LibraryOperationOutcome outcome = LibraryOperationOutcome.COMPLETED;
        private String detail = "";
        private boolean closed;

        private Lease(LibraryOperationCoordinator coordinator, UUID sessionId,
                      LibraryOperationType operation, boolean detached) {
            this.coordinator = coordinator;
            this.sessionId = sessionId;
            this.operation = operation;
            this.detached = detached;
        }

        /** Adds a successful result description to the terminal history event. */
        public Lease markCompleted(String detail) {
            if (!closed && outcome != LibraryOperationOutcome.FAILED && outcome != LibraryOperationOutcome.CANCELLED) {
                outcome = LibraryOperationOutcome.COMPLETED;
                this.detail = detail == null ? "" : detail;
            }
            return this;
        }

        /** Marks the root operation as cancelled before closing its lease. */
        public Lease markCancelled(String detail) {
            if (!closed && outcome != LibraryOperationOutcome.FAILED) {
                outcome = LibraryOperationOutcome.CANCELLED;
                this.detail = detail == null ? "" : detail;
            }
            return this;
        }

        /** Marks the root operation as failed before closing its lease. */
        public Lease markFailed(Throwable failure) {
            if (!closed) {
                outcome = LibraryOperationOutcome.FAILED;
                detail = rootMessage(failure);
            }
            return this;
        }

        @Override
        public void close() {
            if (closed) return;
            coordinator.release(sessionId, operation, detached, outcome, detail);
            closed = true;
        }

        private static String rootMessage(Throwable failure) {
            if (failure == null) return "Невідома помилка";
            Throwable current = failure;
            while (current.getCause() != null && current.getCause() != current) current = current.getCause();
            String message = current.getMessage();
            return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
        }
    }
}
