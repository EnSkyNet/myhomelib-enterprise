package com.myhomelibcorp.application.content.indexing;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ContentIndexingControl {
    private final BooleanSupplier cancelled;
    private final Consumer<ContentIndexingProgress> progress;
    private final long ioBytesPerSecond;

    public ContentIndexingControl(BooleanSupplier cancelled, Consumer<ContentIndexingProgress> progress, long ioBytesPerSecond) {
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.progress = progress == null ? ignored -> { } : progress;
        this.ioBytesPerSecond = Math.max(0L, ioBytesPerSecond);
    }
    public boolean isCancelled() { return cancelled.getAsBoolean(); }
    public long ioBytesPerSecond() { return ioBytesPerSecond; }
    public void report(ContentIndexingProgress value) { progress.accept(Objects.requireNonNull(value)); }
}
