package com.myhomelibcorp.application.navigation;

import java.util.Objects;

/**
 * UI-agnostic navigation node returned by the application layer.
 *
 * @param mode navigation mode that owns the node
 * @param id stable domain identifier/code; empty only for synthetic nodes
 * @param label display label from catalogue data (or a neutral fallback for a synthetic node)
 * @param bookCount number of matching books when cheaply available, otherwise -1
 */
public record NavigationNodeDto(
        NavigationMode mode,
        String id,
        String label,
        long bookCount
) {
    public static final long UNKNOWN_COUNT = -1L;

    public NavigationNodeDto {
        Objects.requireNonNull(mode, "mode");
        id = id == null ? "" : id;
        label = label == null ? "" : label;
        if (bookCount < UNKNOWN_COUNT) {
            throw new IllegalArgumentException("bookCount must be >= -1");
        }
    }

    public static NavigationNodeDto of(NavigationMode mode, String id, String label) {
        return new NavigationNodeDto(mode, id, label, UNKNOWN_COUNT);
    }

    public boolean hasKnownBookCount() {
        return bookCount >= 0;
    }
}
