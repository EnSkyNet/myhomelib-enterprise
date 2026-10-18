package com.myhomelibcorp.ui.reader;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared crash-loss-bounded autosave state machine for text and audiobook positions.
 *
 * <p>The latest UI state lives in one atomic snapshot. Persistence is serialized per book,
 * failed snapshots survive book switches, and monotonically increasing revisions prevent an
 * older retry from overwriting a newer successful save. The scheduler is started lazily on the
 * first active book so merely constructing the reader workspace does not create an idle thread.</p>
 */
@Slf4j
final class PositionAutosaveCoordinator<P, A> implements AutoCloseable {
    private static final long FLUSH_INTERVAL_SECONDS = 3L;
    private static final long CLOSE_TIMEOUT_SECONDS = 2L;

    @FunctionalInterface
    interface Persistence<P, A> {
        boolean save(String collectionId, String bookId, P position, A auxiliary) throws Exception;
    }

    private final String subject;
    private final Persistence<P, A> persistence;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean schedulerStarted = new AtomicBoolean();
    private final AtomicReference<Snapshot<P, A>> state = new AtomicReference<>(Snapshot.empty());
    private final AtomicLong revisionSequence = new AtomicLong();
    private final ConcurrentMap<BookKey, Snapshot<P, A>> pendingFailures = new ConcurrentHashMap<>();
    // Keep revision state only while a save is in-flight or an unsaved snapshot is
    // waiting for retry. Successfully saved books do NOT accumulate here forever.
    // An in-flight reference is registered *before* claiming the UI snapshot; this
    // prevents a slow older flush from losing its revision fence when a newer flush
    // of the same book commits and the current book changes.
    private final ConcurrentMap<BookKey, BookSaveState> saveStates = new ConcurrentHashMap<>();
    private final Object lifecycleGuard = new Object();
    private boolean closing;
    private boolean closeInProgress;
    private int activeOperations;
    private Boolean closeResult;

