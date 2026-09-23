package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionDatabaseOwnershipPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Metadata-DB backed ownership flag. NULL is preserved as UNKNOWN for safe legacy handling. */
@Component
public class SqliteCollectionDatabaseOwnershipAdapter implements CollectionDatabaseOwnershipPort {
    private final JdbcTemplate jdbc;

    public SqliteCollectionDatabaseOwnershipAdapter(@Qualifier("metadataJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Ownership findOwnership(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) return Ownership.UNKNOWN;
        return jdbc.query("SELECT owns_database FROM collections WHERE id = ? LIMIT 1", rs -> {
            if (!rs.next()) return Ownership.UNKNOWN;
            int value = rs.getInt(1);
            if (rs.wasNull()) return Ownership.UNKNOWN;
            return value == 1 ? Ownership.OWNED : value == 0 ? Ownership.EXTERNAL : Ownership.UNKNOWN;
        }, collectionId);
    }

    @Override
    public void setOwnership(String collectionId, Ownership ownership) {
        if (collectionId == null || collectionId.isBlank()) {
            throw new IllegalArgumentException("collectionId must not be blank");
        }
        if (ownership == null || ownership == Ownership.UNKNOWN) {
            throw new IllegalArgumentException("Explicit ownership must be OWNED or EXTERNAL");
        }
        int updated = jdbc.update("UPDATE collections SET owns_database = ? WHERE id = ?",
                ownership == Ownership.OWNED ? 1 : 0, collectionId);
        if (updated != 1) {
            throw new IllegalStateException("Cannot persist database ownership for collection " + collectionId);
        }
    }
}
