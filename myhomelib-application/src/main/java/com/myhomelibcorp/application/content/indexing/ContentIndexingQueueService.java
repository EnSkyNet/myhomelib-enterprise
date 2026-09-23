package com.myhomelibcorp.application.content.indexing;

import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.application.port.out.contentindexing.ContentIndexQueueCheckpointPort;
import com.myhomelibcorp.application.port.out.contentindexing.ContentIndexingTaskProcessor;
import com.myhomelibcorp.application.port.out.contentindexing.PowerStatePort;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Persistent, restart-safe background queue for full-text content indexing.
 * Public control methods are non-blocking; file extraction/index mutation runs only on daemon workers.
 */
@Service
public class ContentIndexingQueueService implements DisposableBean {
    public static final int MAX_OUTSTANDING_TASKS = 20_000;
    private static final Comparator<ContentIndexingTask> ORDER = Comparator
            .comparingInt((ContentIndexingTask task) -> task.priority().order())
            .thenComparing(ContentIndexingTask::enqueuedAt)
            .thenComparing(ContentIndexingTask::taskId);

    private final ContentIndexingTaskProcessor processor;
    private final ContentIndexQueueCheckpointPort checkpointPort;
    private final IndexingPerformanceSettingsService performanceSettings;
    private final PowerStatePort powerState;
    private final ContentIndexPort contentIndex;
    private final int processors;
    private final PriorityBlockingQueue<ContentIndexingTask> pending = new PriorityBlockingQueue<>(32, ORDER);
    private final Map<String, ContentIndexingTask> outstanding = new ConcurrentHashMap<>();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Map<String, ContentIndexingTask> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> cancellation = new ConcurrentHashMap<>();
    private final Map<String, String> artifactTaskIds = new ConcurrentHashMap<>();
    private final Set<String> failedArtifactKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> supersededTaskIds = ConcurrentHashMap.newKeySet();
    private final Set<String> rebuildRequiredCollections = ConcurrentHashMap.newKeySet();
    private final Set<String> staleHolds = ConcurrentHashMap.newKeySet();
    private final Set<String> restoredCollections = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<Consumer<ContentIndexingQueueSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Object enqueueLock = new Object();
    private volatile String lastError = "";

    private final ScheduledExecutorService coordinator;
    private final ExecutorService workers;
    private final ExecutorService checkpointExecutor;

    /** Compatibility constructor for benchmarks/isolated tests that do not exercise freshness markers. */
    public ContentIndexingQueueService(ContentIndexingTaskProcessor processor,
                                       ContentIndexQueueCheckpointPort checkpointPort,
                                       IndexingPerformanceSettingsService performanceSettings,
                                       PowerStatePort powerState) {
        this(processor, checkpointPort, performanceSettings, powerState, ContentIndexPortNoop.INSTANCE,
                Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    @Autowired
    public ContentIndexingQueueService(ContentIndexingTaskProcessor processor,
                                       ContentIndexQueueCheckpointPort checkpointPort,
                                       IndexingPerformanceSettingsService performanceSettings,
                                       PowerStatePort powerState,
                                       ContentIndexPort contentIndex) {
        this(processor, checkpointPort, performanceSettings, powerState, contentIndex,
                Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    ContentIndexingQueueService(ContentIndexingTaskProcessor processor,
                                ContentIndexQueueCheckpointPort checkpointPort,
                                IndexingPerformanceSettingsService performanceSettings,
                                PowerStatePort powerState,
                                int processors) {
        this(processor, checkpointPort, performanceSettings, powerState, ContentIndexPortNoop.INSTANCE, processors);
    }

    ContentIndexingQueueService(ContentIndexingTaskProcessor processor,
                                ContentIndexQueueCheckpointPort checkpointPort,
                                IndexingPerformanceSettingsService performanceSettings,
                                PowerStatePort powerState,
                                ContentIndexPort contentIndex,
                                int processors) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.checkpointPort = Objects.requireNonNull(checkpointPort, "checkpointPort");
        this.performanceSettings = Objects.requireNonNull(performanceSettings, "performanceSettings");
        this.powerState = Objects.requireNonNull(powerState, "powerState");
        this.contentIndex = Objects.requireNonNull(contentIndex, "contentIndex");
        this.processors = Math.max(1, processors);
        int maximumWorkers = IndexingResourceProfile.FAST.workerThreads(this.processors);
        // The queue service already caps submitted work through active.size() < limit.  Use a
        // zero-queue owned pool so a programming regression cannot silently accumulate an
        // unbounded LinkedBlockingQueue as Executors.newFixedThreadPool would.
        this.workers = new java.util.concurrent.ThreadPoolExecutor(
                maximumWorkers, maximumWorkers, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                r -> daemon(r, "mhl-content-index-worker"),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        this.checkpointExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "mhl-content-index-checkpoint"));
        this.coordinator = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "mhl-content-index-coordinator"));
        this.coordinator.scheduleWithFixedDelay(this::dispatchSafely, 0L, 50L, TimeUnit.MILLISECONDS);
    }

