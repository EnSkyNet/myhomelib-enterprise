package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Shared bounded SQLite IN-clause batching below conservative bind-variable limits. */
public final class SqliteInClauseSupport {
    public static final int MAX_ITEMS = 400;

    private SqliteInClauseSupport() { }

    public static String placeholders(int size) {
        if (size <= 0 || size > MAX_ITEMS) {
            throw new IllegalArgumentException("SQLite IN batch size must be 1.." + MAX_ITEMS + ": " + size);
        }
        return String.join(",", Collections.nCopies(size, "?"));
    }

    public static <T> void forEachChunk(List<T> items, Consumer<List<T>> consumer) {
        if (items == null || items.isEmpty()) return;
        for (int from = 0; from < items.size(); from += MAX_ITEMS) {
            consumer.accept(items.subList(from, Math.min(items.size(), from + MAX_ITEMS)));
        }
    }
}
