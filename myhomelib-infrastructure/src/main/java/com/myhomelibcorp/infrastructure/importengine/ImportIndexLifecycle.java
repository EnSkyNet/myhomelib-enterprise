package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures and restores the exact live definitions of secondary indexes temporarily suspended
 * for a full INPX snapshot. The import pipeline only orchestrates the token lifecycle.
 */
@Component
@RequiredArgsConstructor
final class ImportIndexLifecycle {
    private static final List<String> BULK_IMPORT_SUSPEND_INDEXES = List.of(
            "idx_books_title",
            "idx_books_series",
            "idx_authors_last_name"
    );

    private final CollectionManager collectionManager;

    SuspendedIndexes suspendForFullSnapshot() {
        if (!collectionManager.hasActiveCollection()) return SuspendedIndexes.empty();
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        List<IndexDefinition> definitions = new ArrayList<>();
        for (String name : BULK_IMPORT_SUSPEND_INDEXES) {
            definitions.addAll(jdbc.query(
                    "SELECT name, sql FROM sqlite_master WHERE type='index' AND name=? AND sql IS NOT NULL",
                    (rs, rowNum) -> new IndexDefinition(rs.getString(1), rs.getString(2)),
                    name));
        }
        try {
            for (IndexDefinition definition : definitions) {
                jdbc.execute("DROP INDEX IF EXISTS " + quoteIdentifier(definition.name()));
            }
            return new SuspendedIndexes(List.copyOf(definitions));
        } catch (RuntimeException dropFailure) {
            try {
                restore(new SuspendedIndexes(definitions));
            } catch (RuntimeException restoreFailure) {
                dropFailure.addSuppressed(restoreFailure);
            }
            throw dropFailure;
        }
    }

    void restore(SuspendedIndexes suspended) {
        if (suspended == null || suspended.definitions().isEmpty() || !collectionManager.hasActiveCollection()) return;
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        RuntimeException firstFailure = null;
        for (IndexDefinition definition : suspended.definitions()) {
            try {
                Integer present = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?",
                        Integer.class, definition.name());
                if (present == null || present == 0) jdbc.execute(definition.createSql());
            } catch (RuntimeException e) {
                if (firstFailure == null) firstFailure = e;
                else firstFailure.addSuppressed(e);
            }
        }
        if (firstFailure != null) {
            throw new IllegalStateException("Failed to restore one or more import indexes", firstFailure);
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    record SuspendedIndexes(List<IndexDefinition> definitions) {
        SuspendedIndexes {
            definitions = definitions == null ? List.of() : List.copyOf(definitions);
        }
        static SuspendedIndexes empty() { return new SuspendedIndexes(List.of()); }
    }

    private record IndexDefinition(String name, String createSql) { }
}
