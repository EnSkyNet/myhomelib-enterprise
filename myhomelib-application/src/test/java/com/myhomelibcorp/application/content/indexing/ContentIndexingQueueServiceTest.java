package com.myhomelibcorp.application.content.indexing;

import com.myhomelibcorp.application.content.index.ContentIndexEntry;
import com.myhomelibcorp.application.content.index.ContentIndexHealth;
import com.myhomelibcorp.application.content.index.ContentIndexPage;
import com.myhomelibcorp.application.content.index.ContentIndexQuery;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
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
import java.util.concurrent.CompletableFuture;
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
    void staleIndexWithoutCheckpointFailsClosedToPersistedFullRebuildRequirement() {
        MemoryCheckpoint checkpoint = new MemoryCheckpoint();
        MemoryContentIndex contentIndex = new MemoryContentIndex(ContentIndexHealth.State.STALE);
        MemorySettings settings = new MemorySettings();
        IndexingPerformanceSettingsService settingsService = new IndexingPerformanceSettingsService(settings);
        settingsService.save(new IndexingPerformanceSettings(IndexingResourceProfile.ECO, true));
        ContentIndexingQueueService queue = new ContentIndexingQueueService(
                (task, control) -> ContentIndexingOutcome.completed(), checkpoint, settingsService,
                () -> false, contentIndex, 2);
        services.add(queue);

        assertThat(queue.restore("c1")).isZero();
        assertThat(checkpoint.load("c1")).isPresent();
        assertThat(checkpoint.load("c1").orElseThrow().rebuildRequired()).isTrue();
        assertThat(checkpoint.load("c1").orElseThrow().tasks()).isEmpty();
    }

    @Test
    void nestedStaleHoldsCannotBeClearedByAnotherRebuildCompletion() {
        MemoryCheckpoint checkpoint = new MemoryCheckpoint();
        MemoryContentIndex contentIndex = new MemoryContentIndex(ContentIndexHealth.State.READY);
        MemorySettings settings = new MemorySettings();
        IndexingPerformanceSettingsService settingsService = new IndexingPerformanceSettingsService(settings);
        settingsService.save(new IndexingPerformanceSettings(IndexingResourceProfile.ECO, true));
        ContentIndexingQueueService queue = new ContentIndexingQueueService(
                (task, control) -> ContentIndexingOutcome.completed(), checkpoint, settingsService,
                () -> false, contentIndex, 2);
        services.add(queue);

        queue.holdStale("c1");          // authoritative full rebuild
        queue.holdStale("c1");          // concurrent committed download scheduling
        queue.acknowledgeFresh("c1");   // rebuild completed, but download scheduling still owns a hold

        assertThat(contentIndex.health("c1").state()).isEqualTo(ContentIndexHealth.State.STALE);
        queue.releaseStaleHold("c1");
        assertThat(contentIndex.health("c1").state()).isEqualTo(ContentIndexHealth.State.STALE);

        queue.releaseStaleHold("c1");
        assertThat(contentIndex.health("c1").state()).isEqualTo(ContentIndexHealth.State.READY);
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
    void discardWaitsUntilWorkerDerivedStateFinalizationFinishes() throws Exception {
        MemoryCheckpoint checkpoint = new MemoryCheckpoint();
        CountDownLatch enteredFresh = new CountDownLatch(1);
        CountDownLatch releaseFresh = new CountDownLatch(1);
        MemoryContentIndex contentIndex = new MemoryContentIndex(ContentIndexHealth.State.READY) {
            @Override public void markFresh(String collectionId) {
                enteredFresh.countDown();
                try { releaseFresh.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                super.markFresh(collectionId);
            }
        };
        MemorySettings settings = new MemorySettings();
        IndexingPerformanceSettingsService settingsService = new IndexingPerformanceSettingsService(settings);
        settingsService.save(new IndexingPerformanceSettings(IndexingResourceProfile.ECO, true));
        ContentIndexingQueueService queue = new ContentIndexingQueueService(
                (task, control) -> ContentIndexingOutcome.completed(), checkpoint, settingsService,
                () -> false, contentIndex, 2);
        services.add(queue);

        queue.enqueue(task("finalizing", ContentIndexingPriority.NORMAL));
        assertThat(enteredFresh.await(2, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> discarded = CompletableFuture.supplyAsync(
                () -> queue.discardCollection("c1", 2_000L));
        TimeUnit.MILLISECONDS.sleep(100);
        assertThat(discarded.isDone()).isFalse();

        releaseFresh.countDown();
        assertThat(discarded.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(checkpoint.load("c1")).isEmpty();
        assertThat(queue.hasOutstandingForCollection("c1")).isFalse();
    }

    @Test
    void idleQueueDoesNotProbePowerStateOrStartWorkScheduler() {
        AtomicInteger probes = new AtomicInteger();
        ContentIndexingQueueService queue = queue((task, control) -> ContentIndexingOutcome.completed(),
                new MemoryCheckpoint(), () -> { probes.incrementAndGet(); return false; },
                IndexingResourceProfile.BALANCED, 4);

        ContentIndexingQueueSnapshot snapshot = queue.snapshot();

        assertThat(snapshot.pending()).isZero();
        assertThat(snapshot.active()).isZero();
        assertThat(snapshot.pausedForBattery()).isFalse();
        assertThat(probes.get()).isZero();
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

    static class MemoryContentIndex implements ContentIndexPort {
        private volatile ContentIndexHealth.State state;
        MemoryContentIndex(ContentIndexHealth.State state) { this.state = state; }
        @Override public void replaceArtifact(String collectionId, ContentIndexEntry entry) { }
        @Override public void deleteArtifact(String collectionId, String artifactId) { }
        @Override public void deleteBook(String collectionId, String bookId) { }
        @Override public void rebuild(String collectionId, Iterable<ContentIndexEntry> entries) { state = ContentIndexHealth.State.READY; }
        @Override public ContentIndexPage search(ContentIndexQuery query) { return new ContentIndexPage(List.of(), 0, query.offset(), query.limit()); }
        @Override public ContentIndexHealth health(String collectionId) {
            return new ContentIndexHealth(collectionId, 1, 0, 0, true, state, "");
        }
        @Override public void markStale(String collectionId) { state = ContentIndexHealth.State.STALE; }
        @Override public void markFresh(String collectionId) { state = ContentIndexHealth.State.READY; }
    }

    static final class MemoryCheckpoint implements ContentIndexQueueCheckpointPort {
        final Map<String, ContentIndexQueueCheckpoint> states = new ConcurrentHashMap<>();
        @Override public Optional<ContentIndexQueueCheckpoint> load(String collectionId) { return Optional.ofNullable(states.get(collectionId)); }
        @Override public void save(ContentIndexQueueCheckpoint checkpoint) { states.put(checkpoint.collectionId(), checkpoint); }
        @Override public void clear(String collectionId) { states.remove(collectionId); }
    }
}
