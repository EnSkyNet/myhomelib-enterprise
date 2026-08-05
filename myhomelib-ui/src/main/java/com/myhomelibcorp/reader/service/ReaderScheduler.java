package com.myhomelibcorp.reader.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ReaderScheduler {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            1,
            r -> {
                Thread t = new Thread(r, "reader-scheduler");
                t.setDaemon(true);
                return t;
            }
    );

    public void execute(Runnable task) {
        scheduler.execute(task);
    }

    public void schedule(Runnable task, long delay, TimeUnit unit) {
        scheduler.schedule(task, delay, unit);
    }

    public void scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        scheduler.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("ReaderScheduler не завершив роботу примусово");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("ReaderScheduler завершено");
    }
}