    /** Restores unfinished work. Tasks that were active at process termination are intentionally re-queued. */
    public int restore(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return 0;
        String id = collectionId.trim();
        if (!restoredCollections.add(id)) return 0;
        Optional<ContentIndexQueueCheckpoint> loaded;
        try {
            loaded = checkpointPort.load(id);
        } catch (RuntimeException failure) {
            restoredCollections.remove(id);
            throw failure;
        }
        if (loaded.isEmpty()) {
            // A persisted STALE marker without a queue checkpoint means the process may have
            // crashed after publishing staleness but before durably recording the exact work.
            // Exact incremental recovery is no longer provable, so fail closed to a full rebuild.
            try {
                if (contentIndex.health(id).state() == com.myhomelibcorp.application.content.index.ContentIndexHealth.State.STALE) {
                    rebuildRequiredCollections.add(id);
                    checkpointCollection(id);
                }
            } catch (RuntimeException failure) {
                restoredCollections.remove(id);
                throw failure;
            }
            return 0;
        }
        ContentIndexQueueCheckpoint state = loaded.get();
        if (state.tasks().size() > MAX_OUTSTANDING_TASKS) {
            throw new IllegalStateException("Content indexing checkpoint exceeds queue capacity: " + state.tasks().size());
        }
        if (state.paused()) paused.set(true);
        if (state.rebuildRequired()) rebuildRequiredCollections.add(id);
        if (state.rebuildRequired() || !state.tasks().isEmpty()) contentIndex.markStale(id);
        int restored = 0;
        for (ContentIndexingTask task : state.tasks()) {
            if (!outstanding.containsKey(task.taskId())) {
                outstanding.put(task.taskId(), task);
                artifactTaskIds.put(artifactKey(task), task.taskId());
                pending.offer(task);
                restored++;
            }
        }
        notifyListeners();
        return restored;
    }

    /**
     * Enqueues a task without blocking on extraction/index I/O. A newer task for the same
     * collection+artifact supersedes an older pending task; an already active task is left alone.
     */
    public void enqueue(ContentIndexingTask task) {
        ensureOpen();
        Objects.requireNonNull(task, "task");
        synchronized (enqueueLock) {
            enqueueInternal(task, true);
        }
        notifyListeners();
    }

    /**
     * Enqueues one bounded batch while checkpointing once per affected collection. This avoids
     * O(n^2) checkpoint rewriting when an import produces thousands of exact changed IDs.
     */
    public int enqueueAll(List<ContentIndexingTask> tasks) {
        ensureOpen();
        if (tasks == null || tasks.isEmpty()) return 0;
        List<ContentIndexingTask> acceptedTasks = tasks.stream().filter(Objects::nonNull).toList();
        if (acceptedTasks.isEmpty()) return 0;
        java.util.LinkedHashSet<String> collections = new java.util.LinkedHashSet<>();
        synchronized (enqueueLock) {
            // Reserve capacity for the whole batch before publishing its first task. A conservative
            // reservation is intentional: falling back to a full rebuild is safer than exposing a
            // partially queued change set that could later be mistaken for a fresh index.
            if (acceptedTasks.size() > availableCapacity()) {
                throw new IllegalStateException("Content indexing queue capacity exceeded: " + MAX_OUTSTANDING_TASKS);
            }
            for (ContentIndexingTask task : acceptedTasks) {
                enqueueInternal(task, false);
                collections.add(task.collectionId());
            }
        }
        collections.forEach(this::requestCheckpoint);
        notifyListeners();
        return acceptedTasks.size();
    }

