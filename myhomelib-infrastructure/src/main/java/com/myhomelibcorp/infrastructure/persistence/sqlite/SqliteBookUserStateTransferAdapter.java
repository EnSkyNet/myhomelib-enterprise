package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.collection.BookUserStateTransferPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionDatabasePathResolver;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteInClauseSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Bounded SQLite-to-SQLite transfer of collection-local reader/user state.
 * Target writes use the Spring-bound current collection connection, so when this
 * adapter is invoked from BookSaver's transaction hook the book rows and state
 * are committed or rolled back together.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SqliteBookUserStateTransferAdapter implements BookUserStateTransferPort {
    private static final int JDBC_BATCH = 200;

    private final CollectionManager collections;

    @Override
    public void transferCopiedBookState(Collection sourceCollection,
                                        Collection targetCollection,
                                        List<BookId> copiedBookIds) {
        List<String> ids = normalizeIds(copiedBookIds);
        if (ids.isEmpty()) return;

        Collection current = collections.getCurrentCollection();
        if (current == null || targetCollection == null ||
                !targetCollection.getId().equals(current.getId())) {
            throw new IllegalStateException("Target collection must be active during user-state transfer");
        }
        if (sourceCollection == null || sourceCollection.getId().equals(targetCollection.getId())) {
            throw new IllegalArgumentException("Source and target collections must be different");
        }

        Path sourceDb = CollectionDatabasePathResolver.resolve(sourceCollection);
        Path targetDb = CollectionDatabasePathResolver.resolve(targetCollection);
        if (sourceDb.equals(targetDb)) {
            throw new IllegalArgumentException("Source and target collections resolve to the same SQLite database");
        }
        if (!Files.isRegularFile(sourceDb)) {
            throw new IllegalStateException("Source collection database is unavailable: " + sourceDb);
        }

        collections.getCurrentJdbcTemplate().execute((ConnectionCallback<Void>) target -> {
            try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + sourceDb)) {
                try (var st = source.createStatement()) {
                    st.execute("PRAGMA query_only=ON");
                    st.execute("PRAGMA busy_timeout=5000");
                }
                for (int from = 0; from < ids.size(); from += SqliteInClauseSupport.MAX_ITEMS) {
                    List<String> part = ids.subList(from, Math.min(ids.size(), from + SqliteInClauseSupport.MAX_ITEMS));
                    copyReadingProgress(source, target, part);
                    copyReadingHistory(source, target, part);
                    copyReadingStatistics(source, target, part);
                    copyBookmarks(source, target, part);
                    copyReaderPreferences(source, target, part);
                }
                return null;
            }
        });
        log.debug("Transferred collection-local user state for {} copied books", ids.size());
    }

    private static void copyReadingProgress(Connection source, Connection target, List<String> ids) throws SQLException {
        String select = """
                SELECT book_id,anchor_id,paragraph_index,paragraph_id,char_offset,percent,
                       chapter_title,chapter_id,updated_at,reading_time_seconds
                FROM reading_progress WHERE book_id IN (%s)
                """.formatted(SqliteInClauseSupport.placeholders(ids.size()));
        String upsert = """
                INSERT INTO reading_progress
                    (book_id,anchor_id,paragraph_index,paragraph_id,char_offset,percent,
                     chapter_title,chapter_id,updated_at,reading_time_seconds)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(book_id) DO UPDATE SET
                    anchor_id=excluded.anchor_id,
                    paragraph_index=excluded.paragraph_index,
                    paragraph_id=excluded.paragraph_id,
                    char_offset=excluded.char_offset,
                    percent=excluded.percent,
                    chapter_title=excluded.chapter_title,
                    chapter_id=excluded.chapter_id,
                    updated_at=excluded.updated_at,
                    reading_time_seconds=excluded.reading_time_seconds
                """;
        copyRows(source, target, ids, select, upsert, 10, false);
    }

    private static void copyReadingHistory(Connection source, Connection target, List<String> ids) throws SQLException {
        String select = "SELECT book_id,last_opened_at,open_count FROM reading_history WHERE book_id IN ("
                + SqliteInClauseSupport.placeholders(ids.size()) + ")";
        String upsert = """
                INSERT INTO reading_history(book_id,last_opened_at,open_count) VALUES(?,?,?)
                ON CONFLICT(book_id) DO UPDATE SET
                    last_opened_at=excluded.last_opened_at,
                    open_count=excluded.open_count
                """;
        copyRows(source, target, ids, select, upsert, 3, false);
    }

    private static void copyReadingStatistics(Connection source, Connection target, List<String> ids) throws SQLException {
        String select = """
                SELECT book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,
                       start_percent,end_percent,current_percent,completed_at
                FROM reading_stats WHERE book_id IN (%s)
                """.formatted(SqliteInClauseSupport.placeholders(ids.size()));
        String upsert = """
                INSERT INTO reading_stats
                    (book_id,first_read_at,last_read_at,total_reading_seconds,reading_sessions,
                     start_percent,end_percent,current_percent,completed_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(book_id) DO UPDATE SET
                    first_read_at=excluded.first_read_at,
                    last_read_at=excluded.last_read_at,
                    total_reading_seconds=excluded.total_reading_seconds,
                    reading_sessions=excluded.reading_sessions,
                    start_percent=excluded.start_percent,
                    end_percent=excluded.end_percent,
                    current_percent=excluded.current_percent,
                    completed_at=excluded.completed_at
                """;
        copyRows(source, target, ids, select, upsert, 9, false);
    }

    private static void copyBookmarks(Connection source, Connection target, List<String> ids) throws SQLException {
        String select = """
                SELECT id,book_id,paragraph_id,char_offset,position,chapter_title,context,created_at
                FROM bookmarks WHERE book_id IN (%s) ORDER BY book_id,created_at,id
                """.formatted(SqliteInClauseSupport.placeholders(ids.size()));
        String insert = """
                INSERT INTO bookmarks
                    (id,book_id,paragraph_id,char_offset,position,chapter_title,context,created_at)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    paragraph_id=excluded.paragraph_id,
                    char_offset=excluded.char_offset,
                    position=excluded.position,
                    chapter_title=excluded.chapter_title,
                    context=excluded.context,
                    created_at=excluded.created_at
                WHERE bookmarks.book_id=excluded.book_id
                """;
        copyRows(source, target, ids, select, insert, 8, true);
    }

    private static void copyReaderPreferences(Connection source, Connection target, List<String> ids) throws SQLException {
        String select = """
                SELECT book_id,preferences_json,updated_at
                FROM reader_book_preferences WHERE book_id IN (%s)
                """.formatted(SqliteInClauseSupport.placeholders(ids.size()));
        String upsert = """
                INSERT INTO reader_book_preferences(book_id,preferences_json,updated_at) VALUES(?,?,?)
                ON CONFLICT(book_id) DO UPDATE SET
                    preferences_json=excluded.preferences_json,
                    updated_at=excluded.updated_at
                """;
        copyRows(source, target, ids, select, upsert, 3, false);
    }

    private static void copyRows(Connection source,
                                 Connection target,
                                 List<String> ids,
                                 String selectSql,
                                 String targetSql,
                                 int columns,
                                 boolean requireEveryRowApplied) throws SQLException {
        try (PreparedStatement read = source.prepareStatement(selectSql);
             PreparedStatement write = target.prepareStatement(targetSql)) {
            for (int i = 0; i < ids.size(); i++) read.setString(i + 1, ids.get(i));
            read.setFetchSize(JDBC_BATCH);
            try (ResultSet rs = read.executeQuery()) {
                int pending = 0;
                while (rs.next()) {
                    for (int column = 1; column <= columns; column++) {
                        write.setObject(column, rs.getObject(column));
                    }
                    write.addBatch();
                    if (++pending >= JDBC_BATCH) {
                        verifyBatch(write.executeBatch(), requireEveryRowApplied);
                        pending = 0;
                    }
                }
                if (pending > 0) verifyBatch(write.executeBatch(), requireEveryRowApplied);
            }
        }
    }


    private static void verifyBatch(int[] updateCounts, boolean requireEveryRowApplied) throws SQLException {
        if (!requireEveryRowApplied || updateCounts == null) return;
        for (int count : updateCounts) {
            if (count == Statement.EXECUTE_FAILED || count == 0) {
                throw new SQLException("Target user-state row conflicts with unrelated existing data");
            }
        }
    }

    private static List<String> normalizeIds(List<BookId> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (BookId id : ids) {
            if (id != null && id.asString() != null && !id.asString().isBlank()) unique.add(id.asString());
        }
        return new ArrayList<>(unique);
    }
}
