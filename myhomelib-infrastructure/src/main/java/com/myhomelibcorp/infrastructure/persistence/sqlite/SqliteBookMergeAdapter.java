package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.duplicate.merge.BookMergeJournalEntry;
import com.myhomelibcorp.application.duplicate.merge.BookMergeMutationResult;
import com.myhomelibcorp.application.duplicate.merge.BookMergePlan;
import com.myhomelibcorp.application.port.out.duplicate.BookMergePort;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.cache.BookCache;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SQLite implementation of undoable logical-book merge.
 *
 * <p>The duplicate book row is soft-deleted rather than physically removed. Artifacts/bookmarks/identities are
 * reassigned to the survivor, relationship/user state is conservatively consolidated, and a durable journal stores
 * the exact pre-merge survivor state plus the keys required for inverse operations. No filesystem API is called.</p>
 */
@Component
@RequiredArgsConstructor
public class SqliteBookMergeAdapter implements BookMergePort {
    private static final List<String> BIBLIOGRAPHIC_COLUMNS = List.of(
            "title", "series", "sequence_number", "language", "keywords", "annotation", "isbn",
            "year", "publisher", "lib_id", "library_rate", "translators", "city", "source_url", "cover_hash");

    private final CollectionManager collections;
    private final ObjectMapper objectMapper;
    private final BookCache bookCache;

    private JdbcTemplate jdbc() {
        return collections.getCurrentJdbcTemplate();
    }

    private TransactionTemplate tx() {
        if (collections.getCurrentDataSource() == null) {
            throw new IllegalStateException("No active collection database");
        }
        return new TransactionTemplate(new DataSourceTransactionManager(collections.getCurrentDataSource()));
    }

    @Override
    public BookMergeMutationResult merge(BookMergePlan plan) {
        Objects.requireNonNull(plan, "plan");
        String survivor = plan.survivorBookId().asString();
        String merged = plan.mergedBookId().asString();
        String metadataSource = plan.metadataSourceBookId().asString();
        String mergeId = UUID.randomUUID().toString();
        Instant changedAt = Instant.now();

        BookMergeMutationResult result = tx().execute(status -> {
            Map<String, Object> survivorBook = requireActiveBook(survivor);
            Map<String, Object> mergedBook = requireActiveBook(merged);

            Snapshot snapshot = new Snapshot(
                    survivorBook,
                    mergedBook,
                    addedKeys("book_authors", "author_id", survivor, merged),
                    addedKeys("book_genres", "genre_code", survivor, merged),
                    addedKeys("book_groups", "group_id", survivor, merged),
                    addedKeys("keyword_books", "normalized_name", survivor, merged),
                    stringColumn("bookmarks", "id", merged),
                    stringColumn("book_artifacts", "artifact_id", merged),
                    identityKeys(merged),
                    singleRow("reading_progress", survivor),
                    singleRow("reading_history", survivor),
                    singleRow("reading_stats", survivor),
                    singleRow("reader_book_preferences", survivor),
                    singleRow("book_artifact_preferences", survivor),
                    singleRow("book_artifact_preferences", merged)
            );

            jdbc().update("""
                    INSERT INTO book_merge_journal(
                        merge_id, survivor_book_id, merged_book_id, metadata_source_book_id,
                        snapshot_json, merged_at, undone_at
                    ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, NULL)
                    """, mergeId, survivor, merged, metadataSource, encode(snapshot));

            jdbc().update("""
                    INSERT INTO operation_history(
                        operation_id, operation_type, summary, affected_count, changed_count,
                        rules_json, created_at, completed_at, undone_at
                    ) VALUES (?, 'BOOK_MERGE', ?, 2, 2, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL)
                    """, mergeId, "Book merge: " + survivor + " <- " + merged);

            copyBibliographicMetadata(metadataSource, survivor);
            mergeBookUserScalars(survivor, merged);
            unionRelation("book_authors", "author_id", survivor, merged);
            unionRelation("book_genres", "genre_code", survivor, merged);
            unionRelation("book_groups", "group_id", survivor, merged);
            unionRelation("keyword_books", "normalized_name", survivor, merged);
            mergeReadingProgress(survivor, merged);
            mergeReadingHistory(survivor, merged);
            mergeReadingStats(survivor, merged);
            copyReaderPreferencesIfMissing(survivor, merged);

            jdbc().update("UPDATE bookmarks SET book_id = ? WHERE book_id = ?", survivor, merged);
            moveIdentities(snapshot.movedIdentities(), survivor);

            // Preference must be removed before changing the composite (book_id, artifact_id) parent key.
            jdbc().update("DELETE FROM book_artifact_preferences WHERE book_id = ?", merged);
            jdbc().update("UPDATE artifact_occurrences SET book_id = ? WHERE book_id = ?", survivor, merged);
            jdbc().update("UPDATE book_artifacts SET book_id = ?, updated_at = CURRENT_TIMESTAMP WHERE book_id = ?", survivor, merged);
            ensureSurvivorArtifactPreference(survivor, snapshot.survivorArtifactPreference(), snapshot.mergedArtifactPreference());
            syncLegacyStorageProjection(survivor);

            recomputeAuthorSort(survivor);
            jdbc().update("UPDATE books SET deleted = 1, update_date = CURRENT_TIMESTAMP WHERE id = ?", merged);
            jdbc().update("UPDATE books SET deleted = 0, update_date = CURRENT_TIMESTAMP WHERE id = ?", survivor);

            return new BookMergeMutationResult(mergeId, plan.survivorBookId(), plan.mergedBookId(), changedAt, false);
        });
        if (result == null) throw new IllegalStateException("Merge transaction returned no result");
        evict(plan.survivorBookId(), plan.mergedBookId());
        return result;
    }

