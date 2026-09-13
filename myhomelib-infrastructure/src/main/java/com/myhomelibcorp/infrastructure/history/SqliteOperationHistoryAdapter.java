package com.myhomelibcorp.infrastructure.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.bulkedit.BatchMetadataChange;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditRule;
import com.myhomelibcorp.application.bulkedit.BatchMetadataEditableSnapshot;
import com.myhomelibcorp.application.history.LibraryOperationHistoryEntry;
import com.myhomelibcorp.application.history.LibraryOperationHistoryType;
import com.myhomelibcorp.application.port.out.history.OperationHistoryPort;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SQLite-backed shared MHL-112 journal. All writes participate in the caller's collection transaction. */
@Component
public class SqliteOperationHistoryAdapter implements OperationHistoryPort {
    private final CollectionManager collections;
    private final ObjectMapper objectMapper;

    public SqliteOperationHistoryAdapter(CollectionManager collections, ObjectMapper objectMapper) {
        this.collections = collections;
        this.objectMapper = objectMapper;
    }

    private JdbcTemplate jdbc() {
        return collections.getCurrentJdbcTemplate();
    }

    @Override
    public void beginBulkOperation(String operationId, String summary, int selectedCount, List<BatchMetadataEditRule> rules) {
        requireId(operationId);
        jdbc().update("""
                INSERT INTO operation_history(
                    operation_id, operation_type, summary, affected_count, changed_count,
                    rules_json, created_at, completed_at, undone_at
                ) VALUES (?, 'BULK_METADATA', ?, ?, 0, ?, CURRENT_TIMESTAMP, NULL, NULL)
                """, operationId, summary == null ? "" : summary, Math.max(0, selectedCount), encode(rules == null ? List.of() : rules));
    }

    @Override
    public void appendBulkChanges(String operationId, int sequenceStart, List<BatchMetadataChange> changes) {
        requireId(operationId);
        if (changes == null || changes.isEmpty()) return;
        List<Object[]> args = new ArrayList<>(changes.size());
        int sequence = sequenceStart;
        for (BatchMetadataChange change : changes) {
            if (change == null || change.bookId() == null || change.before() == null || change.after() == null) {
                throw new IllegalArgumentException("Complete bulk metadata change is required");
            }
            args.add(new Object[]{operationId, sequence++, change.bookId().asString(), encode(change.before()), encode(change.after())});
        }
        jdbc().batchUpdate("""
                INSERT INTO bulk_metadata_changes(operation_id, sequence_no, book_id, before_json, after_json)
                VALUES (?, ?, ?, ?, ?)
                """, args);
    }

    @Override
    public void completeOperation(String operationId, int changedCount) {
        requireId(operationId);
        int updated = jdbc().update("""
                UPDATE operation_history
                   SET changed_count = ?, completed_at = CURRENT_TIMESTAMP
                 WHERE operation_id = ? AND completed_at IS NULL AND undone_at IS NULL
                """, Math.max(0, changedCount), operationId);
        if (updated != 1) throw new IllegalStateException("Operation history entry cannot be completed: " + operationId);
    }

    @Override
    public void markUndone(String operationId) {
        requireId(operationId);
        int updated = jdbc().update("""
                UPDATE operation_history
                   SET undone_at = CURRENT_TIMESTAMP
                 WHERE operation_id = ? AND completed_at IS NOT NULL AND undone_at IS NULL
                """, operationId);
        if (updated != 1) throw new IllegalStateException("Operation history entry cannot be marked undone: " + operationId);
    }

    @Override
    public Optional<LibraryOperationHistoryEntry> latestUndoable() {
        List<Map<String, Object>> rows = jdbc().queryForList("""
                SELECT operation_id, operation_type, summary, affected_count, changed_count,
                       created_at, completed_at, undone_at
                  FROM operation_history
                 WHERE completed_at IS NOT NULL AND undone_at IS NULL AND changed_count > 0
                 ORDER BY datetime(completed_at) DESC, rowid DESC
                 LIMIT 1
                """);
        return rows.isEmpty() ? Optional.empty() : Optional.of(mapEntry(rows.getFirst()));
    }

    @Override
    public List<LibraryOperationHistoryEntry> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        return jdbc().queryForList("""
                SELECT operation_id, operation_type, summary, affected_count, changed_count,
                       created_at, completed_at, undone_at
                  FROM operation_history
                 WHERE completed_at IS NOT NULL
                 ORDER BY datetime(completed_at) DESC, rowid DESC
                 LIMIT ?
                """, safeLimit).stream().map(SqliteOperationHistoryAdapter::mapEntry).toList();
    }

    @Override
    public List<BatchMetadataChange> loadBulkChanges(String operationId, int offset, int limit) {
        requireId(operationId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(1000, limit));
        return jdbc().queryForList("""
                SELECT book_id, before_json, after_json
                  FROM bulk_metadata_changes
                 WHERE operation_id = ?
                 ORDER BY sequence_no
                 LIMIT ? OFFSET ?
                """, operationId, safeLimit, safeOffset).stream()
                .map(row -> new BatchMetadataChange(
                        BookId.fromString(text(row.get("book_id"))),
                        decode(text(row.get("before_json")), BatchMetadataEditableSnapshot.class),
                        decode(text(row.get("after_json")), BatchMetadataEditableSnapshot.class)))
                .toList();
    }

    @Override
    public int countBulkChanges(String operationId) {
        requireId(operationId);
        Integer count = jdbc().queryForObject(
                "SELECT COUNT(*) FROM bulk_metadata_changes WHERE operation_id = ?", Integer.class, operationId);
        return count == null ? 0 : count;
    }

    @Override
    public void requireLatestUndoable(String operationId) {
        requireId(operationId);
        LibraryOperationHistoryEntry latest = latestUndoable()
                .orElseThrow(() -> new IllegalStateException("No undoable library operation"));
        if (!operationId.equals(latest.operationId())) {
            throw new IllegalStateException("Undo must be performed in reverse library-operation order");
        }
    }

    @Override
    public void prune(int retainedOperations) {
        int retain = Math.max(1, Math.min(200, retainedOperations));
        jdbc().update("""
                DELETE FROM operation_history
                 WHERE operation_id IN (
                       SELECT operation_id
                         FROM operation_history
                        WHERE completed_at IS NOT NULL
                        ORDER BY datetime(completed_at) DESC, rowid DESC
                        LIMIT -1 OFFSET ?
                 )
                """, retain);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failed) {
            throw new IllegalStateException("Cannot serialize operation history", failed);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failed) {
            throw new IllegalStateException("Cannot deserialize operation history", failed);
        }
    }

    private static LibraryOperationHistoryEntry mapEntry(Map<String, Object> row) {
        return new LibraryOperationHistoryEntry(
                text(row.get("operation_id")),
                LibraryOperationHistoryType.valueOf(text(row.get("operation_type"))),
                text(row.get("summary")),
                integer(row.get("affected_count")),
                integer(row.get("changed_count")),
                parseSqliteInstant(text(row.get("created_at"))),
                nullableInstant(row.get("completed_at")),
                nullableInstant(row.get("undone_at")));
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(text(value));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Instant nullableInstant(Object value) {
        String text = text(value);
        return text.isBlank() ? null : parseSqliteInstant(text);
    }

    private static Instant parseSqliteInstant(String value) {
        if (value == null || value.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(value.replace(' ', 'T') + (value.endsWith("Z") ? "" : "Z"));
        } catch (RuntimeException invalid) {
            return Instant.EPOCH;
        }
    }

    private static void requireId(String operationId) {
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId is required");
    }
}
