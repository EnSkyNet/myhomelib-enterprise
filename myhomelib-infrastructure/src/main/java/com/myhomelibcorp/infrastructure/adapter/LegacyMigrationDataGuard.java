package com.myhomelibcorp.infrastructure.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Locale;

/**
 * Preserves data around two historical schema migrations that are intentionally left immutable
 * because released Flyway migrations must never have their checksums rewritten.
 *
 * <p>V7 collapsed authors by {@code (first_name,last_name)} even though a later schema correctly
 * treats names as lookup attributes rather than identities. When upgrading a database that has
 * not reached V7 yet, duplicate-name author rows and their book links are copied to durable guard
 * tables before Flyway runs and restored after the later V34 migration removes the old unique
 * index.</p>
 *
 * <p>V26 recreates {@code reading_progress} but copies only the pre-V25 columns. A database that
 * was used while exactly on V25 can therefore contain chapter/read-time fields that V26 would
 * otherwise discard. Those fields are guarded in the same way.</p>
 *
 * <p>The guard tables deliberately survive a process crash. Restoration is idempotent and the
 * tables are dropped only after the guarded rows have been verified in the final schema.</p>
 */
@Component
@Slf4j
public class LegacyMigrationDataGuard {
    static final String AUTHOR_GUARD = "mhl_guard_v7_authors";
    static final String AUTHOR_LINK_GUARD = "mhl_guard_v7_book_authors";
    static final String READING_GUARD = "mhl_guard_v26_reading_progress";

    public void captureBeforeMigrate(DataSource dataSource, String currentVersion) {
        if (dataSource == null) return;
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer version = parseIntegerVersion(currentVersion);

        // A null version can represent a legacy, non-Flyway database. Schema checks below make
        // capture safe for an actually empty fresh database.
        if ((version == null || version < 7) && tableExists(jdbc, "authors") && tableExists(jdbc, "book_authors")) {
            capturePreV7Authors(dataSource, jdbc);
        }

        if ((version == null || version <= 25)
                && tableExists(jdbc, "reading_progress")
                && columnExists(jdbc, "reading_progress", "chapter_title")
                && columnExists(jdbc, "reading_progress", "chapter_id")
                && columnExists(jdbc, "reading_progress", "reading_time_seconds")) {
            captureV25ReadingProgress(dataSource, jdbc);
        }
    }

    public void restoreAfterMigrate(DataSource dataSource) {
        if (dataSource == null) return;
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        if (tableExists(jdbc, AUTHOR_GUARD) || tableExists(jdbc, AUTHOR_LINK_GUARD)) {
            restorePreV7Authors(dataSource, jdbc);
        }
        if (tableExists(jdbc, READING_GUARD)) {
            restoreV25ReadingProgress(dataSource, jdbc);
        }
    }