    private void enqueueInternal(ContentIndexingTask task, boolean checkpoint) {
        String key = artifactKey(task);
        prepareCollectionForWork(task.collectionId());
        String previousId = artifactTaskIds.get(key);
        boolean replacingPending = previousId != null
                && !previousId.equals(task.taskId())
                && !active.contains(previousId)
                && outstanding.containsKey(previousId);
        boolean alreadyOutstanding = outstanding.containsKey(task.taskId());
        if (!replacingPending && !alreadyOutstanding && outstanding.size() >= MAX_OUTSTANDING_TASKS) {
            throw new IllegalStateException("Content indexing queue capacity exceeded: " + MAX_OUTSTANDING_TASKS);
        }
        // Publishing a stale marker before the task becomes visible prevents content search
        // from serving an older index while background extraction is pending.
        contentIndex.markStale(task.collectionId());
        failedArtifactKeys.remove(key);
        previousId = artifactTaskIds.put(key, task.taskId());
        if (previousId != null && !previousId.equals(task.taskId()) && !active.contains(previousId)) {
            ContentIndexingTask previous = outstanding.remove(previousId);
            if (previous != null) pending.remove(previous);
            cancellation.remove(previousId);
        }
        outstanding.put(task.taskId(), task);
        pending.offer(task);
        if (checkpoint) requestCheckpoint(task.collectionId());
    }

    public boolean cancel(String taskId) {
        if (taskId == null || taskId.isBlank()) return false;
        // Resolve membership before publishing the cancellation token. Otherwise an active
        // worker may observe the token, finish and remove the task between computeIfAbsent()
        // and outstanding.get(), making a successful cancellation spuriously report false.
        ContentIndexingTask task = outstanding.get(taskId);
        if (task == null) return false;
        AtomicBoolean token = cancellation.computeIfAbsent(taskId, ignored -> new AtomicBoolean());
        token.set(true);
        if (!active.contains(taskId)) {
            pending.remove(task);
            failedArtifactKeys.add(artifactKey(task));
            finishOutstanding(task, false, null);
            markFreshIfDrained(task.collectionId());
        }
        notifyListeners();
        return true;
    }

    public void pause() {
        paused.set(true);
        requestCheckpointAllCollections();
        notifyListeners();
    }

    public void resume() {
        paused.set(false);
        requestCheckpointAllCollections();
        notifyListeners();
        dispatchSafely();
    }

    /**
     * Removes queued work for the supplied books and asks already active work to stop. The method
     * waits only a bounded interval; false means callers must keep the collection stale and fall
     * back to a full rebuild instead of racing an older task.
     */
    public boolean supersedeBooks(String collectionId, Set<String> bookIds, long timeoutMillis) {
        if (collectionId == null || collectionId.isBlank() || bookIds == null || bookIds.isEmpty()) return true;
        Set<String> stableIds = bookIds.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (stableIds.isEmpty()) return true;
        for (ContentIndexingTask task : List.copyOf(outstanding.values())) {
            if (!collectionId.equals(task.collectionId()) || !stableIds.contains(task.bookId())) continue;
            supersedeTask(task);
        }
        return awaitNoActive(collectionId, stableIds, Math.max(0L, timeoutMillis));
    }

    public boolean supersedeCollection(String collectionId, long timeoutMillis) {
        if (collectionId == null || collectionId.isBlank()) return true;
        for (ContentIndexingTask task : List.copyOf(outstanding.values())) {
            if (collectionId.equals(task.collectionId())) supersedeTask(task);
        }
        return awaitNoActive(collectionId, null, Math.max(0L, timeoutMillis));
    }

    /**
     * Permanently forgets all background full-text state for a collection that is about to be deleted.
     * The caller must abort deletion when this method returns false: otherwise an active worker could
     * publish/checkpoint state after the collection's files have already been removed.
     */
    public boolean discardCollection(String collectionId, long timeoutMillis) {
        if (collectionId == null || collectionId.isBlank()) return true;
        String id = collectionId.trim();
        if (!supersedeCollection(id, timeoutMillis)) return false;
        synchronized (enqueueLock) {
            // supersedeCollection already removes pending work and waits for active work. Remove any
            // residual bookkeeping so a later collection reusing the same id cannot inherit state.
            pending.removeIf(task -> id.equals(task.collectionId()));
            outstanding.entrySet().removeIf(entry -> id.equals(entry.getValue().collectionId()));
            artifactTaskIds.entrySet().removeIf(entry -> entry.getKey().startsWith(id + "\u0000"));
            failedArtifactKeys.removeIf(key -> key.startsWith(id + "\u0000"));
            rebuildRequiredCollections.remove(id);
            staleHolds.remove(id);
            restoredCollections.remove(id);
        }
        checkpointPort.clear(id);
        notifyListeners();
        return true;
    }

