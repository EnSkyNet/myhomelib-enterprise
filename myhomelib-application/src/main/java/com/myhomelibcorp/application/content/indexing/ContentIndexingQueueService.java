package com.myhomelibcorp.application.content.indexing;

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
import java.util.function.Consumer;

/**
 * Persistent, restart-safe background queue for full-text content indexing.
 * Public control methods are non-blocking; file extraction/index mutation runs only on daemon workers.
 */
@Service
public class ContentIndexingQueueService implements DisposableBean {
    private static final Comparator<ContentIndexingTask> ORDER = Comparator
            .comparingInt((ContentIndexingTask task) -> task.priority().order())
            .thenComparing(ContentIndexingTask::enqueuedAt)
            .thenComparing(ContentIndexingTask::taskId);

    private final ContentIndexingTaskProcessor processor;
    private final ContentIndexQueueCheckpointPort checkpointPort;
    private final IndexingPerformanceSettingsService performanceSettings;
    private final PowerStatePort powerState;
    private final int processors;
    private final PriorityBlockingQueue<ContentIndexingTask> pending = new PriorityBlockingQueue<>(32, ORDER);
    private final Map<String, ContentIndexingTask> outstanding = new ConcurrentHashMap<>();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicBoolean> cancellation = new ConcurrentHashMap<>();
    private final Map<String, String> artifactTaskIds = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ContentIndexingQueueSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile String lastError = "";

    private final ScheduledExecutorService coordinator;
    private final ExecutorService workers;
    private final ExecutorService checkpointExecutor;

    @Autowired
    public ContentIndexingQueueService(ContentIndexingTaskProcessor processor,
                                       ContentIndexQueueCheckpointPort checkpointPort,
                                       IndexingPerformanceSettingsService performanceSettings,
                                       PowerStatePort powerState) {
        this(processor, checkpointPort, performanceSettings, powerState,
                Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    ContentIndexingQueueService(ContentIndexingTaskProcessor processor,
                                ContentIndexQueueCheckpointPort checkpointPort,
                                IndexingPerformanceSettingsService performanceSettings,
                                PowerStatePort powerState,
                                int processors) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.checkpointPort = Objects.requireNonNull(checkpointPort, "checkpointPort");
        this.performanceSettings = Objects.requireNonNull(performanceSettings, "performanceSettings");
        this.powerState = Objects.requireNonNull(powerState, "powerState");
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
        Optional<ContentIndexQueueCheckpoint> loaded = checkpointPort.load(collectionId);
        if (loaded.isEmpty()) return 0;
        ContentIndexQueueCheckpoint state = loaded.get();
        paused.set(state.paused());
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
        String key = artifactKey(task);
        String previousId = artifactTaskIds.put(key, task.taskId());
        if (previousId != null && !previousId.equals(task.taskId()) && !active.contains(previousId)) {
            ContentIndexingTask previous = outstanding.remove(previousId);
            if (previous != null) pending.remove(previous);
            cancellation.remove(previousId);
        }
        outstanding.put(task.taskId(), task);
        pending.offer(task);
        requestCheckpoint(task.collectionId());
        notifyListeners();
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
            finishOutstanding(task, false, null);
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
                    finishOutstanding(task, true, null);
                }
                case CANCELLED -> finishOutstanding(task, false, null);
                case FAILED -> {
                    failed.incrementAndGet();
                    lastError = outcome.message();
                    finishOutstanding(task, false, outcome.message());
                }
            }
        } catch (RuntimeException failure) {
            failed.incrementAndGet();
            lastError = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            finishOutstanding(task, false, lastError);
        } finally {
            active.remove(task.taskId());
            cancellation.remove(task.taskId());
            requestCheckpoint(task.collectionId());
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
        if (tasks.isEmpty()) checkpointPort.clear(collectionId);
        else checkpointPort.save(new ContentIndexQueueCheckpoint(collectionId, paused.get(), tasks));
    }

    private boolean safeOnBattery() {
        try { return powerState.onBatteryPower(); }
        catch (RuntimeException ignored) { return false; }
    }

    private void notifyListeners() {
        ContentIndexingQueueSnapshot snapshot = snapshot();
        for (Consumer<ContentIndexingQueueSnapshot> listener : listeners) {
            try { listener.accept(snapshot); } catch (RuntimeException ignored) { }
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
}
