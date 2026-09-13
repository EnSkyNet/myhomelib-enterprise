package com.myhomelibcorp.application.content;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Cooperative cancellation and progress boundary shared by extractor implementations. */
public final class ContentExtractionContext {
    private final BooleanSupplier cancelled;
    private final Consumer<ContentExtractionProgress> progressConsumer;

    private ContentExtractionContext(BooleanSupplier cancelled, Consumer<ContentExtractionProgress> progressConsumer) {
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.progressConsumer = progressConsumer == null ? ignored -> { } : progressConsumer;
    }

    public static ContentExtractionContext create(
            AtomicBoolean cancelled,
            Consumer<ContentExtractionProgress> progressConsumer) {
        return new ContentExtractionContext(cancelled::get, progressConsumer);
    }

    public static ContentExtractionContext create(
            BooleanSupplier cancelled,
            Consumer<ContentExtractionProgress> progressConsumer) {
        return new ContentExtractionContext(cancelled, progressConsumer);
    }

    public static ContentExtractionContext none() {
        return new ContentExtractionContext(() -> false, ignored -> { });
    }

    public boolean isCancelled() {
        return cancelled.getAsBoolean();
    }

    public void throwIfCancelled() {
        if (cancelled.getAsBoolean()) throw new ContentExtractionCancelledException();
    }

    public void report(String phase, long completed, long total) {
        throwIfCancelled();
        progressConsumer.accept(new ContentExtractionProgress(phase, completed, total));
    }
}