    PositionAutosaveCoordinator(String subject, String threadName, Persistence<P, A> persistence) {
        this.subject = requireText(subject, "subject");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        String safeThreadName = requireText(threadName, "threadName");
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, safeThreadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    void start(String collectionId, String bookId, A auxiliary) {
        if (!beginOperation()) throw new IllegalStateException("Autosave is already closing");
        try {
            flushWork();
            state.set(new Snapshot<>(collectionId, bookId, null, auxiliary, false, revisionSequence.get()));
            startSchedulerIfNeeded();
        } finally {
            finishOperation();
        }
    }

    void mark(P position) {
        if (position == null || !beginOperation()) return;
        try {
            long revision = revisionSequence.incrementAndGet();
            state.updateAndGet(current -> current.bookId() == null || revision <= current.revision()
                    ? current
                    : new Snapshot<>(current.collectionId(), current.bookId(), position, current.auxiliary(), true, revision));
        } finally {
            finishOperation();
        }
    }

    void mark(P position, A auxiliary) {
        if (position == null || !beginOperation()) return;
        try {
            long revision = revisionSequence.incrementAndGet();
            state.updateAndGet(current -> current.bookId() == null || revision <= current.revision()
                    ? current
                    : new Snapshot<>(current.collectionId(), current.bookId(), position, auxiliary, true, revision));
        } finally {
            finishOperation();
        }
    }

    boolean flush() {
        if (!beginOperation()) return false;
        try {
            return flushWork();
        } finally {
            finishOperation();
        }
    }

    private boolean flushWork() {
        retryPendingFailures();
        // API contract: this reports only the currently selected book's flush result.
        // Older pending failures remain queued for retry and are checked by closeAndFlush().
        return flushCurrentIfDirty();
    }

    /**
     * Stop admitting changes and persist the last known positions. A failed close
     * deliberately keeps the in-memory snapshots: callers may retry after SQLite
     * becomes available or an outstanding write finishes. Only a fully successful
     * close releases the recovery data.
     */
    boolean closeAndFlush() {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLOSE_TIMEOUT_SECONDS);
        synchronized (lifecycleGuard) {
            if (Boolean.TRUE.equals(closeResult)) return true;
            // A second concurrent shutdown must not claim that the first has succeeded.
            if (closeInProgress) return false;
            closing = true; // No new marks, starts, or ordinary flushes may be admitted.
            closeInProgress = true;
        }
        boolean saved = false;
        try {
            stopScheduler();

            // The scheduled executor is not the only caller: an explicit UI or
            // background flush may still be inside persistence.save().
            synchronized (lifecycleGuard) {
                while (activeOperations > 0) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0) break;
                    try {
                        TimeUnit.NANOSECONDS.timedWait(lifecycleGuard, remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (activeOperations > 0) {
                    log.warn("{} autosave close timed out with {} unfinished operation(s)",
                            subject, activeOperations);
                    return false;
                }
            }

            retryPendingFailures();
            boolean currentSaved = flushCurrentIfDirty();
            // A newer successful snapshot can supersede a failed older retry.
            // Evaluate the final pending state, not an intermediate retry failure.
            saved = currentSaved && pendingFailures.isEmpty();
            if (saved) {
                state.set(Snapshot.empty());
                pendingFailures.clear();
                saveStates.clear();
            } else {
                log.warn("{} autosave close failed; {} unsaved position(s) retained for retry",
                        subject, pendingFailures.size());
            }
            return saved;
        } finally {
            synchronized (lifecycleGuard) {
                closeResult = saved;
                closeInProgress = false;
                lifecycleGuard.notifyAll();
            }
        }
    }

    private boolean beginOperation() {
        synchronized (lifecycleGuard) {
            if (closing) return false;
            activeOperations++;
            return true;
        }
    }

    private void finishOperation() {
        synchronized (lifecycleGuard) {
            activeOperations--;
            lifecycleGuard.notifyAll();
        }
    }

    @Override
    public void close() {
        closeAndFlush();
    }

    private void startSchedulerIfNeeded() {
        if (!schedulerStarted.compareAndSet(false, true)) return;
        executor.scheduleWithFixedDelay(this::flush,
                FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopScheduler() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private boolean flushCurrentIfDirty() {
        while (true) {
            Snapshot<P, A> snapshot = state.get();
            if (snapshot.bookId() == null || snapshot.latest() == null || !snapshot.dirty()) return true;
            BookKey key = new BookKey(snapshot.collectionId(), snapshot.bookId());
            BookSaveState saveState = register(key);
            try {
                if (state.compareAndSet(snapshot, snapshot.clean())) {
                    return persistRegistered(key, snapshot, saveState);
                }
            } finally {
                release(key, saveState);
            }
        }
    }

    private boolean retryPendingFailures() {
        if (pendingFailures.isEmpty()) return true;
        boolean allSaved = true;
        for (BookKey key : new ArrayList<>(pendingFailures.keySet())) {
            BookSaveState saveState = register(key);
            try {
                synchronized (saveState) {
                    Snapshot<P, A> pending = pendingFailures.get(key);
                    if (pending != null && !persistLocked(key, pending, saveState)) allSaved = false;
                }
            } finally {
                release(key, saveState);
            }
        }
        return allSaved;
    }

    private boolean persistRegistered(BookKey key, Snapshot<P, A> snapshot, BookSaveState saveState) {
        synchronized (saveState) {
            return persistLocked(key, snapshot, saveState);
        }
    }

    private boolean persistLocked(BookKey key, Snapshot<P, A> snapshot, BookSaveState saveState) {
        // Two concurrent flushes can claim successive snapshots before either acquires the
        // per-book lock. A delayed older flush must never overwrite a newer committed one.
        long committedRevision = saveState.committedRevision;
        if (snapshot.revision() <= committedRevision) {
            pendingFailures.computeIfPresent(key, (ignored, pending) ->
                    pending.revision() <= committedRevision ? null : pending);
            return true;
        }
        try {
            if (persistence.save(snapshot.collectionId(), snapshot.bookId(), snapshot.latest(), snapshot.auxiliary())) {
                saveState.committedRevision = snapshot.revision();
                pendingFailures.computeIfPresent(key, (ignored, pending) ->
                        pending.revision() <= snapshot.revision() ? null : pending);
                return true;
            }
            rememberFailure(key, snapshot);
            log.warn("{} autosave failed for {}; retained for retry", subject, snapshot.bookId());
            return false;
        } catch (Exception error) {
            rememberFailure(key, snapshot);
            log.warn("{} autosave failed for {}: {}; retained for retry", subject, snapshot.bookId(), error.getMessage());
            return false;
        }
    }

    private BookSaveState register(BookKey key) {
        return saveStates.compute(key, (ignored, current) -> {
            BookSaveState result = current == null ? new BookSaveState() : current;
            result.activeSaves++;
            return result;
        });
    }

    private void release(BookKey key, BookSaveState acquired) {
        saveStates.computeIfPresent(key, (ignored, current) -> {
            if (current != acquired) {
                throw new IllegalStateException("Autosave state was replaced during an active save");
            }
            current.activeSaves--;
            // Retain the revision fence for a failed snapshot, but forget fully
            // saved books as soon as their final in-flight save has finished.
            return current.activeSaves == 0 && !pendingFailures.containsKey(key) ? null : current;
        });
    }

    int trackedBookCount() {
        return saveStates.size();
    }

    private static final class BookSaveState {
        private int activeSaves; // Modified under saveStates.compute for this key.
        private long committedRevision = -1L; // Modified under synchronized(this).
    }

    private void rememberFailure(BookKey key, Snapshot<P, A> snapshot) {
        pendingFailures.merge(key, snapshot,
                (existing, candidate) -> candidate.revision() >= existing.revision() ? candidate : existing);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private record BookKey(String collectionId, String bookId) { }

    private record Snapshot<P, A>(
            String collectionId,
            String bookId,
            P latest,
            A auxiliary,
            boolean dirty,
            long revision) {
        private static <P, A> Snapshot<P, A> empty() {
            return new Snapshot<>(null, null, null, null, false, 0L);
        }

        private Snapshot<P, A> clean() {
            return new Snapshot<>(collectionId, bookId, latest, auxiliary, false, revision);
        }
    }
}
