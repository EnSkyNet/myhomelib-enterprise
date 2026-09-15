package com.myhomelibcorp.application.content.indexing;

import com.myhomelibcorp.application.port.out.contentindexing.ContentIndexQueueCheckpointPort;
import com.myhomelibcorp.application.port.out.contentindexing.ContentIndexingTaskProcessor;
import com.myhomelibcorp.application.port.out.contentindexing.PowerStatePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ContentIndexingQueueServiceTest {
    private final List<ContentIndexingQueueService> services = new ArrayList<>();

    @AfterEach void closeServices() { services.forEach(ContentIndexingQueueService::destroy); }

    @Test
    void priorityQueueRunsOffCallerThreadAndHonoursHighNormalLowOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(3);
        ContentIndexingQueueService queue = queue((task, control) -> {
            order.add(task.taskId());
            completed.countDown();
            return ContentIndexingOutcome.completed();
        }, new MemoryCheckpoint(), () -> false, IndexingResourceProfile.ECO, 8);
        queue.pause();

        long started = System.nanoTime();
        queue.enqueue(task("low", ContentIndexingPriority.LOW));
        queue.enqueue(task("high", ContentIndexingPriority.HIGH));
        queue.enqueue(task("normal", ContentIndexingPriority.NORMAL));
        long enqueueMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(enqueueMillis).isLessThan(100L);

        queue.resume();
        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly("high", "normal", "low");
    }

    @Test
    void checkpointRestoresUnfinishedTasksAfterRestart() throws Exception {
        MemoryCheckpoint checkpoint = new MemoryCheckpoint();
        ContentIndexingQueueService first = queue((task, control) -> ContentIndexingOutcome.completed(), checkpoint,
                () -> false, IndexingResourceProfile.ECO, 4);
        first.pause();
        first.enqueue(task("one", ContentIndexingPriority.NORMAL));
        first.enqueue(task("two", ContentIndexingPriority.NORMAL));
        first.destroy();

        List<String> processed = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        ContentIndexingQueueService second = queue((task, control) -> {
            processed.add(task.taskId()); done.countDown(); return ContentIndexingOutcome.completed();
        }, checkpoint, () -> false, IndexingResourceProfile.ECO, 4);
        assertThat(second.restore("c1")).isEqualTo(2);
        assertThat(second.snapshot().paused()).isTrue();
        second.resume();
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        await(() -> second.outstandingTasks().isEmpty());
        assertThat(processed).containsExactlyInAnyOrder("one", "two");
        await(() -> checkpoint.load("c1").isEmpty());
    }

    @Test
    void cancelSignalsActiveProcessorAndRemovesTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch observedCancel = new CountDownLatch(1);
        ContentIndexingQueueService queue = queue((task, control) -> {
            started.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!control.isCancelled() && !Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            if (control.isCancelled()) observedCancel.countDown();
            return ContentIndexingOutcome.cancelled();
        }, new MemoryCheckpoint(), () -> false, IndexingResourceProfile.ECO, 4);
        queue.enqueue(task("cancel-me", ContentIndexingPriority.NORMAL));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.cancel("cancel-me")).isTrue();
        assertThat(observedCancel.await(2, TimeUnit.SECONDS)).isTrue();
        await(() -> queue.outstandingTasks().isEmpty());
    }

    @Test
    void powerProbeFailurePausesWorkConservatively() throws Exception {
        AtomicInteger processed = new AtomicInteger();
        ContentIndexingQueueService queue = queue((task, control) -> {
            processed.incrementAndGet();
            return ContentIndexingOutcome.completed();
        }, new MemoryCheckpoint(), () -> { throw new IllegalStateException("power probe unavailable"); },
                IndexingResourceProfile.BALANCED, 4);

        queue.enqueue(task("probe-failure", ContentIndexingPriority.NORMAL));
        TimeUnit.MILLISECONDS.sleep(200);

        assertThat(processed.get()).isZero();
        assertThat(queue.snapshot().pausedForBattery()).isTrue();
        assertThat(queue.outstandingTasks()).hasSize(1);
    }

    @Test
    void batteryPauseAndBalancedWorkerLimitAreEnforced() throws Exception {
        AtomicBoolean battery = new AtomicBoolean(true);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch fourStarted = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        ContentIndexingQueueService queue = queue((task, control) -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            fourStarted.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            active.decrementAndGet();
            return ContentIndexingOutcome.completed();
        }, new MemoryCheckpoint(), battery::get, IndexingResourceProfile.BALANCED, 8);
        for (int i = 0; i < 8; i++) queue.enqueue(task("t" + i, ContentIndexingPriority.NORMAL));
        Thread.onSpinWait();
        TimeUnit.MILLISECONDS.sleep(150);
        assertThat(maxActive.get()).isZero();

        battery.set(false);
        assertThat(fourStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(maxActive.get()).isEqualTo(4);
        release.countDown();
    }

    private ContentIndexingQueueService queue(ContentIndexingTaskProcessor processor, MemoryCheckpoint checkpoint,
                                               PowerStatePort power, IndexingResourceProfile profile, int processors) {
        MemorySettings settings = new MemorySettings();
        IndexingPerformanceSettingsService settingsService = new IndexingPerformanceSettingsService(settings);
        settingsService.save(new IndexingPerformanceSettings(profile, profile.defaultPauseOnBattery()));
        ContentIndexingQueueService queue = new ContentIndexingQueueService(
                processor, checkpoint, settingsService, power, processors);
        services.add(queue);
        return queue;
    }

    private ContentIndexingTask task(String id, ContentIndexingPriority priority) {
        return new ContentIndexingTask(id, "c1", "book-" + id, "artifact-" + id,
                "/tmp/" + id + ".txt", "txt", priority, Instant.parse("2026-09-12T12:00:00Z"));
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    static final class MemorySettings implements ApplicationSettingsPort {
        final Map<String, String> values = new ConcurrentHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new ConcurrentHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }

    static final class MemoryCheckpoint implements ContentIndexQueueCheckpointPort {
        final Map<String, ContentIndexQueueCheckpoint> states = new ConcurrentHashMap<>();
        @Override public Optional<ContentIndexQueueCheckpoint> load(String collectionId) { return Optional.ofNullable(states.get(collectionId)); }
        @Override public void save(ContentIndexQueueCheckpoint checkpoint) { states.put(checkpoint.collectionId(), checkpoint); }
        @Override public void clear(String collectionId) { states.remove(collectionId); }
    }
}
