package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.CollectionBookReadPort;
import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.infrastructure.collection.CollectionDatabasePathResolver;
import com.myhomelibcorp.infrastructure.persistence.mapper.GroupRowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Repository;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * Read-only, single-connection query session for a non-active or active collection.
 * It never switches CollectionManager and never opens a write-capable connection.
 */
@Repository
@ConditionalOnProperty(name = "app.database.type", havingValue = "sqlite", matchIfMissing = true)
@RequiredArgsConstructor
public class SqliteCollectionBookReadAdapter implements CollectionBookReadPort {

    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final SqliteBookQueryRepository bookQueries;
    private final GroupRowMapper groupRowMapper;

    @Override
    public Session open(Collection collection) throws IOException {
        if (collection == null) throw new IllegalArgumentException("Collection is required");
        Path database = CollectionDatabasePathResolver.resolve(collection).toAbsolutePath().normalize();
        SafeFilePublication.requireSafeRegularFile(database, "cross-collection group source database");

        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);

        Connection connection = null;
        try {
            connection = config.createConnection("jdbc:sqlite:" + database);
            SafeFilePublication.requireSafeRegularFile(database, "cross-collection group source database");
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
            }
            // Xerial SQLiteConnection.rollback() rejects calls while JDBC auto-commit
            // is enabled. Let the driver own the read transaction so close() can
            // reliably release the snapshot on every exit path.
            connection.setAutoCommit(false);
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int initialDataVersion = dataVersion(connection);
            return new ReadSession(database, connection, jdbc, initialDataVersion);
        } catch (IOException | SQLException | RuntimeException error) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException closeError) { error.addSuppressed(closeError); }
            }
            if (error instanceof IOException io) throw io;
            throw new IOException("Unable to open read-only collection database: " + database, error);
        }
    }

    private static int dataVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA data_version")) {
            if (!result.next()) throw new SQLException("SQLite did not return PRAGMA data_version");
            return result.getInt(1);
        }
    }

    private final class ReadSession implements Session {
        private final Path database;
        private final Connection connection;
        private final JdbcTemplate jdbc;
        private final int initialDataVersion;
        private boolean closed;

        private ReadSession(Path database, Connection connection, JdbcTemplate jdbc, int initialDataVersion) {
            this.database = database;
            this.connection = connection;
            this.jdbc = jdbc;
            this.initialDataVersion = initialDataVersion;
        }

        @Override
        public Optional<Group> findGroupById(Long groupId) {
            if (groupId == null) return Optional.empty();
            List<Group> matches = jdbc.query("SELECT * FROM groups WHERE id = ?", groupRowMapper, groupId);
            return matches.stream().findFirst();
        }

        @Override
        public Optional<Group> findGroupByName(String name) {
            if (name == null || name.isBlank()) return Optional.empty();
            String normalized = name.strip();
            // Resolve all case/whitespace variants together. Returning an exact-case
            // match early would hide a second logical group named "shared" beside "Shared".
            List<Group> matches = jdbc.query("SELECT * FROM groups ORDER BY id", groupRowMapper).stream()
                    .filter(group -> group.getName() != null && group.getName().strip().equalsIgnoreCase(normalized))
                    .limit(2)
                    .toList();
            if (matches.size() > 1) {
                throw new IllegalStateException("Ambiguous group name in collection database: " + name);
            }
            return matches.stream().findFirst();
        }

        @Override
        public PageResult<Book> findPage(BookQuery query) {
            requireGroupQuery(query);
            return bookQueries.findGroupExportPage(jdbc, query);
        }

        @Override
        public PageResult<Book> findTitlePageByCursor(BookQuery query, BookPageCursor cursor,
                                                      BookPageDirection direction, long knownTotal) {
            requireGroupQuery(query);
            return bookQueries.findGroupExportTitlePageByCursor(jdbc, query, cursor, direction, knownTotal);
        }

        private void requireGroupQuery(BookQuery query) {
            if (query == null || query.groupId() == null) {
                throw new IllegalArgumentException("A collection-local group query is required");
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try {
                int finalDataVersion = dataVersion(connection);
                if (finalDataVersion != initialDataVersion) {
                    failure = new IOException("Collection database changed during portable group export: " + database);
                }
            } catch (SQLException error) {
                failure = new IOException("Unable to validate collection database snapshot: " + database, error);
            } finally {
                try { connection.rollback(); }
                catch (SQLException rollbackError) {
                    if (failure == null) failure = new IOException("Unable to close collection read snapshot: " + database, rollbackError);
                    else failure.addSuppressed(rollbackError);
                }
                try { connection.close(); }
                catch (SQLException closeError) {
                    if (failure == null) failure = new IOException("Unable to close collection read connection: " + database, closeError);
                    else failure.addSuppressed(closeError);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
