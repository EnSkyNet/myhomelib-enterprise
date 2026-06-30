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
        runOnUiThread(() -> {
            try {
                future.complete(supplier.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }
}