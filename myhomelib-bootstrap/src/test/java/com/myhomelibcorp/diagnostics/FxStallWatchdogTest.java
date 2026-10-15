package com.myhomelibcorp.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class FxStallWatchdogTest {
    @Test
    void snapshotIncludesStackOfLiveBackgroundThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "myhomelib-diagnostic-test");
        worker.start();
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            String snapshot = FxStallWatchdog.snapshot();
            assertThat(snapshot).contains("myhomelib-diagnostic-test").contains("state=");
        } finally {
            release.countDown();
            worker.join(2000);
        }
    }
}
