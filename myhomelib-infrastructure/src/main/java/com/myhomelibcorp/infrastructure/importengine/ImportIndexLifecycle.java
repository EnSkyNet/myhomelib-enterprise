package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.port.out.infrastructure.ImportIndexMaintenancePort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures and restores the exact live definitions of secondary indexes temporarily suspended
 * for a full INPX snapshot. Deferred definitions are also persisted in SQLite so a crash can be
 * repaired on the next startup before the collection is exposed to the UI.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public final class ImportIndexLifecycle implements ImportIndexMaintenancePort {
    private static final String QUEUE_TABLE = "import_deferred_index_restore";
    private static final List<String> FULL_SNAPSHOT_SUSPEND_INDEXES = List.of(
            "idx_books_title",
            "idx_books_series",
            "idx_authors_last_name"
    );

    private static final List<String> INITIAL_BASELINE_EXTRA_SUSPEND_INDEXES = List.of(
            "idx_books_language",
            "idx_books_created_at",
            "idx_books_update_date",
            "idx_books_rate",
            "idx_books_title_lower",
            "idx_books_format",
            "idx_books_author_sort",
            "idx_books_collection_root",
            "idx_books_publisher",
            "idx_books_year",
            "idx_books_lib_id",
            "idx_books_library_rate",
            "idx_books_missing_since",
            "idx_books_active_language_title",
            "idx_books_active_id",
            "idx_book_authors_author_id",
            "idx_book_genres_genre_code",
            "idx_keyword_books_book_id",
            "idx_keyword_books_keyword_book",
            "idx_catalog_book_state_source",
            "idx_catalog_book_state_downloaded"
    );

    private final CollectionManager collectionManager;

    SuspendedIndexes suspendForFullSnapshot(boolean initialBaseline) {
        if (!collectionManager.hasActiveCollection()) return SuspendedIndexes.empty();
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        ensureQueueTable(jdbc);
        List<String> indexNames = new ArrayList<>(FULL_SNAPSHOT_SUSPEND_INDEXES);
        if (initialBaseline) indexNames.addAll(INITIAL_BASELINE_EXTRA_SUSPEND_INDEXES);
        List<IndexDefinition> definitions = new ArrayList<>();
        for (String name : indexNames) {
            definitions.addAll(jdbc.query(
                    "SELECT name, sql FROM sqlite_master WHERE type='index' AND name=? AND sql IS NOT NULL",
                    (rs, rowNum) -> new IndexDefinition(rs.getString(1), rs.getString(2)),
                    name));
        }
        try {
            // Persist exact live CREATE statements before DROP. This row set commits atomically with
            // the import, so a crash before commit rolls both catalog changes and dropped indexes back;
            // a crash after commit leaves a durable repair plan for startup recovery.
            if (!definitions.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO " + QUEUE_TABLE + "(index_name, create_sql) VALUES (?, ?) "
                                + "ON CONFLICT(index_name) DO UPDATE SET create_sql=excluded.create_sql",
                        definitions, 100, (ps, definition) -> {
                            ps.setString(1, definition.name());
                            ps.setString(2, definition.createSql());
                        });
            }
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
        restoreDefinitions(suspended.definitions());
    }

    @Override
    public boolean hasPendingIndexes() {
        if (!collectionManager.hasActiveCollection()) return false;
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        ensureQueueTable(jdbc);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + QUEUE_TABLE, Integer.class);
        return count != null && count > 0;
    }

    @Override
    public void restorePendingIndexes() {
        if (!collectionManager.hasActiveCollection()) return;
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        ensureQueueTable(jdbc);
        List<IndexDefinition> pending = jdbc.query(
                "SELECT index_name, create_sql FROM " + QUEUE_TABLE + " ORDER BY index_name",
                (rs, rowNum) -> new IndexDefinition(rs.getString(1), rs.getString(2)));
        if (pending.isEmpty()) return;
        log.info("Restoring {} deferred SQLite performance indexes", pending.size());
        restoreDefinitions(pending);
    }

    private void restoreDefinitions(List<IndexDefinition> definitions) {
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        ensureQueueTable(jdbc);
        RuntimeException firstFailure = null;
        for (IndexDefinition definition : definitions) {
            try {
                Integer present = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?",
                        Integer.class, definition.name());
                if (present == null || present == 0) jdbc.execute(definition.createSql());
                // Delete only after CREATE/verification succeeds, preserving crash recovery semantics.
                jdbc.update("DELETE FROM " + QUEUE_TABLE + " WHERE index_name=?", definition.name());
            } catch (RuntimeException e) {
                if (firstFailure == null) firstFailure = e;
                else firstFailure.addSuppressed(e);
            }
        }
        if (firstFailure != null) {
            throw new IllegalStateException("Failed to restore one or more import indexes", firstFailure);
        }
    }

    private static void ensureQueueTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + QUEUE_TABLE + " ("
                + "index_name TEXT PRIMARY KEY, create_sql TEXT NOT NULL, queued_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
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
