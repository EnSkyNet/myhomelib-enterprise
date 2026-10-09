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
    private final ConcurrentMap<BookKey, Object> persistenceLocks = new ConcurrentHashMap<>();

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
        flush();
        state.set(new Snapshot<>(collectionId, bookId, null, auxiliary, false, revisionSequence.get()));
        startSchedulerIfNeeded();
    }

    void mark(P position) {
        if (position == null) return;
        long revision = revisionSequence.incrementAndGet();
        state.updateAndGet(current -> current.bookId() == null
                ? current
                : new Snapshot<>(current.collectionId(), current.bookId(), position, current.auxiliary(), true, revision));
    }

    void mark(P position, A auxiliary) {
        if (position == null) return;
        long revision = revisionSequence.incrementAndGet();
        state.updateAndGet(current -> current.bookId() == null
                ? current
                : new Snapshot<>(current.collectionId(), current.bookId(), position, auxiliary, true, revision));
    }

    boolean flush() {
        retryPendingFailures();
        return flushCurrentIfDirty();
    }

    boolean closeAndFlush() {
        stopScheduler();
        retryPendingFailures();
        boolean currentSaved = flushCurrentIfDirty();
        boolean allSaved = currentSaved && pendingFailures.isEmpty();
        if (!allSaved) {
            log.warn("{} autosave closed with {} retained unsaved position(s)", subject, pendingFailures.size());
        }
        state.set(Snapshot.empty());
        pendingFailures.clear();
        persistenceLocks.clear();
        return allSaved;
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
        Snapshot<P, A> snapshot;
        while (true) {
            snapshot = state.get();
            if (snapshot.bookId() == null || snapshot.latest() == null || !snapshot.dirty()) return true;
            if (state.compareAndSet(snapshot, snapshot.clean())) break;
        }
        return persist(snapshot);
    }

    private boolean retryPendingFailures() {
        if (pendingFailures.isEmpty()) return true;
        boolean allSaved = true;
        for (BookKey key : new ArrayList<>(pendingFailures.keySet())) {
            Object lock = persistenceLocks.computeIfAbsent(key, ignored -> new Object());
            synchronized (lock) {
                Snapshot<P, A> pending = pendingFailures.get(key);
                if (pending != null && !persistLocked(key, pending)) allSaved = false;
            }
        }
        return allSaved;
    }

    private boolean persist(Snapshot<P, A> snapshot) {
        BookKey key = new BookKey(snapshot.collectionId(), snapshot.bookId());
        Object lock = persistenceLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            return persistLocked(key, snapshot);
        }
    }

    private boolean persistLocked(BookKey key, Snapshot<P, A> snapshot) {
        try {
            if (persistence.save(snapshot.collectionId(), snapshot.bookId(), snapshot.latest(), snapshot.auxiliary())) {
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
