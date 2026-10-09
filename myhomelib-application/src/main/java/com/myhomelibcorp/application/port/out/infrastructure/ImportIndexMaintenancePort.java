package com.myhomelibcorp.application.port.out.infrastructure;

/**
 * Maintains secondary SQLite indexes intentionally deferred by a very large initial catalog import.
 * Implementations must keep enough durable state to recover an interrupted deferred rebuild on restart.
 */
public interface ImportIndexMaintenancePort {
    boolean hasPendingIndexes();
    void restorePendingIndexes();
}