    @Override
    public BookMergeMutationResult undo(String mergeId) {
        if (mergeId == null || mergeId.isBlank()) throw new IllegalArgumentException("mergeId is required");
        Instant changedAt = Instant.now();

        BookMergeMutationResult result = tx().execute(status -> {
            Map<String, Object> journal = activeJournal(mergeId)
                    .orElseThrow(() -> new IllegalArgumentException("Active merge not found: " + mergeId));
            String survivor = text(journal.get("survivor_book_id"));
            String merged = text(journal.get("merged_book_id"));
            requireLatestLibraryOperation(mergeId);
            requireLatestUndoable(mergeId, survivor, merged, text(journal.get("merged_at")));
            Snapshot snapshot = decode(text(journal.get("snapshot_json")));

            // Remove current preferences before moving composite artifact ownership back.
            jdbc().update("DELETE FROM book_artifact_preferences WHERE book_id IN (?, ?)", survivor, merged);

            for (String artifactId : snapshot.movedArtifactIds()) {
                jdbc().update("UPDATE artifact_occurrences SET book_id = ? WHERE artifact_id = ?", merged, artifactId);
                jdbc().update("UPDATE book_artifacts SET book_id = ?, updated_at = CURRENT_TIMESTAMP WHERE artifact_id = ?", merged, artifactId);
            }
            moveIdentities(snapshot.movedIdentities(), merged);
            for (String bookmarkId : snapshot.movedBookmarkIds()) {
                jdbc().update("UPDATE bookmarks SET book_id = ? WHERE id = ?", merged, bookmarkId);
            }

            removeAddedRelationKeys("book_authors", "author_id", survivor, snapshot.addedAuthorIds());
            removeAddedRelationKeys("book_genres", "genre_code", survivor, snapshot.addedGenreCodes());
            removeAddedRelationKeys("book_groups", "group_id", survivor, snapshot.addedGroupIds());
            removeAddedRelationKeys("keyword_books", "normalized_name", survivor, snapshot.addedKeywordNames());

            restoreSingleRow("reading_progress", survivor, snapshot.survivorReadingProgress());
            restoreSingleRow("reading_history", survivor, snapshot.survivorReadingHistory());
            restoreSingleRow("reading_stats", survivor, snapshot.survivorReadingStats());
            restoreSingleRow("reader_book_preferences", survivor, snapshot.survivorReaderPreferences());

            restoreBook(snapshot.survivorBook());
            restoreBook(snapshot.mergedBook());
            restoreSingleRow("book_artifact_preferences", survivor, snapshot.survivorArtifactPreference());
            restoreSingleRow("book_artifact_preferences", merged, snapshot.mergedArtifactPreference());

            jdbc().update("UPDATE book_merge_journal SET undone_at = CURRENT_TIMESTAMP WHERE merge_id = ? AND undone_at IS NULL", mergeId);
            int historyUpdated = jdbc().update("""
                    UPDATE operation_history SET undone_at = CURRENT_TIMESTAMP
                     WHERE operation_id = ? AND operation_type = 'BOOK_MERGE'
                       AND completed_at IS NOT NULL AND undone_at IS NULL
                    """, mergeId);
            if (historyUpdated != 1) throw new IllegalStateException("Shared operation history is missing merge: " + mergeId);
            return new BookMergeMutationResult(mergeId, BookId.fromString(survivor), BookId.fromString(merged), changedAt, true);
        });
        if (result == null) throw new IllegalStateException("Undo transaction returned no result");
        evict(result.survivorBookId(), result.mergedBookId());
        return result;
    }

