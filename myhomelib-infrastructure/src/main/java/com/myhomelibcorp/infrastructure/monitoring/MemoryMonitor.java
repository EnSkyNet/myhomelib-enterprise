package com.myhomelibcorp.infrastructure.monitoring;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class MemoryMonitor implements AutoCloseable {

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private ScheduledExecutorService scheduler;
    private boolean running;

    public synchronized void startMonitoring(long intervalMs) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("Memory monitor interval must be > 0 ms");
        }
        if (running) return;

        ScheduledExecutorService candidate = newScheduler();
        try {
            candidate.scheduleAtFixedRate(this::logMemoryUsage,
                    intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            scheduler = candidate;
            running = true;
        } catch (RuntimeException e) {
            candidate.shutdownNow();
            throw e;
        }
    }

    public synchronized void stopMonitoring() {
        running = false;
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current == null) return;

        current.shutdown();
        try {
            if (!current.awaitTermination(2, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public synchronized boolean isRunning() {
        return running;
    }

    private ScheduledExecutorService newScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "memory-monitor");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }

    private void logMemoryUsage() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
        log.debug("💾 Heap: used={} MB, max={} MB, committed={} MB | NonHeap: used={} MB",
                heap.getUsed() / 1024 / 1024,
                heap.getMax() / 1024 / 1024,
                heap.getCommitted() / 1024 / 1024,
                nonHeap.getUsed() / 1024 / 1024);
    }

    @Override
    @PreDestroy
    public void close() {
        stopMonitoring();
    }
}
