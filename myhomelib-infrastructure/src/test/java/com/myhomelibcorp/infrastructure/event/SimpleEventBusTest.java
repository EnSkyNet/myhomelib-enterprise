package com.myhomelibcorp.infrastructure.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleEventBusTest {

    @Test
    void registerPublishUnregisterIsIdentitySafeAndIdempotent() {
        SimpleEventBus bus = new SimpleEventBus();
        AtomicInteger calls = new AtomicInteger();
        Consumer<String> listener = ignored -> calls.incrementAndGet();

        bus.register(String.class, listener);
        bus.publish("one");
        assertThat(calls).hasValue(1);
        assertThat(bus.registrationCount(String.class)).isEqualTo(1);

        bus.unregister(String.class, listener);
        bus.unregister(String.class, listener);
        bus.publish("two");

        assertThat(calls).hasValue(1);
        assertThat(bus.registrationCount(String.class)).isZero();
    }

    @Test
    void concurrentRegisterUnregisterAndPublishDoesNotThrowOrLoseBusIntegrity() throws Exception {
        SimpleEventBus bus = new SimpleEventBus();
        AtomicInteger calls = new AtomicInteger();
        int workers = 8;
        int rounds = 500;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Consumer<Integer>> listeners = new ArrayList<>();
        for (int i = 0; i < workers; i++) listeners.add(ignored -> calls.incrementAndGet());

        try {
            for (int i = 0; i < workers; i++) {
                Consumer<Integer> listener = listeners.get(i);
                pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        bus.register(Integer.class, listener);
                        bus.publish(round);
                        bus.unregister(Integer.class, listener);
                        bus.publish(round);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(bus.registrationCount(Integer.class)).isZero();
        assertThat(calls.get()).isGreaterThan(0);
    }
}
