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
    private static final List<String> FULL_SNAPSHOT_SUSPEND_INDEXES = List.of(
            "idx_books_title",
            "idx_books_series",
            "idx_authors_last_name"
    );

    /**
     * Additional non-unique indexes that are pure write amplification during the first import into
     * an empty catalog. They are intentionally not suspended for a refresh of an existing catalog:
     * rebuilding a large pre-existing index can cost more than maintaining it during UPSERTs.
     *
     * The exact author-name lookup index and keyword_books(book_id) stay live because the import
     * path can need them for safe author-cache fallback and idempotent relation replacement.
     */
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
            "idx_book_genres_genre_code"
    );

    private final CollectionManager collectionManager;

    SuspendedIndexes suspendForFullSnapshot(boolean initialBaseline) {
        if (!collectionManager.hasActiveCollection()) return SuspendedIndexes.empty();
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
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
