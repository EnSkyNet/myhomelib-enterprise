package com.myhomelibcorp.reader.render.support;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PagedRenderCoordinatorTest {
    @Test
    void invalidateExpiresConfigurationAndSingleRenderTokens() {
        try (PagedRenderCoordinator coordinator = new PagedRenderCoordinator("paged-render-test")) {
            long configuration = coordinator.generation();
            var single = coordinator.beginSingleRender();

            assertTrue(coordinator.isCurrent(configuration));
            assertTrue(coordinator.isCurrent(single));

            coordinator.invalidate();

            assertFalse(coordinator.isCurrent(configuration));
            assertFalse(coordinator.isCurrent(single));
        }
    }

    @Test
    void replacingSingleRenderCancelsPreviousTask() throws Exception {
        try (PagedRenderCoordinator coordinator = new PagedRenderCoordinator("paged-render-test")) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<?> first = coordinator.submit(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            coordinator.trackSingle(first);
            assertTrue(started.await(2, TimeUnit.SECONDS));

            coordinator.beginSingleRender();

            assertTrue(first.isCancelled());
            release.countDown();
        }
    }

    @Test
    void cleanupRunsAfterEarlierWorkerTask() throws Exception {
        try (PagedRenderCoordinator coordinator = new PagedRenderCoordinator("paged-render-test")) {
            StringBuilder order = new StringBuilder();
            CountDownLatch complete = new CountDownLatch(1);
            coordinator.submit(() -> order.append('R'));

            assertTrue(coordinator.enqueueAfterPending(() -> {
                order.append('C');
                complete.countDown();
            }));
            assertTrue(complete.await(2, TimeUnit.SECONDS));
            assertEquals("RC", order.toString());
        }
    }
}
