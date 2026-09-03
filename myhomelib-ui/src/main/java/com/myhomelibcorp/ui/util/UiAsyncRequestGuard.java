package com.myhomelibcorp.ui.util;

import com.myhomelibcorp.ui.viewmodel.ApplicationState;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prevents late async completions from mutating JavaFX state after a newer request
 * or an active-collection switch. Controllers keep their own generation counter;
 * this helper standardizes the collection check.
 */
public final class UiAsyncRequestGuard {
    private UiAsyncRequestGuard() { }

    public static UiAsyncRequestToken next(AtomicLong generation, ApplicationState state) {
        Objects.requireNonNull(generation, "generation");
        return new UiAsyncRequestToken(generation.incrementAndGet(), currentCollectionId(state));
    }

    public static UiAsyncRequestToken snapshot(AtomicLong generation, ApplicationState state) {
        Objects.requireNonNull(generation, "generation");
        return new UiAsyncRequestToken(generation.get(), currentCollectionId(state));
    }

    public static boolean isCurrent(UiAsyncRequestToken token, AtomicLong generation, ApplicationState state) {
        return token != null
                && token.requestId() == generation.get()
                && Objects.equals(token.collectionId(), currentCollectionId(state));
    }

    public static long invalidate(AtomicLong generation) {
        return Objects.requireNonNull(generation, "generation").incrementAndGet();
    }

    public static String currentCollectionId(ApplicationState state) {
        return state == null ? null : state.getCurrentLibraryCollectionId();
    }
}
