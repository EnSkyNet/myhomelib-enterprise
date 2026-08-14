package com.myhomelibcorp.reader.service;

import jakarta.annotation.PreDestroy;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ReaderScheduler {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            3,
            r -> {
                Thread t = new Thread(r, "reader-scheduler");
                t.setDaemon(true);
                return t;
            }
    );

    public void execute(Runnable task) {
        scheduler.execute(task);
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return scheduler.schedule(task, delay, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return scheduler.scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit) {
        return scheduler.scheduleWithFixedDelay(task, initialDelay, delay, unit);
    }

    /**
     * Виконує задачу на FX Application Thread.
     * Якщо поточний потік - FX, виконує негайно.
     * Інакше - через Platform.runLater().
     */
    public void runOnFxThread(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    public void cancel(ScheduledFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ReaderScheduler...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    log.warn("ReaderScheduler did not terminate gracefully");
                }
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("ReaderScheduler shut down");
    }
}