    @Override
    public Optional<BookMergeJournalEntry> findLatestActiveMerge(BookId bookId) {
        if (bookId == null) return Optional.empty();
        List<Map<String, Object>> rows = jdbc().queryForList("""
                SELECT j.merge_id, j.survivor_book_id, j.merged_book_id, j.metadata_source_book_id, j.merged_at
                  FROM book_merge_journal j
                  JOIN operation_history h ON h.operation_id = j.merge_id AND h.operation_type = 'BOOK_MERGE'
                 WHERE j.undone_at IS NULL AND h.undone_at IS NULL AND h.completed_at IS NOT NULL
                   AND (j.survivor_book_id = ? OR j.merged_book_id = ?)
                 ORDER BY datetime(j.merged_at) DESC, j.rowid DESC
                 LIMIT 1
                """, bookId.asString(), bookId.asString());
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.getFirst();
        return Optional.of(new BookMergeJournalEntry(
                text(row.get("merge_id")),
                BookId.fromString(text(row.get("survivor_book_id"))),
                BookId.fromString(text(row.get("merged_book_id"))),
                BookId.fromString(text(row.get("metadata_source_book_id"))),
                parseSqliteInstant(text(row.get("merged_at")))));
    }

    private Map<String, Object> requireActiveBook(String id) {
        List<Map<String, Object>> rows = jdbc().queryForList("SELECT * FROM books WHERE id = ?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Book not found: " + id);
        Map<String, Object> row = new LinkedHashMap<>(rows.getFirst());
        if (number(row.get("deleted")) != 0L) throw new IllegalArgumentException("Book is already deleted/merged: " + id);
        return row;
    }

    private Optional<Map<String, Object>> activeJournal(String mergeId) {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "SELECT * FROM book_merge_journal WHERE merge_id = ? AND undone_at IS NULL", mergeId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(new LinkedHashMap<>(rows.getFirst()));
    }

    private void requireLatestLibraryOperation(String operationId) {
        List<String> ids = jdbc().queryForList("""
                SELECT operation_id FROM operation_history
                 WHERE completed_at IS NOT NULL AND undone_at IS NULL AND changed_count > 0
                 ORDER BY datetime(completed_at) DESC, rowid DESC
                 LIMIT 1
                """, String.class);
        if (ids.isEmpty() || !operationId.equals(ids.getFirst())) {
            throw new IllegalStateException("Undo must be performed in reverse library-operation order");
        }
    }

    private void requireLatestUndoable(String mergeId, String survivor, String merged, String mergedAt) {
        Integer newer = jdbc().queryForObject("""
                SELECT COUNT(*) FROM book_merge_journal
                 WHERE undone_at IS NULL AND merge_id <> ?
                   AND (survivor_book_id IN (?, ?) OR merged_book_id IN (?, ?))
                   AND (datetime(merged_at) > datetime(?) OR (datetime(merged_at) = datetime(?) AND rowid >
                       (SELECT rowid FROM book_merge_journal WHERE merge_id = ?)))
                """, Integer.class, mergeId, survivor, merged, survivor, merged, mergedAt, mergedAt, mergeId);
        if (newer != null && newer > 0) {
            throw new IllegalStateException("Undo must be performed in reverse merge order");
        }
    }

