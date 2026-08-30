package com.myhomelibcorp.infrastructure.maintenance;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Exact, bounded-heap path set backed by a disposable SQLite database.
 *
 * <p>Maintenance may need to compare hundreds of thousands of catalog file references with
 * the filesystem. Keeping every normalized path in a Java {@code HashSet<String>} duplicates
 * a large part of the catalog in heap. This index keeps only the SQLite page cache in memory
 * while preserving exact membership semantics (unlike a Bloom filter).</p>
 */
@Slf4j
final class DiskBackedPathIndex implements AutoCloseable {
    private static final int BATCH_SIZE = 1_000;

    private final Path databaseFile;
    private final Connection connection;
    private final PreparedStatement insert;
    private final PreparedStatement contains;
    private int pending;
    private boolean sealed;

    private DiskBackedPathIndex(Path databaseFile, Connection connection) throws SQLException {
        this.databaseFile = databaseFile;
        this.connection = connection;
        this.insert = connection.prepareStatement("INSERT OR IGNORE INTO path_refs(path) VALUES (?)");
        this.contains = connection.prepareStatement("SELECT 1 FROM path_refs WHERE path=? LIMIT 1");
    }

    static DiskBackedPathIndex create() {
        Path file = null;
        Connection connection = null;
        try {
            file = Files.createTempFile("myhomelib-maintenance-paths-", ".sqlite");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                // This database is derived and disposable. Durability buys nothing here, while
                // journal writes can dominate a scan with hundreds of thousands of references.
                statement.execute("PRAGMA journal_mode=OFF");
                statement.execute("PRAGMA synchronous=OFF");
                statement.execute("PRAGMA temp_store=MEMORY");
                statement.execute("CREATE TABLE path_refs(path TEXT PRIMARY KEY) WITHOUT ROWID");
            }
            connection.setAutoCommit(false);
            return new DiskBackedPathIndex(file, connection);
        } catch (Exception e) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) { }
            }
            if (file != null) {
                try { Files.deleteIfExists(file); } catch (IOException ignored) { }
            }
            throw new IllegalStateException("Cannot create bounded maintenance path index", e);
        }
    }

    void add(String path) {
        Objects.requireNonNull(path, "path");
        if (sealed) throw new IllegalStateException("Path index is already sealed");
        try {
            insert.setString(1, path);
            insert.addBatch();
            if (++pending >= BATCH_SIZE) flush();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot add path to maintenance index", e);
        }
    }

    void seal() {
        if (sealed) return;
        try {
            flush();
            connection.commit();
            sealed = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot finalize maintenance path index", e);
        }
    }

    boolean contains(String path) {
        Objects.requireNonNull(path, "path");
        if (!sealed) throw new IllegalStateException("Path index must be sealed before lookup");
        try {
            contains.setString(1, path);
            try (var rs = contains.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot query maintenance path index", e);
        }
    }

    private void flush() throws SQLException {
        if (pending == 0) return;
        insert.executeBatch();
        insert.clearBatch();
        pending = 0;
    }

    @Override
    public void close() {
        try { insert.close(); } catch (SQLException ignored) { }
        try { contains.close(); } catch (SQLException ignored) { }
        try { connection.close(); } catch (SQLException e) {
            log.debug("Cannot close maintenance path index cleanly: {}", e.getMessage());
        }
        try { Files.deleteIfExists(databaseFile); } catch (IOException e) {
            log.debug("Cannot delete temporary maintenance path index {}: {}", databaseFile, e.getMessage());
        }
    }
}
