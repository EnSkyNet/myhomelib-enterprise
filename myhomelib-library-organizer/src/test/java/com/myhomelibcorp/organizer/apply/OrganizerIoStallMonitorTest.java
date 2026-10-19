package com.myhomelibcorp.organizer.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizerIoStallMonitorTest {
    @TempDir Path temp;

    @Test
    void blockedWorkerProducesBoundedNonDestructiveDiagnosticAndStopsAfterClose() throws Exception {
        UUID operationId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        List<String> warnings = new CopyOnWriteArrayList<>();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Path source = Files.writeString(temp.resolve("source.fb2"), "keep original");
        Thread worker = new Thread(() -> {
            try (OrganizerIoStallMonitor monitor = new OrganizerIoStallMonitor(operationId,
                    warnings::add, 80, 10)) {
                monitor.mark(itemId, "COPY_OPENED");
                waiting.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Worker not released");
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        }, "synthetic-blocked-file-worker");
        worker.start();
        try {
            assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (warnings.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(warnings).isNotEmpty();
            assertThat(warnings.getFirst()).contains("ORGANIZER_IO_STALL")
                    .contains("operationId=" + operationId)
                    .contains("itemId=" + itemId)
                    .contains("stage=COPY_OPENED")
                    .contains("workerStack=")
                    .contains("CountDownLatch");
        } finally {
            release.countDown();
            worker.join(3_000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(workerFailure.get()).isNull();
        assertThat(Files.readString(source)).isEqualTo("keep original");
        int warningCountAtClose = warnings.size();
        Thread.sleep(120);
        assertThat(warnings).hasSize(warningCountAtClose);
    }

    @Test
    void activeProgressDoesNotTriggerFalseStallAndSameByteCountDoesNotResetTimer() throws Exception {
        List<String> warnings = new CopyOnWriteArrayList<>();
        UUID id = UUID.randomUUID();
        try (OrganizerIoStallMonitor monitor = new OrganizerIoStallMonitor(id, warnings::add, 100, 10)) {
            for (int i = 0; i < 12; i++) {
                monitor.mark(id, "PREFLIGHT_HASH_BYTES", i * 65536L);
                Thread.sleep(12);
            }
            assertThat(warnings).isEmpty();
        }
        try (OrganizerIoStallMonitor monitor = new OrganizerIoStallMonitor(id, warnings::add, 60, 10)) {
            monitor.mark(id, "COPY_BYTES", 123);
            for (int i = 0; i < 12; i++) {
                monitor.mark(id, "COPY_BYTES", 123);
                Thread.sleep(12);
            }
            assertThat(warnings).anyMatch(s -> s.contains("stage=COPY_BYTES") && s.contains("bytes=123"));
        }
    }

    @Test
    void streamingShaReportsRealByteProgressAndPreservesChecksum() throws Exception {
        byte[] data = new byte[2 * 1024 * 1024 + 17];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        Path source = Files.write(temp.resolve("large.fb2"), data);
        List<Long> bytes = new java.util.ArrayList<>();
        String expected = OrganizerFileIntegritySupport.sha256(source);
        String calculated = OrganizerFileIntegritySupport.sha256(source, () -> false, bytes::add);
        assertThat(calculated).isEqualTo(expected);
        assertThat(bytes).isNotEmpty();
        assertThat(bytes.getFirst()).isPositive();
        assertThat(bytes.getLast()).isEqualTo(data.length);
        assertThat(bytes).isSorted();
    }
}
