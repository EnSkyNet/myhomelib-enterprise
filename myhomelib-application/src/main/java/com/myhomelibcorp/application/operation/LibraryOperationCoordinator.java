package com.myhomelibcorp.application.operation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
@Slf4j
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
                active = new ActiveSession(sessionId, operation, 1, false);
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
                active.join(operation);
                lease = new Lease(this, active.sessionId, operation, false);
            } else {
                throw conflict(operation);
            }
        }
        if (rootStarted) {
            logOperationStarted(lease.sessionId, operation, "SYNC");
            publishActiveOperation(operation);
        }
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
            active = new ActiveSession(sessionId, operation, 1, false);
            lease = new Lease(this, sessionId, operation, true);
        }
        logOperationStarted(lease.sessionId, operation, "DETACHED");
        publishActiveOperation(operation);
        return lease;
    }

    /**
     * Acquires a detached lease that may share one root lifecycle barrier with explicitly compatible
     * collection-scoped operations. This is used by UI pre-queue guards and application boundaries:
     * the first lease blocks destructive lifecycle operations, while additional compatible workers
     * can run in parallel without opening a SWITCH queue-gap.
     */
    /**
     * Executes one synchronous unit of work under a shared detached lifecycle lease.
     *
     * <p>The helper deliberately owns only the common success/failure/close protocol. Callers that
     * need cancellation or a custom completion detail should keep explicit lease control.</p>
     */
    public <T> T executeDetachedShared(LibraryOperationType operation, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        try (Lease lease = acquireDetachedShared(operation)) {
            try {
                T result = work.get();
                lease.markCompleted("");
                return result;
            } catch (RuntimeException | Error failure) {
                lease.markFailed(failure);
                throw failure;
            }
        }
    }

    public Lease acquireDetachedShared(LibraryOperationType operation) {
        Objects.requireNonNull(operation, "operation");
        Lease lease;
        boolean rootStarted = false;
        synchronized (monitor) {
            if (active == null) {
                UUID sessionId = UUID.randomUUID();
                active = new ActiveSession(sessionId, operation, 1, true);
                lease = new Lease(this, sessionId, operation, true);
                rootStarted = true;
            } else if (active.sharedDetachedRoot && isSharedCompatible(active.rootOperation, operation)) {
                active.join(operation);
                lease = new Lease(this, active.sessionId, operation, true);
            } else {
                throw conflict(operation);
            }
        }
        if (rootStarted) {
            logOperationStarted(lease.sessionId, operation, "SHARED");
            publishActiveOperation(operation);
        }
        return lease;
    }

    /**
     * Waits until a shared lifecycle barrier can be acquired. If the current root is already a
     * compatible shared-detached session, the new lease joins it immediately; otherwise this
     * method waits for the root to become idle and starts a new shared session.
     *
     * <p>This is intended for queued collection-scoped maintenance that must not fail merely
     * because the operation that scheduled it still owns the coordinator, while still allowing
     * application-boundary QUERY/EDIT guards inside the worker to join the same barrier.</p>
     */
    public Lease acquireDetachedSharedAwait(LibraryOperationType operation) {
        Objects.requireNonNull(operation, "operation");
        Lease lease;
        boolean rootStarted = false;
        synchronized (monitor) {
            while (active != null
                    && !(active.sharedDetachedRoot && isSharedCompatible(active.rootOperation, operation))) {
                try {
                    monitor.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for shared library operation lease", interrupted);
                }
            }
            if (active == null) {
                UUID sessionId = UUID.randomUUID();
                active = new ActiveSession(sessionId, operation, 1, true);
                lease = new Lease(this, sessionId, operation, true);
                rootStarted = true;
            } else {
                active.join(operation);
                lease = new Lease(this, active.sessionId, operation, true);
            }
        }
        if (rootStarted) {
            logOperationStarted(lease.sessionId, operation, "SHARED_AWAIT");
            publishActiveOperation(operation);
        }
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
            active = new ActiveSession(sessionId, operation, 1, false);
            lease = new Lease(this, sessionId, operation, true);
        }
        logOperationStarted(lease.sessionId, operation, "DETACHED_AWAIT");
        publishActiveOperation(operation);
        return lease;
    }

    public LibraryOperationType activeOperation() {
        synchronized (monitor) {
            return active == null ? null : active.rootOperation;
        }
    }

    /** Returns whether any lease of the requested type is currently part of the active shared session. */
    public boolean isOperationActive(LibraryOperationType operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (monitor) {
            return active != null && active.has(operation);
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

    private static boolean isSharedCompatible(LibraryOperationType root, LibraryOperationType requested) {
        if (root == null || requested == null) return false;
        if (root == requested) {
            return root == LibraryOperationType.DOWNLOAD
                    || root == LibraryOperationType.EDIT
                    || root == LibraryOperationType.ANNOTATION
                    || root == LibraryOperationType.CONVERSION
                    || root == LibraryOperationType.EXPORT
                    || root == LibraryOperationType.QUERY
                    || root == LibraryOperationType.READER_STATE
                    || root == LibraryOperationType.INDEX
                    || root == LibraryOperationType.SYNC;
        }
        // Background Lucene rebuild keeps the same active collection and does not touch
        // reader state or catalog rows. UI startup reads must not be blocked by it, and
        // a running reader-state read must not force the background rebuild to fail.
        if ((root == LibraryOperationType.INDEX && requested == LibraryOperationType.READER_STATE)
                || (root == LibraryOperationType.READER_STATE && requested == LibraryOperationType.INDEX)) {
            return true;
        }
        // A background rebuild already owns the stable-collection barrier. User downloads may
        // join that barrier, but the reverse direction stays blocked: starting a rebuild inside
        // an arbitrary download session would make post-download Lucene synchronization racy.
        if (root == LibraryOperationType.INDEX && requested == LibraryOperationType.DOWNLOAD) {
            return true;
        }
        // Conversion is file-local work. It must not freeze reader/session state or prevent an
        // unrelated online download while the active collection itself remains protected from SWITCH.
        if (root == LibraryOperationType.CONVERSION
                && (requested == LibraryOperationType.READER_STATE
                    || requested == LibraryOperationType.DOWNLOAD)) {
            return true;
        }
        return (root == LibraryOperationType.EXPORT && requested == LibraryOperationType.CONVERSION)
                || (root == LibraryOperationType.CONVERSION && requested == LibraryOperationType.EXPORT)
                || queryCompatiblePair(root, requested);
    }

    private static boolean queryCompatiblePair(LibraryOperationType root, LibraryOperationType requested) {
        // A protected mutation/download/export may perform nested read-only lookups while keeping the
        // same active collection. The reverse direction stays blocked so an unrelated mutation cannot
        // join a long-running QUERY root merely because both are collection-scoped.
        if (requested == LibraryOperationType.QUERY) return supportsStableCollectionQuery(root);
        // Ordinary QUERY roots are often pre-acquired before UI work reaches the bounded executor.
        // A burst of navigation/details requests can therefore keep QUERY active while no query is
        // actually running. File-local/background work in the same stable collection must not be
        // rejected behind that passive read barrier. Destructive lifecycle operations (SWITCH,
        // DELETE, RESTORE, CREATE, IMPORT, etc.) remain incompatible and still wait for all leases.
        if (root == LibraryOperationType.QUERY) {
            return requested == LibraryOperationType.READER_STATE
                    || requested == LibraryOperationType.DOWNLOAD
                    || requested == LibraryOperationType.CONVERSION
                    || requested == LibraryOperationType.INDEX
                    || requested == LibraryOperationType.EXPORT;
        }
        return false;
    }

    /** Operations that keep the same active collection for their complete lifetime. */
    private static boolean supportsStableCollectionQuery(LibraryOperationType operation) {
        return operation == LibraryOperationType.DOWNLOAD
                || operation == LibraryOperationType.EDIT
                || operation == LibraryOperationType.ANNOTATION
                || operation == LibraryOperationType.CONVERSION
                || operation == LibraryOperationType.EXPORT
                || operation == LibraryOperationType.READER_STATE
                || operation == LibraryOperationType.INDEX;
    }

    private void release(UUID sessionId, LibraryOperationType operation, boolean detached,
                         LibraryOperationOutcome outcome, String detail) {
        boolean becameIdle = false;
        LibraryOperationCompletion completion = null;
        UUID completedSessionId = null;
        long completedDurationMs = -1L;
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
            active.leave(operation);
            if (active.depth <= 0) {
                completion = new LibraryOperationCompletion(active.rootOperation, active.outcome, active.detail);
                completedSessionId = active.sessionId;
                completedDurationMs = Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - active.startedNanos));
                active = null;
                becameIdle = true;
                monitor.notifyAll();
            }
        }
        // Publish the terminal result before the active-state transition. Consumers that keep an
        // operation-history row can finish it accurately before a queued operation starts.
        if (completion != null) {
            logOperationCompleted(completedSessionId, completion, completedDurationMs);
            publishCompletion(completion);
        }

        // A waiter may acquire the next root lease immediately after notifyAll(). Publish the
        // current root state rather than a stale null so observers cannot finish on an idle state
        // while a queued background operation already owns the coordinator.
        if (becameIdle) publishActiveOperation(activeOperation());
    }

    private static void logOperationStarted(UUID sessionId, LibraryOperationType operation, String mode) {
        if (operation == LibraryOperationType.QUERY || operation == LibraryOperationType.READER_STATE) {
            log.debug("LIBRARY_OPERATION_START session={} operation={} mode={}", sessionId, operation, mode);
        } else {
            log.info("LIBRARY_OPERATION_START session={} operation={} mode={}", sessionId, operation, mode);
        }
    }

    private static void logOperationCompleted(UUID sessionId, LibraryOperationCompletion completion, long durationMs) {
        if (completion == null) return;
        LibraryOperationType operation = completion.operation();
        if (operation == LibraryOperationType.QUERY || operation == LibraryOperationType.READER_STATE) {
            log.debug("LIBRARY_OPERATION_DONE session={} operation={} outcome={} durationMs={}",
                    sessionId, operation, completion.outcome(), durationMs);
        } else {
            log.info("LIBRARY_OPERATION_DONE session={} operation={} outcome={} durationMs={}",
                    sessionId, operation, completion.outcome(), durationMs);
        }
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
        private final boolean sharedDetachedRoot;
        private final long startedNanos;
        private final EnumMap<LibraryOperationType, Integer> operationCounts = new EnumMap<>(LibraryOperationType.class);
        private int depth;
        private LibraryOperationOutcome outcome = LibraryOperationOutcome.COMPLETED;
        private String detail = "";

        private ActiveSession(UUID sessionId, LibraryOperationType rootOperation, int depth, boolean sharedDetachedRoot) {
            this.sessionId = sessionId;
            this.rootOperation = rootOperation;
            this.depth = depth;
            this.sharedDetachedRoot = sharedDetachedRoot;
            this.startedNanos = System.nanoTime();
            operationCounts.put(rootOperation, depth);
        }

        private void join(LibraryOperationType operation) {
            depth++;
            operationCounts.merge(operation, 1, Integer::sum);
        }

        private void leave(LibraryOperationType operation) {
            Integer count = operationCounts.get(operation);
            if (count == null || count <= 0) {
                throw new IllegalStateException("Operation lease accounting mismatch: " + operation);
            }
            if (count == 1) operationCounts.remove(operation);
            else operationCounts.put(operation, count - 1);
            depth--;
        }

        private boolean has(LibraryOperationType operation) {
            return operationCounts.getOrDefault(operation, 0) > 0;
        }

        private void recordOutcome(LibraryOperationOutcome candidate, String candidateDetail) {
            LibraryOperationOutcome effective = candidate == null ? LibraryOperationOutcome.COMPLETED : candidate;
            String normalizedDetail = candidateDetail == null ? "" : candidateDetail.trim();

            // Shared detached leases contribute to one root result. Preserve the detail that belongs
            // to the strongest terminal outcome instead of letting a later successful sibling replace
            // the diagnostic of an earlier failure/cancellation.
            if (effective == LibraryOperationOutcome.FAILED) {
                outcome = LibraryOperationOutcome.FAILED;
                if (!normalizedDetail.isBlank()) detail = normalizedDetail;
                return;
            }
            if (outcome == LibraryOperationOutcome.FAILED) return;

            if (effective == LibraryOperationOutcome.CANCELLED) {
                outcome = LibraryOperationOutcome.CANCELLED;
                if (!normalizedDetail.isBlank()) detail = normalizedDetail;
                return;
            }
            if (outcome == LibraryOperationOutcome.CANCELLED) return;

            if (!normalizedDetail.isBlank()) detail = normalizedDetail;
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
        public synchronized Lease markCompleted(String detail) {
            if (!closed && outcome != LibraryOperationOutcome.FAILED && outcome != LibraryOperationOutcome.CANCELLED) {
                outcome = LibraryOperationOutcome.COMPLETED;
                this.detail = detail == null ? "" : detail;
            }
            return this;
        }

        /** Marks the root operation as cancelled before closing its lease. */
        public synchronized Lease markCancelled(String detail) {
            if (!closed && outcome != LibraryOperationOutcome.FAILED) {
                outcome = LibraryOperationOutcome.CANCELLED;
                this.detail = detail == null ? "" : detail;
            }
            return this;
        }

        /** Marks the root operation as failed before closing its lease. */
        public synchronized Lease markFailed(Throwable failure) {
            return markFailed(rootMessage(failure));
        }

        /** Marks the root operation as failed when a result object already carries the failure detail. */
        public synchronized Lease markFailed(String detail) {
            if (!closed) {
                outcome = LibraryOperationOutcome.FAILED;
                this.detail = detail == null || detail.isBlank() ? "Невідома помилка" : detail;
            }
            return this;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            // Detached leases are explicitly allowed to be completed from another thread. The
            // synchronized close makes the release/depth decrement exactly-once while still leaving
            // a synchronous lease retryable by its owner when release() rejects a wrong-thread or
            // non-LIFO close attempt.
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