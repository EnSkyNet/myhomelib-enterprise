package com.myhomelibcorp.ui.organizer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Backpressure for high-frequency background progress (e.g. hundreds of thousands of files).
 * Keeps one newest value and at most one queued UI runnable, rather than a runnable per file.
 * Scheduler must execute accepted runnables eventually on the UI thread.
 */
final class LatestUiProgress<T> {
    private final Consumer<Runnable> scheduleOnUi;
    private final Consumer<T> updateUi;
    private final AtomicReference<T> newest = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();

    LatestUiProgress(Consumer<Runnable> scheduleOnUi, Consumer<T> updateUi) {
        this.scheduleOnUi = Objects.requireNonNull(scheduleOnUi);
        this.updateUi = Objects.requireNonNull(updateUi);
    }

    void offer(T value) {
        newest.set(Objects.requireNonNull(value));
        scheduleIfNeeded();
    }

    void clear() {
        newest.set(null);
    }

    private void scheduleIfNeeded() {
        if (scheduled.compareAndSet(false, true)) {
            try {
                scheduleOnUi.accept(this::flush);
            } catch (RuntimeException failure) {
                scheduled.set(false);
                throw failure;
            }
        }
    }

    private void flush() {
        try {
            T update = newest.getAndSet(null);
            if (update != null) updateUi.accept(update);
        } finally {
            scheduled.set(false);
            // If a writer raced with the dispatch, schedule another turn instead of
            // looping on the UI thread (and starving UI input under heavy work).
            if (newest.get() != null) scheduleIfNeeded();
        }
    }
}