    private void capturePreV7Authors(DataSource dataSource, JdbcTemplate jdbc) {
        if (tableExists(jdbc, AUTHOR_GUARD) || tableExists(jdbc, AUTHOR_LINK_GUARD)) {
            log.info("Legacy V7 author guard already exists; keeping the earlier durable snapshot");
            return;
        }

        Integer atRisk = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM authors a
                  JOIN (
                        SELECT first_name, last_name
                          FROM authors
                         GROUP BY first_name, last_name
                        HAVING COUNT(*) > 1
                       ) d
                    ON a.first_name IS d.first_name AND a.last_name IS d.last_name
                """, Integer.class);
        if (atRisk == null || atRisk == 0) return;

        transaction(dataSource).executeWithoutResult(status -> {
            jdbc.execute("""
                    CREATE TABLE mhl_guard_v7_authors (
                        id TEXT PRIMARY KEY,
                        first_name TEXT,
                        middle_name TEXT,
                        last_name TEXT
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE mhl_guard_v7_book_authors (
                        book_id TEXT NOT NULL,
                        author_id TEXT NOT NULL,
                        PRIMARY KEY (book_id, author_id)
                    )
                    """);
            jdbc.update("""
                    INSERT INTO mhl_guard_v7_authors(id, first_name, middle_name, last_name)
                    SELECT a.id, a.first_name, a.middle_name, a.last_name
                      FROM authors a
                      JOIN (
                            SELECT first_name, last_name
                              FROM authors
                             GROUP BY first_name, last_name
                            HAVING COUNT(*) > 1
                           ) d
                        ON a.first_name IS d.first_name AND a.last_name IS d.last_name
                    """);
            jdbc.update("""
                    INSERT INTO mhl_guard_v7_book_authors(book_id, author_id)
                    SELECT ba.book_id, ba.author_id
                      FROM book_authors ba
                      JOIN mhl_guard_v7_authors g ON g.id = ba.author_id
                    """);
        });
        log.warn("Preserved {} author row(s) before historical V7 name-collapse migration", atRisk);
    }

    private void restorePreV7Authors(DataSource dataSource, JdbcTemplate jdbc) {
        if (!tableExists(jdbc, AUTHOR_GUARD)) {
            throw new IllegalStateException("Legacy author-link guard exists without its author snapshot");
        }
        if (!tableExists(jdbc, AUTHOR_LINK_GUARD)) {
            throw new IllegalStateException("Legacy author guard exists without its book-link snapshot");
        }
        if (!tableExists(jdbc, "authors") || !tableExists(jdbc, "book_authors")) {
            throw new IllegalStateException("Final author schema is unavailable for guarded migration restore");
        }
        // V34 must have removed this uniqueness rule; restoring before that point would collapse
        // the same rows again. Keeping the guard is safer than pretending recovery succeeded.
        if (indexExists(jdbc, "idx_authors_unique_name")) {
            throw new IllegalStateException("Cannot restore guarded authors while idx_authors_unique_name still exists");
        }

        transaction(dataSource).executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT OR IGNORE INTO authors(id, first_name, middle_name, last_name)
                    SELECT id, first_name, middle_name, last_name
                      FROM mhl_guard_v7_authors
                    """);
            jdbc.update("""
                    INSERT OR IGNORE INTO book_authors(book_id, author_id)
                    SELECT g.book_id, g.author_id
                      FROM mhl_guard_v7_book_authors g
                     WHERE EXISTS (SELECT 1 FROM books b WHERE b.id = g.book_id)
                       AND EXISTS (SELECT 1 FROM authors a WHERE a.id = g.author_id)
                    """);

            int expectedAuthors = count(jdbc, "SELECT COUNT(*) FROM mhl_guard_v7_authors");
            int restoredAuthors = count(jdbc, """
                    SELECT COUNT(*)
                      FROM mhl_guard_v7_authors g
                      JOIN authors a ON a.id = g.id
                    """);
            if (expectedAuthors != restoredAuthors) {
                throw new IllegalStateException("Legacy author restore incomplete: expected="
                        + expectedAuthors + ", restored=" + restoredAuthors);
            }

            int expectedLinks = count(jdbc, """
                    SELECT COUNT(*)
                      FROM mhl_guard_v7_book_authors g
                     WHERE EXISTS (SELECT 1 FROM books b WHERE b.id = g.book_id)
                    """);
            int restoredLinks = count(jdbc, """
                    SELECT COUNT(*)
                      FROM mhl_guard_v7_book_authors g
                      JOIN book_authors ba ON ba.book_id = g.book_id AND ba.author_id = g.author_id
                     WHERE EXISTS (SELECT 1 FROM books b WHERE b.id = g.book_id)
                    """);
            if (expectedLinks != restoredLinks) {
                throw new IllegalStateException("Legacy author-link restore incomplete: expected="
                        + expectedLinks + ", restored=" + restoredLinks);
            }

            jdbc.execute("DROP TABLE mhl_guard_v7_book_authors");
            jdbc.execute("DROP TABLE mhl_guard_v7_authors");
            log.info("Restored {} guarded legacy author row(s) and {} book-author link(s)",
                    restoredAuthors, restoredLinks);
        });
    }

    private void captureV25ReadingProgress(DataSource dataSource, JdbcTemplate jdbc) {
        if (tableExists(jdbc, READING_GUARD)) {
            log.info("Legacy V26 reading-progress guard already exists; keeping the earlier durable snapshot");
            return;
        }
        int rows = count(jdbc, "SELECT COUNT(*) FROM reading_progress");
        if (rows == 0) return;

        transaction(dataSource).executeWithoutResult(status -> jdbc.execute("""
                CREATE TABLE mhl_guard_v26_reading_progress AS
                SELECT book_id, chapter_title, chapter_id, reading_time_seconds
                  FROM reading_progress
                """));
        log.warn("Preserved {} reading-progress row(s) before historical V26 table recreation", rows);
    }

    private void restoreV25ReadingProgress(DataSource dataSource, JdbcTemplate jdbc) {
        if (!tableExists(jdbc, "reading_progress")) {
            throw new IllegalStateException("Final reading_progress schema is unavailable for guarded migration restore");
        }
        for (String column : new String[]{"chapter_title", "chapter_id", "reading_time_seconds"}) {
            if (!columnExists(jdbc, "reading_progress", column)) {
                throw new IllegalStateException("Final reading_progress column is unavailable: " + column);
            }
        }

        transaction(dataSource).executeWithoutResult(status -> {
            int expected = count(jdbc, "SELECT COUNT(*) FROM mhl_guard_v26_reading_progress");
            jdbc.update("""
                    UPDATE reading_progress
                       SET chapter_title = (SELECT g.chapter_title FROM mhl_guard_v26_reading_progress g WHERE g.book_id = reading_progress.book_id),
                           chapter_id = (SELECT g.chapter_id FROM mhl_guard_v26_reading_progress g WHERE g.book_id = reading_progress.book_id),
                           reading_time_seconds = (SELECT g.reading_time_seconds FROM mhl_guard_v26_reading_progress g WHERE g.book_id = reading_progress.book_id)
                     WHERE EXISTS (SELECT 1 FROM mhl_guard_v26_reading_progress g WHERE g.book_id = reading_progress.book_id)
                    """);
            int restored = count(jdbc, """
                    SELECT COUNT(*)
                      FROM mhl_guard_v26_reading_progress g
                      JOIN reading_progress r ON r.book_id = g.book_id
                     WHERE r.chapter_title IS g.chapter_title
                       AND r.chapter_id IS g.chapter_id
                       AND r.reading_time_seconds IS g.reading_time_seconds
                    """);
            if (expected != restored) {
                throw new IllegalStateException("Legacy reading-progress restore incomplete: expected="
                        + expected + ", restored=" + restored);
            }
            jdbc.execute("DROP TABLE mhl_guard_v26_reading_progress");
            log.info("Restored {} guarded V25 reading-progress row(s)", restored);
        });
    }

    private static TransactionTemplate transaction(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static boolean tableExists(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, name);
        return count != null && count > 0;
    }

    private static boolean indexExists(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?", Integer.class, name);
        return count != null && count > 0;
    }

    private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        String safeTable = table.replace("\"", "\"\"");
        return jdbc.query("PRAGMA table_info(\"" + safeTable + "\")",
                rs -> {
                    while (rs.next()) {
                        if (column.equalsIgnoreCase(rs.getString("name"))) return true;
                    }
                    return false;
                });
    }

    private static Integer parseIntegerVersion(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int dot = normalized.indexOf('.');
        if (dot >= 0) normalized = normalized.substring(0, dot);
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
