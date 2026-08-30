package com.myhomelibcorp.application.port.out.search;

import com.myhomelibcorp.domain.model.collection.Collection;

/** Lifecycle of the derived Lucene index bound to the active collection. */
public interface SearchIndexLifecycle {
    /**
     * Opens the index belonging to {@code collection}.
     * @return true only when the persisted index is proven reusable for the current database state.
     */
    boolean activateCollectionIndex(Collection collection);

    /**
     * Invalidates the freshness proof for the active derived index before synchronizing a
     * committed DB mutation. A dirty index must never be sealed as reusable on close.
     */
    void markCurrentIndexDirty();

    /**
     * Marks the active derived index synchronized after a successful selective update/full rebuild
     * and persists a new freshness proof for the current DB state.
     */
    void markCurrentIndexSynchronized();

    /** Commits/closes the currently active collection index before switching database context. */
    void closeCurrentIndex();

    /**
     * Seals the just-closed index after the old SQLite connection has been closed/checkpointed.
     * This avoids WAL checkpoint timestamp changes invalidating an otherwise clean index.
     */
    void sealClosedIndex(Collection collection);
}
