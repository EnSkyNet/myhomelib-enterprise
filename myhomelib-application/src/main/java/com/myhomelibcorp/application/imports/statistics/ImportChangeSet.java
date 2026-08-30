package com.myhomelibcorp.application.imports.statistics;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Stable book IDs affected by an import, with counters independent of retained ID sets. */
public record ImportChangeSet(
        Set<String> inserted,
        Set<String> updated,
        Set<String> deleted,
        boolean complete,
        long insertedCount,
        long updatedCount,
        long deletedCount
) {
    public ImportChangeSet {
        inserted = immutable(inserted);
        updated = immutable(updated);
        deleted = immutable(deleted);
        insertedCount = Math.max(0L, insertedCount);
        updatedCount = Math.max(0L, updatedCount);
        deletedCount = Math.max(0L, deletedCount);
        if (!complete) {
            // An incomplete ID set must never be consumed as a selective-index instruction.
            inserted = Set.of();
            updated = Set.of();
            deleted = Set.of();
        }
    }

    /** Source-compatible constructor for callers that already hold complete exact sets. */
    public ImportChangeSet(Set<String> inserted, Set<String> updated, Set<String> deleted, boolean complete) {
        this(inserted, updated, deleted, complete,
                sizeOf(inserted), sizeOf(updated), sizeOf(deleted));
    }

    public static ImportChangeSet empty(boolean complete) {
        return new ImportChangeSet(Set.of(), Set.of(), Set.of(), complete, 0, 0, 0);
    }

    /**
     * Bounded merge using the default safety limit. Prefer an explicitly configured
     * {@link ImportChangeAccumulator} in long-running orchestration paths.
     */
    public ImportChangeSet merge(ImportChangeSet other) {
        if (other == null) return this;
        ImportChangeAccumulator accumulator = ImportChangeAccumulator.withDefaultLimit();
        accumulator.merge(this);
        accumulator.merge(other);
        return accumulator.snapshot();
    }

    private static long sizeOf(Set<String> source) {
        return source == null ? 0L : source.size();
    }

    private static Set<String> immutable(Set<String> source) {
        return source == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
