package com.myhomelibcorp.ui.util;

import javafx.application.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class UiExecutor {

    private UiExecutor() {}

    /**
     * Виконує код на UI-потоку (Platform.runLater), якщо це необхідно.
     */
    public static void runOnUiThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    /**
     * Асинхронно виконує постачальника на UI-потоку та повертає CompletableFuture.
     */
    public static <T> CompletableFuture<T> supplyOnUiThread(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        };
        try {
            if (Platform.isFxApplicationThread()) {
                task.run();
            } else {
                Platform.runLater(task);
            }
        } catch (Throwable schedulingFailure) {
            future.completeExceptionally(schedulingFailure);
        }
        return future;
    }
}