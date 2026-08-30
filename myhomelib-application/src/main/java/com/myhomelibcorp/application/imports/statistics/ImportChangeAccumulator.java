package com.myhomelibcorp.application.imports.statistics;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Bounded accumulator for stable book IDs affected by import.
 *
 * <p>Exact ID sets are retained only while the number of tracked IDs stays within the configured
 * limit. Once the limit is crossed the accumulator switches permanently to full-reindex mode and
 * releases the ID sets, while long counters continue to be maintained independently.</p>
 */
public final class ImportChangeAccumulator {
    public static final int DEFAULT_TRACKED_ID_LIMIT = 50_000;

    private final int trackedIdLimit;
    private final LinkedHashSet<String> inserted = new LinkedHashSet<>();
    private final LinkedHashSet<String> updated = new LinkedHashSet<>();
    private final LinkedHashSet<String> deleted = new LinkedHashSet<>();

    private long insertedCount;
    private long updatedCount;
    private long deletedCount;
    private boolean complete = true;

    public ImportChangeAccumulator(int trackedIdLimit) {
        this.trackedIdLimit = Math.max(0, trackedIdLimit);
        if (this.trackedIdLimit == 0) switchToFullReindex();
    }

    public static ImportChangeAccumulator withDefaultLimit() {
        return new ImportChangeAccumulator(DEFAULT_TRACKED_ID_LIMIT);
    }

    public static int normalizeLimit(int configuredLimit) {
        return configuredLimit > 0 ? configuredLimit : DEFAULT_TRACKED_ID_LIMIT;
    }

    public void recordInserted(String id) {
        if (id == null || id.isBlank()) return;
        if (!complete) {
            insertedCount++;
            return;
        }
        if (inserted.contains(id)) return;
        if (updated.remove(id)) updatedCount--;
        if (deleted.remove(id)) deletedCount--;
        inserted.add(id);
        insertedCount++;
        enforceLimit();
    }

    public void recordUpdated(String id) {
        if (id == null || id.isBlank()) return;
        if (!complete) {
            updatedCount++;
            return;
        }
        if (inserted.contains(id) || updated.contains(id)) return;
        if (deleted.remove(id)) deletedCount--;
        updated.add(id);
        updatedCount++;
        enforceLimit();
    }

    public void recordDeleted(String id) {
        if (id == null || id.isBlank()) return;
        if (!complete) {
            deletedCount++;
            return;
        }
        if (deleted.contains(id)) return;
        if (inserted.remove(id)) insertedCount--;
        if (updated.remove(id)) updatedCount--;
        deleted.add(id);
        deletedCount++;
        enforceLimit();
    }

    public void markUnchanged(String id) {
        if (!complete || id == null || id.isBlank()) return;
        if (updated.remove(id)) updatedCount--;
        if (deleted.remove(id)) deletedCount--;
    }

    /** Merge another import result without ever creating an unbounded union of IDs. */
    public void merge(ImportChangeSet other) {
        if (other == null) return;
        if (!complete || !other.complete()) {
            insertedCount += other.insertedCount();
            updatedCount += other.updatedCount();
            deletedCount += other.deletedCount();
            switchToFullReindex();
            return;
        }
        for (String id : other.inserted()) recordInserted(id);
        for (String id : other.updated()) recordUpdated(id);
        for (String id : other.deleted()) recordDeleted(id);
    }

    public boolean complete() {
        return complete;
    }

    public boolean fullReindexRequired() {
        return !complete;
    }

    public long insertedCount() {
        return insertedCount;
    }

    public long updatedCount() {
        return updatedCount;
    }

    public long deletedCount() {
        return deletedCount;
    }

    public long trackedIdCount() {
        return complete ? (long) inserted.size() + updated.size() + deleted.size() : 0L;
    }

    public boolean containsInserted(String id) {
        return complete && inserted.contains(id);
    }

    public ImportChangeSet snapshot() {
        return new ImportChangeSet(
                complete ? inserted : Set.of(),
                complete ? updated : Set.of(),
                complete ? deleted : Set.of(),
                complete,
                insertedCount,
                updatedCount,
                deletedCount);
    }

    private void enforceLimit() {
        if (complete && trackedIdCount() > trackedIdLimit) switchToFullReindex();
    }

    private void switchToFullReindex() {
        complete = false;
        inserted.clear();
        updated.clear();
        deleted.clear();
    }
}
