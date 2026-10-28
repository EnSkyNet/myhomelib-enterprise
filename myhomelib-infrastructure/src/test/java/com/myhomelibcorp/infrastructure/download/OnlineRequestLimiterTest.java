package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineRequestLimiterTest {

    @Test
    @Timeout(20)
    void waitingForBusyHostDoesNotConsumeGlobalSlotNeededByAnotherHost() throws Exception {
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.getInt("online.maxParallelDownloads", 2)).thenReturn(2);
        when(settings.getInt("online.maxParallelDownloadsPerHost", 2)).thenReturn(1);
        OnlineRequestLimiter limiter = new OnlineRequestLimiter(settings);

        URI hostA = URI.create("https://library-a.example/book");
        URI hostB = URI.create("https://library-b.example/book");
        Semaphore global = globalSlots(limiter);
        AtomicBoolean cancelWaiting = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);

        try (OnlineRequestLimiter.Permit first = limiter.acquire(hostA, null)) {
            Future<String> second = executor.submit(() -> {
                started.countDown();
                try (OnlineRequestLimiter.Permit ignored = limiter.acquire(hostA, cancelWaiting)) {
                    return "acquired";
                } catch (IOException cancelled) {
                    return "cancelled";
                }
            });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                // Both acquisition orders block the second A request on this
                // semaphore. Waiting here removes scheduler-order assumptions.
                Semaphore aSlots = hostSlots(limiter, "library-a.example");
                await(aSlots::hasQueuedThreads, Duration.ofSeconds(5));

                // Regression: with global-first acquisition this was ZERO,
                // starving unrelated host B despite two global slots.
                assertThat(global.availablePermits()).isEqualTo(1);
                try (OnlineRequestLimiter.Permit otherHost = limiter.acquire(hostB, null)) {
                    assertThat(global.availablePermits()).isZero();
                }
                assertThat(global.availablePermits()).isEqualTo(1);

                cancelWaiting.set(true);
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("cancelled");
                assertThat(global.availablePermits()).isEqualTo(1);
            } finally {
                cancelWaiting.set(true);
            }
        } finally {
            cancelWaiting.set(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(global.availablePermits()).isEqualTo(2);
        assertThat(hostGates(limiter)).isEmpty();
    }

    private static void await(BooleanSupplier condition, Duration duration) throws InterruptedException {
        long end = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for host semaphore contention");
    }

    private static Semaphore globalSlots(OnlineRequestLimiter limiter) throws Exception {
        Field field = OnlineRequestLimiter.class.getDeclaredField("global");
        field.setAccessible(true);
        return (Semaphore) field.get(limiter);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> hostGates(OnlineRequestLimiter limiter) throws Exception {
        Field field = OnlineRequestLimiter.class.getDeclaredField("byHost");
        field.setAccessible(true);
        return (Map<String, ?>) field.get(limiter);
    }

    private static Semaphore hostSlots(OnlineRequestLimiter limiter, String host) throws Exception {
        Object gate = hostGates(limiter).get(host);
        assertThat(gate).isNotNull();
        Field field = gate.getClass().getDeclaredField("semaphore");
        field.setAccessible(true);
        return (Semaphore) field.get(gate);
    }
}
