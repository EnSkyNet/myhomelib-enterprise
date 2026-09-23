package com.myhomelibcorp.application.port.out.infrastructure;

/**
 * Metadata boundary describing whether the SQLite database referenced by a collection is owned by
 * MyHomeLib or is an externally attached user file.
 *
 * <p>UNKNOWN is intentionally fail-safe: destructive collection deletion must preserve the database
 * unless ownership was explicitly established.</p>
 */
public interface CollectionDatabaseOwnershipPort {

    enum Ownership {
        OWNED,
        EXTERNAL,
        UNKNOWN
    }

    Ownership findOwnership(String collectionId);

    void setOwnership(String collectionId, Ownership ownership);
}
