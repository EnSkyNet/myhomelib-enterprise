package com.myhomelibcorp.infrastructure.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class MemoryMonitor {

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public void startMonitoring(long intervalMs) {
        if (running.get()) return;
        running.set(true);
        scheduler.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
            log.debug("💾 Heap: used={} MB, max={} MB, committed={} MB | NonHeap: used={} MB",
                    heap.getUsed() / 1024 / 1024,
                    heap.getMax() / 1024 / 1024,
                    heap.getCommitted() / 1024 / 1024,
                    nonHeap.getUsed() / 1024 / 1024);
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void stopMonitoring() {
        running.set(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}