    /**
     * Fail-closed barrier for replacing the authoritative SQLite database. Any queued/active
     * extraction is superseded and the full-text index stays durably STALE until a full rebuild.
     */
    public boolean quiesceForDatabaseReplacement(String collectionId, long timeoutMillis) {
        if (collectionId == null || collectionId.isBlank()) return true;
        String id = collectionId.trim();
        requireRebuild(id);
        return supersedeCollection(id, timeoutMillis);
    }

    private void supersedeTask(ContentIndexingTask task) {
        supersededTaskIds.add(task.taskId());
        AtomicBoolean token = cancellation.computeIfAbsent(task.taskId(), ignored -> new AtomicBoolean());
        token.set(true);
        if (!active.contains(task.taskId())) {
            pending.remove(task);
            finishOutstanding(task, true, null);
            cancellation.remove(task.taskId());
            supersededTaskIds.remove(task.taskId());
        }
    }

    private boolean awaitNoActive(String collectionId, Set<String> bookIds, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (hasMatchingActive(collectionId, bookIds)) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) return false;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
            if (Thread.currentThread().isInterrupted()) return false;
        }
        return true;
    }

    private boolean hasMatchingActive(String collectionId, Set<String> bookIds) {
        for (ContentIndexingTask task : activeTasks.values()) {
            if (task == null || !collectionId.equals(task.collectionId())) continue;
            if (bookIds == null || bookIds.contains(task.bookId())) return true;
        }
        return false;
    }

    /** Holds the stale state while an exact committed mutation supersedes older background work. */
    public void holdStale(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return;
        staleHolds.add(collectionId);
        contentIndex.markStale(collectionId);
    }

    public void releaseStaleHold(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return;
        staleHolds.remove(collectionId);
        markFreshIfDrained(collectionId);
    }

    /** A change set is too large/unknown for safe incremental repair; only a full rebuild may clear this state. */
    public void requireRebuild(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return;
        String id = collectionId.trim();
        rebuildRequiredCollections.add(id);
        contentIndex.markStale(id);
        requestCheckpoint(id);
    }

    /** Clears stale state only when no queue work/failures and no full rebuild requirement remain. */
    public void markFreshIfIdle(String collectionId) {
        markFreshIfDrained(collectionId);
    }

    public ContentIndexingQueueSnapshot snapshot() {
        IndexingPerformanceSettings settings = performanceSettings.load();
        boolean batteryPause = settings.pauseOnBattery() && safeOnBattery();
        return new ContentIndexingQueueSnapshot(paused.get(), batteryPause, settings.profile(),
                pending.size(), active.size(), completed.get(), failed.get(), lastError);
    }

    public AutoCloseable addListener(Consumer<ContentIndexingQueueSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        listener.accept(snapshot());
        return () -> listeners.remove(listener);
    }

    public List<ContentIndexingTask> outstandingTasks() {
        return outstanding.values().stream().sorted(ORDER).toList();
    }

    public int availableCapacity() {
        return Math.max(0, MAX_OUTSTANDING_TASKS - outstanding.size());
    }

    /** True when the collection still owns queued or active work in this process. */
    public boolean hasOutstandingForCollection(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return false;
        String id = collectionId.trim();
        return outstanding.values().stream().anyMatch(task -> id.equals(task.collectionId()));
    }

    private void dispatchSafely() {
        if (closed.get() || paused.get()) return;
        try {
            IndexingPerformanceSettings settings = performanceSettings.load();
            if (settings.pauseOnBattery() && safeOnBattery()) {
                notifyListeners();
                return;
            }
            int limit = settings.workerThreads(processors);
            while (!closed.get() && !paused.get() && active.size() < limit) {
                ContentIndexingTask task = pending.poll();
                if (task == null) break;
                if (!outstanding.containsKey(task.taskId())) continue;
                if (!active.add(task.taskId())) continue;
                activeTasks.put(task.taskId(), task);
                AtomicBoolean token = cancellation.computeIfAbsent(task.taskId(), ignored -> new AtomicBoolean(false));
                requestCheckpoint(task.collectionId());
                workers.submit(() -> processOne(task, token, settings));
            }
            notifyListeners();
        } catch (RuntimeException failure) {
            lastError = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            notifyListeners();
        }
    }

    private void processOne(ContentIndexingTask task, AtomicBoolean token, IndexingPerformanceSettings settings) {
        try {
            ContentIndexingControl control = new ContentIndexingControl(token::get,
                    progress -> notifyListeners(), settings.ioBytesPerSecond());
            ContentIndexingOutcome outcome = processor.process(task, control);
            if (outcome == null) outcome = ContentIndexingOutcome.failed("processor returned null outcome");
            switch (outcome.status()) {
                case COMPLETED -> {
                    completed.incrementAndGet();
                    failedArtifactKeys.remove(artifactKey(task));
                    finishOutstanding(task, true, null);
                }
                case CANCELLED -> {
                    boolean superseded = supersededTaskIds.remove(task.taskId());
                    if (!superseded) failedArtifactKeys.add(artifactKey(task));
                    finishOutstanding(task, superseded, null);
                }
                case FAILED -> {
                    boolean superseded = supersededTaskIds.remove(task.taskId()) && token.get();
                    if (!superseded) {
                        failed.incrementAndGet();
                        failedArtifactKeys.add(artifactKey(task));
                        lastError = outcome.message();
                    }
                    finishOutstanding(task, superseded, superseded ? null : outcome.message());
                }
            }
        } catch (RuntimeException failure) {
            boolean superseded = supersededTaskIds.remove(task.taskId()) && token.get();
            if (!superseded) {
                failed.incrementAndGet();
                failedArtifactKeys.add(artifactKey(task));
                lastError = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            }
            finishOutstanding(task, superseded, superseded ? null : lastError);
        } finally {
            supersededTaskIds.remove(task.taskId());
            cancellation.remove(task.taskId());
            // Keep the task visible as active until all derived-state finalization is complete.
            // supersedeCollection()/discardCollection() use active as their quiescence barrier;
            // removing it earlier would let SWITCH/DELETE proceed while this worker can still
            // publish freshness/checkpoint state for the old collection.
            markFreshIfDrained(task.collectionId());
            requestCheckpoint(task.collectionId());
            activeTasks.remove(task.taskId());
            active.remove(task.taskId());
            notifyListeners();
            dispatchSafely();
        }
    }

    private void finishOutstanding(ContentIndexingTask task, boolean successful, String error) {
        outstanding.remove(task.taskId());
        artifactTaskIds.remove(artifactKey(task), task.taskId());
        if (!successful && error != null && !error.isBlank()) lastError = error;
        requestCheckpoint(task.collectionId());
    }

    private void requestCheckpoint(String collectionId) {
        if (collectionId == null || collectionId.isBlank() || closed.get()) return;
        try {
            checkpointExecutor.execute(() -> {
                try { checkpointCollection(collectionId); }
                catch (RuntimeException failure) {
                    lastError = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                    notifyListeners();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Shutdown path performs a final synchronous checkpoint.
        }
    }

    private void requestCheckpointAllCollections() {
        outstanding.values().stream().map(ContentIndexingTask::collectionId).distinct().forEach(this::requestCheckpoint);
    }

    private void checkpointAllCollections() {
        outstanding.values().stream().map(ContentIndexingTask::collectionId).distinct().forEach(this::checkpointCollection);
    }

    private void checkpointCollection(String collectionId) {
        List<ContentIndexingTask> tasks = outstanding.values().stream()
                .filter(task -> task.collectionId().equals(collectionId))
                .sorted(ORDER)
                .toList();
        boolean rebuildRequired = rebuildRequiredCollections.contains(collectionId);
        if (tasks.isEmpty() && !rebuildRequired) checkpointPort.clear(collectionId);
        else checkpointPort.save(new ContentIndexQueueCheckpoint(collectionId, paused.get(), rebuildRequired, tasks));
    }

    private boolean safeOnBattery() {
        try { return powerState.onBatteryPower(); }
        catch (RuntimeException ignored) { return true; }
    }

    private void notifyListeners() {
        ContentIndexingQueueSnapshot snapshot = snapshot();
        for (Consumer<ContentIndexingQueueSnapshot> listener : listeners) {
            try { listener.accept(snapshot); } catch (RuntimeException ignored) { }
        }
    }

    private void prepareCollectionForWork(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return;
        boolean hasOutstanding = outstanding.values().stream().anyMatch(task -> collectionId.equals(task.collectionId()));
        if (hasOutstanding) return;
        try {
            if (contentIndex.health(collectionId).state() == com.myhomelibcorp.application.content.index.ContentIndexHealth.State.READY) {
                failedArtifactKeys.removeIf(key -> key.startsWith(collectionId + "\u0000"));
            }
        } catch (RuntimeException ignored) {
            // Health probing must never block a newly committed catalogue change from becoming stale.
        }
    }

    /** Clears remembered queue failures after a successful authoritative full rebuild. */
    public void acknowledgeFresh(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return;
        String id = collectionId.trim();
        failedArtifactKeys.removeIf(key -> key.startsWith(id + "\u0000"));
        rebuildRequiredCollections.remove(id);
        staleHolds.remove(id);
        // Durable queue/rebuild state must be cleared before the public freshness marker.
        // Otherwise a crash can resurrect already-completed work on the next start.
        checkpointCollection(id);
        contentIndex.markFresh(id);
    }

    private void markFreshIfDrained(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return;
        boolean hasOutstanding = outstanding.values().stream().anyMatch(task -> collectionId.equals(task.collectionId()));
        boolean hasFailure = failedArtifactKeys.stream().anyMatch(key -> key.startsWith(collectionId + "\u0000"));
        boolean rebuildRequired = rebuildRequiredCollections.contains(collectionId);
        boolean heldStale = staleHolds.contains(collectionId);
        if (!hasOutstanding && !hasFailure && !rebuildRequired && !heldStale) {
            // Seal the durable queue state before exposing the index as fresh.
            checkpointCollection(collectionId);
            contentIndex.markFresh(collectionId);
        }
    }

    private static String artifactKey(ContentIndexingTask task) {
        return task.collectionId() + "\u0000" + task.artifactId();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Content indexing queue is closed");
    }

    @Override
    public void destroy() {
        if (!closed.compareAndSet(false, true)) return;
        coordinator.shutdownNow();

        // No new checkpoint requests are accepted once closed=true. Drain already queued
        // checkpoint writes before taking the final synchronous snapshot; otherwise an older
        // async write can race after the final save and lose a just-enqueued task on restart.
        checkpointExecutor.shutdown();
        try {
            if (!checkpointExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                checkpointExecutor.shutdownNow();
                checkpointExecutor.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            checkpointExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        checkpointAllCollections();

        // Active work remains in the final outstanding snapshot and is therefore re-queued on
        // the next start. Interrupt workers only after that durable snapshot has been written.
        workers.shutdownNow();
    }

    private enum ContentIndexPortNoop implements ContentIndexPort {
        INSTANCE;
        @Override public void replaceArtifact(String collectionId, com.myhomelibcorp.application.content.index.ContentIndexEntry entry) { }
        @Override public void deleteArtifact(String collectionId, String artifactId) { }
        @Override public void deleteBook(String collectionId, String bookId) { }
        @Override public void rebuild(String collectionId, Iterable<com.myhomelibcorp.application.content.index.ContentIndexEntry> entries) { }
        @Override public com.myhomelibcorp.application.content.index.ContentIndexPage search(com.myhomelibcorp.application.content.index.ContentIndexQuery query) {
            return new com.myhomelibcorp.application.content.index.ContentIndexPage(List.of(), 0, query.offset(), query.limit());
        }
        @Override public com.myhomelibcorp.application.content.index.ContentIndexHealth health(String collectionId) {
            return new com.myhomelibcorp.application.content.index.ContentIndexHealth(collectionId, 1, 0, 0, true,
                    com.myhomelibcorp.application.content.index.ContentIndexHealth.State.EMPTY, "");
        }
    }
}