    private void copyBibliographicMetadata(String metadataSource, String survivor) {
        if (metadataSource.equals(survivor)) return;
        String assignments = BIBLIOGRAPHIC_COLUMNS.stream()
                .map(column -> column + " = (SELECT " + column + " FROM books WHERE id = ?)")
                .collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < BIBLIOGRAPHIC_COLUMNS.size(); i++) args.add(metadataSource);
        args.add(survivor);
        jdbc().update("UPDATE books SET " + assignments + " WHERE id = ?", args.toArray());
    }

    private void mergeBookUserScalars(String survivor, String merged) {
        jdbc().update("""
                UPDATE books
                   SET rate = MAX(COALESCE(rate, 0), COALESCE((SELECT rate FROM books WHERE id = ?), 0)),
                       progress = MAX(COALESCE(progress, 0), COALESCE((SELECT progress FROM books WHERE id = ?), 0)),
                       review = CASE
                           WHEN TRIM(COALESCE(review, '')) = '' THEN COALESCE((SELECT review FROM books WHERE id = ?), '')
                           WHEN TRIM(COALESCE((SELECT review FROM books WHERE id = ?), '')) = '' THEN review
                           WHEN TRIM(review) = TRIM((SELECT review FROM books WHERE id = ?)) THEN review
                           ELSE review || char(10) || char(10) || '--- merge ---' || char(10) || (SELECT review FROM books WHERE id = ?)
                       END
                 WHERE id = ?
                """, merged, merged, merged, merged, merged, merged, survivor);
    }

    private void unionRelation(String table, String key, String survivor, String merged) {
        jdbc().update("INSERT OR IGNORE INTO " + table + "(book_id, " + key + ") SELECT ?, " + key + " FROM " + table + " WHERE book_id = ?",
                survivor, merged);
    }

    private List<String> addedKeys(String table, String key, String survivor, String merged) {
        Set<String> existing = new LinkedHashSet<>(jdbc().queryForList(
                "SELECT CAST(" + key + " AS TEXT) FROM " + table + " WHERE book_id = ?", String.class, survivor));
        List<String> mergedKeys = jdbc().queryForList(
                "SELECT CAST(" + key + " AS TEXT) FROM " + table + " WHERE book_id = ?", String.class, merged);
        return mergedKeys.stream().filter(value -> value != null && !existing.contains(value)).distinct().toList();
    }

    private void removeAddedRelationKeys(String table, String key, String bookId, List<String> values) {
        if (values == null) return;
        for (String value : values) {
            jdbc().update("DELETE FROM " + table + " WHERE book_id = ? AND CAST(" + key + " AS TEXT) = ?", bookId, value);
        }
    }

    private List<String> stringColumn(String table, String column, String bookId) {
        return jdbc().queryForList("SELECT CAST(" + column + " AS TEXT) FROM " + table + " WHERE book_id = ? ORDER BY " + column,
                String.class, bookId).stream().filter(Objects::nonNull).toList();
    }

    private List<IdentityKey> identityKeys(String bookId) {
        return jdbc().query("""
                SELECT source_id, scheme, external_id FROM book_identities WHERE book_id = ?
                ORDER BY source_id, scheme, external_id
                """, (rs, rowNum) -> new IdentityKey(rs.getString(1), rs.getString(2), rs.getString(3)), bookId);
    }

    private void moveIdentities(List<IdentityKey> keys, String targetBookId) {
        if (keys == null) return;
        for (IdentityKey key : keys) {
            jdbc().update("""
                    UPDATE book_identities SET book_id = ?
                     WHERE source_id = ? AND scheme = ? AND external_id = ?
                    """, targetBookId, key.sourceId(), key.scheme(), key.externalId());
        }
    }

    private Map<String, Object> singleRow(String table, String bookId) {
        List<Map<String, Object>> rows = jdbc().queryForList("SELECT * FROM " + table + " WHERE book_id = ? LIMIT 1", bookId);
        return rows.isEmpty() ? Map.of() : new LinkedHashMap<>(rows.getFirst());
    }

    private void restoreSingleRow(String table, String bookId, Map<String, Object> row) {
        jdbc().update("DELETE FROM " + table + " WHERE book_id = ?", bookId);
        if (row != null && !row.isEmpty()) insertRow(table, row);
    }

    private void mergeReadingProgress(String survivor, String merged) {
        List<Map<String, Object>> rows = jdbc().queryForList("""
                SELECT * FROM reading_progress WHERE book_id IN (?, ?)
                ORDER BY datetime(COALESCE(updated_at, '1970-01-01')) DESC,
                         COALESCE(percent, 0) DESC,
                         CASE WHEN book_id = ? THEN 0 ELSE 1 END
                LIMIT 1
                """, survivor, merged, survivor);
        if (rows.isEmpty()) return;
        Map<String, Object> chosen = new LinkedHashMap<>(rows.getFirst());
        chosen.put("book_id", survivor);
        jdbc().update("DELETE FROM reading_progress WHERE book_id = ?", survivor);
        insertRow("reading_progress", chosen);
    }

    private void mergeReadingHistory(String survivor, String merged) {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "SELECT * FROM reading_history WHERE book_id IN (?, ?)", survivor, merged);
        if (rows.isEmpty()) return;
        String last = rows.stream().map(r -> text(r.get("last_opened_at"))).filter(v -> !v.isBlank()).max(String::compareTo).orElse("");
        long opens = rows.stream().mapToLong(r -> number(r.get("open_count"))).sum();
        jdbc().update("""
                INSERT INTO reading_history(book_id, last_opened_at, open_count) VALUES (?, ?, ?)
                ON CONFLICT(book_id) DO UPDATE SET last_opened_at = excluded.last_opened_at, open_count = excluded.open_count
                """, survivor, last.isBlank() ? Instant.now().toString() : last, Math.max(1, opens));
    }

    private void mergeReadingStats(String survivor, String merged) {
        List<Map<String, Object>> rows = jdbc().queryForList(
                "SELECT * FROM reading_stats WHERE book_id IN (?, ?)", survivor, merged);
        if (rows.isEmpty()) return;
        String first = rows.stream().map(r -> text(r.get("first_read_at"))).filter(v -> !v.isBlank()).min(String::compareTo).orElse(Instant.now().toString());
        String last = rows.stream().map(r -> text(r.get("last_read_at"))).filter(v -> !v.isBlank()).max(String::compareTo).orElse(first);
        long totalSeconds = rows.stream().mapToLong(r -> number(r.get("total_reading_seconds"))).sum();
        long sessions = rows.stream().mapToLong(r -> number(r.get("reading_sessions"))).sum();
        long start = rows.stream().mapToLong(r -> number(r.get("start_percent"))).min().orElse(0);
        long end = rows.stream().mapToLong(r -> number(r.get("end_percent"))).max().orElse(0);
        long current = rows.stream().mapToLong(r -> number(r.get("current_percent"))).max().orElse(0);
        String completed = rows.stream().map(r -> text(r.get("completed_at"))).filter(v -> !v.isBlank()).max(String::compareTo).orElse(null);
        jdbc().update("DELETE FROM reading_stats WHERE book_id = ?", survivor);
        jdbc().update("""
                INSERT INTO reading_stats(book_id, first_read_at, last_read_at, total_reading_seconds,
                    reading_sessions, start_percent, end_percent, current_percent, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, survivor, first, last, totalSeconds, sessions, start, end, current, completed);
    }

    private void copyReaderPreferencesIfMissing(String survivor, String merged) {
        Integer count = jdbc().queryForObject("SELECT COUNT(*) FROM reader_book_preferences WHERE book_id = ?", Integer.class, survivor);
        if (count != null && count > 0) return;
        jdbc().update("""
                INSERT OR IGNORE INTO reader_book_preferences(book_id, preferences_json, updated_at)
                SELECT ?, preferences_json, updated_at FROM reader_book_preferences WHERE book_id = ?
                """, survivor, merged);
    }

    private void ensureSurvivorArtifactPreference(String survivor,
                                                   Map<String, Object> survivorPreference,
                                                   Map<String, Object> mergedPreference) {
        if (survivorPreference != null && !survivorPreference.isEmpty()) return;
        String preferred = mergedPreference == null ? "" : text(mergedPreference.get("preferred_artifact_id"));
        if (!preferred.isBlank()) {
            int inserted = jdbc().update("""
                    INSERT OR REPLACE INTO book_artifact_preferences(book_id, preferred_artifact_id, updated_at)
                    SELECT ?, artifact_id, CURRENT_TIMESTAMP FROM book_artifacts
                     WHERE book_id = ? AND artifact_id = ?
                    """, survivor, survivor, preferred);
            if (inserted > 0) return;
        }
        jdbc().update("""
                INSERT OR IGNORE INTO book_artifact_preferences(book_id, preferred_artifact_id, updated_at)
                SELECT ?, artifact_id, CURRENT_TIMESTAMP
                  FROM book_artifacts
                 WHERE book_id = ?
                 ORDER BY CASE WHEN state = 'AVAILABLE' AND local = 1 THEN 0 ELSE 1 END, artifact_id
                 LIMIT 1
                """, survivor, survivor);
    }

    private void syncLegacyStorageProjection(String bookId) {
        jdbc().update("""
                UPDATE books
                   SET file_name = COALESCE((SELECT COALESCE(NULLIF(ba.archive_name, ''), ba.file_name, '')
                                               FROM book_artifact_preferences p JOIN book_artifacts ba
                                                 ON ba.artifact_id = p.preferred_artifact_id AND ba.book_id = p.book_id
                                              WHERE p.book_id = books.id), file_name),
                       folder = COALESCE((SELECT ba.folder FROM book_artifact_preferences p JOIN book_artifacts ba
                                           ON ba.artifact_id = p.preferred_artifact_id AND ba.book_id = p.book_id
                                          WHERE p.book_id = books.id), ''),
                       archive_entry = COALESCE((SELECT ba.archive_entry FROM book_artifact_preferences p JOIN book_artifacts ba
                                                 ON ba.artifact_id = p.preferred_artifact_id AND ba.book_id = p.book_id
                                                WHERE p.book_id = books.id), ''),
                       file_size = COALESCE((SELECT ba.size_bytes FROM book_artifact_preferences p JOIN book_artifacts ba
                                             ON ba.artifact_id = p.preferred_artifact_id AND ba.book_id = p.book_id
                                            WHERE p.book_id = books.id), 0),
                       collection_root = COALESCE((SELECT ba.collection_root FROM book_artifact_preferences p JOIN book_artifacts ba
                                                   ON ba.artifact_id = p.preferred_artifact_id AND ba.book_id = p.book_id
                                                  WHERE p.book_id = books.id), ''),
                       local = COALESCE((SELECT ba.local FROM book_artifact_preferences p JOIN book_artifacts ba
                                         ON ba.artifact_id = p.preferred_artifact_id AND ba.book_id = p.book_id
                                        WHERE p.book_id = books.id), local),
                       format = COALESCE((SELECT upper(NULLIF(ba.file_format, '')) FROM book_artifact_preferences p JOIN book_artifacts ba
                                          ON ba.artifact_id = p.preferred_artifact_id AND ba.book_id = p.book_id
                                         WHERE p.book_id = books.id), format),
                       missing_since = CASE WHEN COALESCE((SELECT ba.local FROM book_artifact_preferences p JOIN book_artifacts ba
                                                           ON ba.artifact_id = p.preferred_artifact_id AND ba.book_id = p.book_id
                                                          WHERE p.book_id = books.id), 0) = 1
                                            THEN NULL ELSE missing_since END
                 WHERE id = ?
                """, bookId);
    }

    private void recomputeAuthorSort(String bookId) {
        jdbc().update("""
                UPDATE books SET author_sort = COALESCE((
                    SELECT MIN(lower(trim(COALESCE(a.last_name, '') || ' ' || COALESCE(a.first_name, '') || ' ' || COALESCE(a.middle_name, ''))))
                    FROM book_authors ba JOIN authors a ON a.id = ba.author_id WHERE ba.book_id = books.id
                ), '') WHERE id = ?
                """, bookId);
    }

    private void restoreBook(Map<String, Object> row) {
        if (row == null || row.isEmpty()) throw new IllegalStateException("Merge snapshot has no book row");
        String id = text(row.get("id"));
        List<String> columns = jdbc().query("PRAGMA table_info(books)",
                (rs, rowNum) -> rs.getString("name")).stream().filter(name -> !"id".equalsIgnoreCase(name)).toList();
        String assignments = columns.stream().map(name -> name + " = ?").collect(Collectors.joining(", "));
        List<Object> values = new ArrayList<>(columns.size() + 1);
        for (String column : columns) values.add(row.get(column));
        values.add(id);
        int updated = jdbc().update("UPDATE books SET " + assignments + " WHERE id = ?", values.toArray());
        if (updated == 0) throw new IllegalStateException("Book row disappeared after merge: " + id);
    }

    private void insertRow(String table, Map<String, Object> row) {
        if (row == null || row.isEmpty()) return;
        List<String> columns = new ArrayList<>(row.keySet());
        String names = String.join(", ", columns);
        String placeholders = columns.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        Object[] values = columns.stream().map(row::get).toArray();
        jdbc().update("INSERT INTO " + table + "(" + names + ") VALUES (" + placeholders + ")", values);
    }

    private String encode(Snapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize merge snapshot", e);
        }
    }

    private Snapshot decode(String json) {
        try {
            return objectMapper.readValue(json, Snapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read merge snapshot", e);
        }
    }

    private void evict(BookId... ids) {
        if (ids == null) return;
        for (BookId id : ids) bookCache.evict(id);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : value == null || value.toString().isBlank() ? 0L : Long.parseLong(value.toString());
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Instant parseSqliteInstant(String value) {
        if (value == null || value.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(value.replace(' ', 'T') + (value.endsWith("Z") ? "" : "Z"));
        } catch (RuntimeException ignored) {
            return Instant.EPOCH;
        }
    }

    private record IdentityKey(String sourceId, String scheme, String externalId) {}

    private record Snapshot(
            Map<String, Object> survivorBook,
            Map<String, Object> mergedBook,
            List<String> addedAuthorIds,
            List<String> addedGenreCodes,
            List<String> addedGroupIds,
            List<String> addedKeywordNames,
            List<String> movedBookmarkIds,
            List<String> movedArtifactIds,
            List<IdentityKey> movedIdentities,
            Map<String, Object> survivorReadingProgress,
            Map<String, Object> survivorReadingHistory,
            Map<String, Object> survivorReadingStats,
            Map<String, Object> survivorReaderPreferences,
            Map<String, Object> survivorArtifactPreference,
            Map<String, Object> mergedArtifactPreference
    ) {
        private Snapshot {
            survivorBook = copyMap(survivorBook);
            mergedBook = copyMap(mergedBook);
            addedAuthorIds = copyList(addedAuthorIds);
            addedGenreCodes = copyList(addedGenreCodes);
            addedGroupIds = copyList(addedGroupIds);
            addedKeywordNames = copyList(addedKeywordNames);
            movedBookmarkIds = copyList(movedBookmarkIds);
            movedArtifactIds = copyList(movedArtifactIds);
            movedIdentities = List.copyOf(movedIdentities == null ? List.of() : movedIdentities);
            survivorReadingProgress = copyMap(survivorReadingProgress);
            survivorReadingHistory = copyMap(survivorReadingHistory);
            survivorReadingStats = copyMap(survivorReadingStats);
            survivorReaderPreferences = copyMap(survivorReaderPreferences);
            survivorArtifactPreference = copyMap(survivorArtifactPreference);
            mergedArtifactPreference = copyMap(mergedArtifactPreference);
        }

        private static Map<String, Object> copyMap(Map<String, Object> source) {
            return source == null || source.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }

        private static <T> List<T> copyList(List<T> source) {
            return List.copyOf(source == null ? List.of() : source);
        }
    }
}
