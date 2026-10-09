package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.ReadingListRepository;
import com.myhomelibcorp.domain.model.readinglist.ReadingList;
import com.myhomelibcorp.domain.model.readinglist.ReadingListId;
import com.myhomelibcorp.domain.model.readinglist.ReadingListItem;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.CollectionTransactionExecutor;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteDateTimeCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SqliteReadingListRepository implements ReadingListRepository {
    private final CollectionManager collectionManager;

    private JdbcTemplate jdbc() {
        JdbcTemplate current = collectionManager.getCurrentJdbcTemplate();
        if (current == null) throw new IllegalStateException("Current collection is not selected");
        return current;
    }

    @Override
    public ReadingList save(ReadingList list) {
        if (list == null) throw new IllegalArgumentException("Reading list cannot be null");
        try {
            jdbc().update("""
                    INSERT INTO reading_lists(id,name,description,created_at,updated_at)
                    VALUES(?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET
                        name=excluded.name,
                        description=excluded.description,
                        updated_at=excluded.updated_at
                    """,
                    list.id().value(), list.name(), list.description(),
                    SqliteDateTimeCodec.format(list.createdAt()),
                    SqliteDateTimeCodec.format(list.updatedAt()));
        } catch (DataAccessException failure) {
            if (isNormalizedNameConstraint(failure)) {
                throw new IllegalArgumentException("Reading list name already exists: " + list.name(), failure);
            }
            throw failure;
        }
        return findById(list.id()).orElseThrow();
    }

    @Override
    public Optional<ReadingList> findById(ReadingListId id) {
        if (id == null) return Optional.empty();
        List<ListRow> rows = jdbc().query("""
                        SELECT id,name,COALESCE(description,'') AS description,created_at,updated_at
                          FROM reading_lists
                         WHERE id=?
                         LIMIT 1
                        """,
                (rs, rowNum) -> mapListRow(rs.getString("id"), rs.getString("name"),
                        rs.getString("description"), rs.getString("created_at"), rs.getString("updated_at")),
                id.value());
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(rows.getFirst().toDomain(List.of()));
    }

    @Override
    public Optional<ReadingList> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return jdbc().query("""
                        SELECT id
                          FROM reading_lists
                         WHERE lower(trim(name))=lower(trim(?))
                         LIMIT 1
                        """,
                (rs, rowNum) -> ReadingListId.fromString(rs.getString("id")), name.trim())
                .stream().findFirst().flatMap(this::findById);
    }

    @Override
    public List<ReadingList> findAll() {
        List<ListRow> rows = jdbc().query("""
                        SELECT id,name,COALESCE(description,'') AS description,created_at,updated_at
                          FROM reading_lists
                         ORDER BY name COLLATE NOCASE,id
                        """,
                (rs, rowNum) -> mapListRow(rs.getString("id"), rs.getString("name"),
                        rs.getString("description"), rs.getString("created_at"), rs.getString("updated_at")));
        if (rows.isEmpty()) return List.of();
        return rows.stream().map(row -> row.toDomain(List.of())).toList();
    }

    @Override
    public Map<ReadingListId, Integer> itemCounts() {
        Map<ReadingListId, Integer> counts = new LinkedHashMap<>();
        jdbc().query("""
                SELECT list_id,COUNT(*) AS item_count
                  FROM reading_list_items
                 GROUP BY list_id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            counts.put(ReadingListId.fromString(rs.getString("list_id")), rs.getInt("item_count"));
        });
        return counts;
    }

    @Override
    public void delete(ReadingListId id) {
        if (id == null) return;
        CollectionTransactionExecutor.run(collectionManager, () -> {
            jdbc().update("DELETE FROM reading_list_items WHERE list_id=?", id.value());
            jdbc().update("DELETE FROM reading_lists WHERE id=?", id.value());
        });
    }

    @Override
    public List<ReadingListItem> findItems(ReadingListId id) {
        if (id == null) return List.of();
        return jdbc().query("""
                        SELECT book_id,position,added_at
                          FROM reading_list_items
                         WHERE list_id=?
                         ORDER BY position
                        """,
                (rs, rowNum) -> mapItem(rs.getString("book_id"), rs.getInt("position"), rs.getString("added_at")),
                id.value());
    }

    @Override
    public void addBooks(ReadingListId id, List<BookId> bookIds) {
        if (id == null || bookIds == null || bookIds.isEmpty()) return;
        CollectionTransactionExecutor.run(collectionManager, () -> {
            requireList(id);
            LinkedHashSet<String> requested = new LinkedHashSet<>();
            for (BookId bookId : bookIds) if (bookId != null) requested.add(bookId.asString());
            if (requested.isEmpty()) return;

            requested.removeAll(new LinkedHashSet<>(jdbc().queryForList(
                    "SELECT book_id FROM reading_list_items WHERE list_id=?", String.class, id.value())));
            if (requested.isEmpty()) return;

            LinkedHashSet<String> validBooks = new LinkedHashSet<>();
            List<String> requestedIds = List.copyOf(requested);
            com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteInClauseSupport.forEachChunk(requestedIds, part -> {
                String placeholders = com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteInClauseSupport.placeholders(part.size());
                validBooks.addAll(jdbc().queryForList(
                        "SELECT id FROM books WHERE COALESCE(deleted,0)=0 AND id IN (" + placeholders + ")",
                        String.class, part.toArray()));
            });
            if (validBooks.size() != requested.size() || !validBooks.containsAll(requested)) {
                throw new IllegalArgumentException("One or more books do not exist or are deleted");
            }

            Integer nextValue = jdbc().queryForObject(
                    "SELECT COALESCE(MAX(position),-1)+1 FROM reading_list_items WHERE list_id=?",
                    Integer.class, id.value());
            int next = nextValue == null ? 0 : nextValue;
            String now = SqliteDateTimeCodec.format(LocalDateTime.now());
            List<Object[]> batch = new ArrayList<>(requested.size());
            for (String bookId : requested) batch.add(new Object[]{id.value(), bookId, next++, now});
            try {
                jdbc().batchUpdate("""
                        INSERT INTO reading_list_items(list_id,book_id,position,added_at)
                        VALUES(?,?,?,?)
                        """, batch);
            } catch (DataIntegrityViolationException invalidBook) {
                throw new IllegalArgumentException("One or more books do not exist", invalidBook);
            }
            touch(id);
        });
    }

    @Override
    public void removeBooks(ReadingListId id, List<BookId> bookIds) {
        if (id == null || bookIds == null || bookIds.isEmpty()) return;
        CollectionTransactionExecutor.run(collectionManager, () -> {
            requireList(id);
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (BookId bookId : bookIds) if (bookId != null) unique.add(bookId.asString());
            if (unique.isEmpty()) return;
            int changed = 0;
            for (String bookId : unique) {
                changed += jdbc().update("DELETE FROM reading_list_items WHERE list_id=? AND book_id=?", id.value(), bookId);
            }
            if (changed > 0) touch(id);
        });
    }

    @Override
    public void reorder(ReadingListId id, List<BookId> orderedBookIds) {
        if (id == null || orderedBookIds == null) {
            throw new IllegalArgumentException("Reading list and order cannot be null");
        }
        CollectionTransactionExecutor.run(collectionManager, () -> {
            requireList(id);
            List<String> current = jdbc().queryForList(
                    "SELECT book_id FROM reading_list_items WHERE list_id=? ORDER BY position", String.class, id.value());
            List<String> requested = orderedBookIds.stream().map(BookId::asString).toList();
            LinkedHashSet<String> unique = new LinkedHashSet<>(requested);
            if (requested.size() != unique.size()
                    || requested.size() != current.size()
                    || !unique.equals(new LinkedHashSet<>(current))) {
                throw new IllegalArgumentException("Reorder request must contain exactly the current reading-list membership");
            }
            if (requested.isEmpty()) return;

            int offset = current.size() + 1;
            jdbc().update("UPDATE reading_list_items SET position=position+? WHERE list_id=?", offset, id.value());
            List<Object[]> batch = new ArrayList<>(requested.size());
            for (int position = 0; position < requested.size(); position++) {
                batch.add(new Object[]{position, id.value(), requested.get(position)});
            }
            jdbc().batchUpdate("UPDATE reading_list_items SET position=? WHERE list_id=? AND book_id=?", batch);
            touch(id);
        });
    }

    @Override
    public Optional<BookId> findResumeBookId(ReadingListId id) {
        if (id == null) return Optional.empty();
        return jdbc().query("""
                        SELECT rli.book_id
                          FROM reading_list_items rli
                          JOIN books b ON b.id=rli.book_id AND COALESCE(b.deleted,0)=0
                          LEFT JOIN reading_progress rp ON rp.book_id=rli.book_id
                         WHERE rli.list_id=?
                           AND b.missing_since IS NULL
                           AND MAX(COALESCE(b.progress,0),COALESCE(rp.percent,0)) < 100
                         ORDER BY rli.position
                         LIMIT 1
                        """,
                (rs, rowNum) -> BookId.fromString(rs.getString("book_id")), id.value())
                .stream().findFirst();
    }

    private static boolean isNormalizedNameConstraint(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getErrorCode() == 19) {
                String message = sql.getMessage();
                if (message != null && message.contains("ux_reading_lists_normalized_name")) return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("ux_reading_lists_normalized_name")) return true;
        }
        return false;
    }

    private void requireList(ReadingListId id) {
        Integer count = jdbc().queryForObject("SELECT COUNT(*) FROM reading_lists WHERE id=?", Integer.class, id.value());
        if (count == null || count != 1) throw new IllegalArgumentException("Reading list not found: " + id.value());
    }

    private void touch(ReadingListId id) {
        jdbc().update("UPDATE reading_lists SET updated_at=? WHERE id=?",
                SqliteDateTimeCodec.format(LocalDateTime.now()), id.value());
    }

    private static ReadingListItem mapItem(String bookId, int position, String addedAt) {
        return new ReadingListItem(BookId.fromString(bookId), position, SqliteDateTimeCodec.parse(addedAt));
    }

    private static ListRow mapListRow(String id, String name, String description, String createdAt, String updatedAt) {
        return new ListRow(ReadingListId.fromString(id), name, description,
                SqliteDateTimeCodec.parse(createdAt), SqliteDateTimeCodec.parse(updatedAt));
    }

    private record ListRow(ReadingListId id, String name, String description,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {
        private ReadingList toDomain(List<ReadingListItem> items) {
            return new ReadingList(id, name, description, createdAt, updatedAt, items);
        }
    }
}
