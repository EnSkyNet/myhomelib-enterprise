package com.myhomelibcorp.infrastructure.maintenance;

import com.myhomelibcorp.application.collection.CollectionMaintenanceReport;
import com.myhomelibcorp.application.collection.MaintenanceApplyResult;
import com.myhomelibcorp.application.collection.MaintenanceIssue;
import com.myhomelibcorp.application.port.out.collection.CollectionMaintenancePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Safe maintenance command adapter for the active collection.
 * Read-only analysis is delegated to {@link CollectionMaintenanceAnalyzer}; apply always backs up first.
 */
@Component
@Slf4j
public class CollectionMaintenanceAdapter implements CollectionMaintenancePort {

    private static final DateTimeFormatter BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final CollectionManager collectionManager;
    private final CollectionMaintenanceAnalyzer analyzer;

    public CollectionMaintenanceAdapter(CollectionManager collectionManager,
                                        @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate) {
        this.collectionManager = Objects.requireNonNull(collectionManager, "collectionManager");
        this.analyzer = new CollectionMaintenanceAnalyzer(collectionManager, metadataJdbcTemplate);
    }

    @Override
    public CollectionMaintenanceReport analyze(String collectionId) {
        return analyzer.analyze(collectionId);
    }

    @Override
    public MaintenanceApplyResult apply(String collectionId, Set<String> issueIds, boolean dryRun) {
        analyzer.validateCollectionId(collectionId);
        CollectionMaintenanceReport before = analyze(collectionId);

        Map<String, MaintenanceIssue> repairable = new LinkedHashMap<>();
        before.issues().stream()
                .filter(MaintenanceIssue::repairable)
                .forEach(issue -> repairable.put(issue.issueId(), issue));

        Set<String> requestedIds = issueIds == null || issueIds.isEmpty()
                ? new LinkedHashSet<>(repairable.keySet())
                : new LinkedHashSet<>(issueIds);

        List<MaintenanceIssue> selected = requestedIds.stream()
                .map(repairable::get)
                .filter(Objects::nonNull)
                .toList();

        if (dryRun) {
            return new MaintenanceApplyResult(true, null, requestedIds.size(), 0,
                    requestedIds.size() - selected.size(), before, before);
        }

        if (selected.isEmpty()) {
            return new MaintenanceApplyResult(false, null, requestedIds.size(), 0,
                    requestedIds.size(), before, before);
        }

        Path backup = createBackup(analyzer.requireActive(collectionId));
        DataSource dataSource = collectionManager.getCurrentDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("Active collection data source is unavailable");
        }

        long applied = 0;
        long skipped = requestedIds.size() - selected.size();

        try (Connection connection = dataSource.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            boolean foreignKeysEnabled = isForeignKeysEnabled(connection);
            Throwable failure = null;

            try {
                if (!foreignKeysEnabled) {
                    setForeignKeys(connection, true);
                }
                connection.setAutoCommit(false);

                for (MaintenanceIssue issue : selected) {
                    int changed = applyIssue(connection, issue);
                    if (changed > 0) applied++;
                    else skipped++;
                }
                connection.commit();
            } catch (Exception e) {
                failure = e;
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                restoreConnectionState(connection, oldAutoCommit, foreignKeysEnabled, failure);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Maintenance apply failed; backup is at " + backup, e);
        }

        optimizeAfterApply();

        CollectionMaintenanceReport after = analyze(collectionId);
        return new MaintenanceApplyResult(false, backup, requestedIds.size(), applied, skipped, before, after);
    }

    private void restoreConnectionState(Connection connection, boolean oldAutoCommit,
                                        boolean foreignKeysEnabled, Throwable primaryFailure) {
        try {
            if (connection.getAutoCommit() != oldAutoCommit) {
                connection.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            if (primaryFailure != null) primaryFailure.addSuppressed(e);
            else log.warn("Cannot restore maintenance autoCommit state: {}", e.getMessage());
        }

        if (!foreignKeysEnabled) {
            try {
                setForeignKeys(connection, false);
            } catch (SQLException e) {
                if (primaryFailure != null) primaryFailure.addSuppressed(e);
                else log.warn("Cannot restore maintenance foreign_keys state: {}", e.getMessage());
            }
        }
    }

    private void optimizeAfterApply() {
        try {
            JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
            jdbc.execute("REINDEX");
            jdbc.execute("ANALYZE");
            jdbc.execute("PRAGMA optimize");
        } catch (DataAccessException e) {
            log.warn("Index optimization failed after maintenance: {}", e.getMessage());
        }
    }

    private static boolean isForeignKeysEnabled(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys")) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    private static void setForeignKeys(Connection connection, boolean enabled) throws SQLException {
        try (Statement pragma = connection.createStatement()) {
            pragma.execute("PRAGMA foreign_keys=" + (enabled ? "ON" : "OFF"));
        }
    }

    private static int applyIssue(Connection connection, MaintenanceIssue issue) throws SQLException {
        return switch (issue.type()) {
            case MISSING_FILE, INVALID_ARCHIVE_REFERENCE -> executeUpdate(connection,
                    "UPDATE books SET local=0 WHERE id=? AND COALESCE(local,0)=1",
                    issue.target());
            case ORPHANED_AUTHOR -> executeUpdate(connection,
                    "DELETE FROM authors WHERE id=? AND NOT EXISTS (SELECT 1 FROM book_authors WHERE author_id=?)",
                    issue.target(), issue.target());
            case ORPHANED_GENRE -> executeUpdate(connection,
                    "DELETE FROM genres WHERE code=? AND NOT EXISTS (SELECT 1 FROM book_genres WHERE genre_code=?)",
                    issue.target(), issue.target());
            case DUPLICATE_BOOK -> executeUpdate(connection, "DELETE FROM books WHERE id=?", issue.target());
            case DATABASE_INTEGRITY, ORPHAN_FILE -> 0;
        };
    }

    private static int executeUpdate(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    private Path createBackup(Collection collection) {
        try {
            Files.createDirectories(AppPaths.backupsDir());
            String prefix = "collection-" + sanitize(collection.getId()) + "-" + BACKUP_TS.format(Instant.now());
            Path backup = AppPaths.backupsDir().resolve(prefix + ".db");
            int suffix = 1;
            while (Files.exists(backup)) {
                backup = AppPaths.backupsDir().resolve(prefix + "-" + suffix++ + ".db");
            }

            JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
            jdbc.execute("PRAGMA wal_checkpoint(FULL)");

            String backupPath = backup.toAbsolutePath().normalize().toString();
            String quotedPath = backupPath.replace("'", "''");
            jdbc.execute("VACUUM INTO '" + quotedPath + "'");

            if (!Files.isRegularFile(backup) || Files.size(backup) == 0) {
                throw new IOException("SQLite VACUUM INTO produced no backup");
            }
            return backup;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create mandatory collection backup", e);
        }
    }

    private static String sanitize(String value) {
        return (value == null ? "" : value).replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
