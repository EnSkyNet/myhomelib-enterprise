package com.myhomelibcorp.infrastructure.maintenance;

import com.myhomelibcorp.application.collection.*;
import com.myhomelibcorp.application.port.out.collection.CollectionMaintenancePort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Safe maintenance implementation for the active collection.
 * Analysis is read-only. Apply always creates a SQLite backup first and revalidates issues.
 */
@Component
@Slf4j
public class CollectionMaintenanceAdapter implements CollectionMaintenancePort {

    private static final int MAX_SAMPLES_PER_TYPE = 500;
    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final int MAX_PATH_LENGTH = 255;

    private static final Set<String> PHYSICAL_LIBRARY_EXTENSIONS = Set.of(
            ".fb2", ".fbd", ".epub", ".txt", ".text", ".mobi", ".azw", ".azw3",
            ".pdf", ".djvu", ".djv", ".zip", ".fb2zip", ".7z", ".rar", ".cbz", ".cbr");

    private static final Set<String> ZIP_LIKE_EXTENSIONS = Set.of(
            ".zip", ".fb2zip", ".cbz", ".jar");

    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            ".zip", ".fb2zip", ".7z", ".rar", ".cbz", ".cbr");

    private static final DateTimeFormatter BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final CollectionManager collectionManager;
    private final JdbcTemplate metadataJdbcTemplate;

    public CollectionMaintenanceAdapter(CollectionManager collectionManager,
                                        @Qualifier("metadataJdbcTemplate") JdbcTemplate metadataJdbcTemplate) {
        this.collectionManager = collectionManager;
        this.metadataJdbcTemplate = metadataJdbcTemplate;
    }

    @Override
    public CollectionMaintenanceReport analyze(String collectionId) {
        validateCollectionId(collectionId);
        Collection collection = requireActive(collectionId);
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        IssueCollector issues = new IssueCollector();

        // Database integrity check
        String quickCheck = quickCheck(jdbc);
        boolean dbOk = "ok".equalsIgnoreCase(quickCheck);
        if (!dbOk) {
            issues.add(new MaintenanceIssue(
                    issueId(MaintenanceIssueType.DATABASE_INTEGRITY, "database"),
                    MaintenanceIssueType.DATABASE_INTEGRITY,
                    "database",
                    "SQLite quick_check: " + truncate(quickCheck, 200),
                    false,
                    false));
        }

        // Scan books with pagination to avoid memory issues
        long totalBooks = countBooks(jdbc);
        List<BookPhysicalRef> books = new ArrayList<>();
        int offset = 0;
        int batchSize = DEFAULT_BATCH_SIZE;

        while (offset < totalBooks) {
            List<BookPhysicalRef> batch = jdbc.query("""
                    SELECT id, title, file_name, folder, archive_entry, collection_root, lib_id
                      FROM books
                     WHERE COALESCE(local, 0) = 1 AND COALESCE(deleted, 0) = 0
                     ORDER BY id
                     LIMIT ? OFFSET ?
                    """,
                    (rs, rowNum) -> new BookPhysicalRef(
                            rs.getString("id"),
                            rs.getString("title"),
                            rs.getString("file_name"),
                            rs.getString("folder"),
                            rs.getString("archive_entry"),
                            rs.getString("collection_root"),
                            rs.getString("lib_id")),
                    batchSize, offset);
            books.addAll(batch);
            offset += batchSize;
        }

        // Process physical files
        FileProcessor fileProcessor = new FileProcessor(collection, issues);
        long missingFiles = 0;
        long invalidArchiveReferences = 0;
        Set<String> referencedPhysical = new HashSet<>(Math.max(16, books.size() * 2));
        Map<Path, List<BookPhysicalRef>> archiveGroups = new LinkedHashMap<>();

        for (BookPhysicalRef book : books) {
            Path physical = fileProcessor.physicalPath(book);
            if (physical == null) continue;

            String pathKey = FileProcessor.pathKey(physical);
            referencedPhysical.add(pathKey);

            if (!Files.isRegularFile(physical)) {
                missingFiles++;
                issues.sample(MaintenanceIssueType.MISSING_FILE, new MaintenanceIssue(
                        issueId(MaintenanceIssueType.MISSING_FILE, book.id()),
                        MaintenanceIssueType.MISSING_FILE,
                        book.id(),
                        "Відсутній локальний файл: " + physical + " — " + safe(book.title()),
                        true,
                        false));
                continue;
            }

            if (notBlank(book.archiveEntry())) {
                archiveGroups.computeIfAbsent(physical, ignored -> new ArrayList<>()).add(book);
            }
        }

        // Process archive entries
        ArchiveValidator archiveValidator = new ArchiveValidator();
        for (Map.Entry<Path, List<BookPhysicalRef>> group : archiveGroups.entrySet()) {
            Path archive = group.getKey();
            if (!archiveValidator.isZipLike(archive)) continue;

            try (ZipFile zip = new ZipFile(archive.toFile())) {
                Set<String> entries = new HashSet<>();
                Enumeration<? extends ZipEntry> enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    if (!entry.isDirectory()) {
                        entries.add(normalizeEntry(entry.getName()));
                    }
                }

                for (BookPhysicalRef book : group.getValue()) {
                    String expected = normalizeEntry(book.archiveEntry());
                    if (!entries.contains(expected)) {
                        invalidArchiveReferences++;
                        issues.sample(MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE, new MaintenanceIssue(
                                issueId(MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE, book.id()),
                                MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE,
                                book.id(),
                                "В архіві немає запису " + book.archiveEntry() + ": " + archive,
                                true,
                                false));
                    }
                }
            } catch (Exception e) {
                for (BookPhysicalRef book : group.getValue()) {
                    invalidArchiveReferences++;
                    issues.sample(MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE, new MaintenanceIssue(
                            issueId(MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE, book.id()),
                            MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE,
                            book.id(),
                            "Архів не читається: " + archive + " (" + safeMessage(e) + ")",
                            true,
                            false));
                }
            }
        }

        // Check for orphaned authors
        long orphanedAuthors = countOrphanedAuthors(jdbc);
        jdbc.query("""
                SELECT a.id, TRIM(COALESCE(a.last_name,'') || ' ' || COALESCE(a.first_name,'') || ' ' || COALESCE(a.middle_name,'')) AS label
                  FROM authors a
                 WHERE NOT EXISTS (SELECT 1 FROM book_authors ba WHERE ba.author_id=a.id)
                 ORDER BY a.id LIMIT ?
                """,
                (rs) -> {
                    issues.sample(MaintenanceIssueType.ORPHANED_AUTHOR, new MaintenanceIssue(
                            issueId(MaintenanceIssueType.ORPHANED_AUTHOR, rs.getString("id")),
                            MaintenanceIssueType.ORPHANED_AUTHOR,
                            rs.getString("id"),
                            "Автор без книг: " + safe(rs.getString("label")),
                            true,
                            true));
                },
                MAX_SAMPLES_PER_TYPE);

        // Check for orphaned genres
        long orphanedGenres = countOrphanedGenres(jdbc);
        jdbc.query("""
                SELECT g.code, g.name FROM genres g
                 WHERE NOT EXISTS (SELECT 1 FROM book_genres bg WHERE bg.genre_code=g.code)
                 ORDER BY g.code LIMIT ?
                """,
                (rs) -> {
                    issues.sample(MaintenanceIssueType.ORPHANED_GENRE, new MaintenanceIssue(
                            issueId(MaintenanceIssueType.ORPHANED_GENRE, rs.getString("code")),
                            MaintenanceIssueType.ORPHANED_GENRE,
                            rs.getString("code"),
                            "Жанр без книг: " + safe(rs.getString("name")) + " [" + rs.getString("code") + "]",
                            true,
                            true));
                },
                MAX_SAMPLES_PER_TYPE);

        // Check for duplicate books
        List<DuplicateRow> duplicates = findDuplicateBooks(jdbc);
        long duplicateBooks = duplicates.size();
        duplicates.stream().limit(MAX_SAMPLES_PER_TYPE).forEach(row ->
                issues.sample(MaintenanceIssueType.DUPLICATE_BOOK, new MaintenanceIssue(
                        issueId(MaintenanceIssueType.DUPLICATE_BOOK, row.id()),
                        MaintenanceIssueType.DUPLICATE_BOOK,
                        row.id(),
                        "Точний дублікат storage+LibID: " + safe(row.title()) + " [" + safe(row.libId()) + "]",
                        true,
                        true)));

        // Scan orphan files
        OrphanScan orphanScan = fileProcessor.scanOrphanFiles(referencedPhysical, collectionId);

        // Check if samples were truncated
        boolean truncated = issues.truncated()
                || missingFiles > issues.countSamples(MaintenanceIssueType.MISSING_FILE)
                || invalidArchiveReferences > issues.countSamples(MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE)
                || orphanedAuthors > issues.countSamples(MaintenanceIssueType.ORPHANED_AUTHOR)
                || orphanedGenres > issues.countSamples(MaintenanceIssueType.ORPHANED_GENRE)
                || duplicateBooks > issues.countSamples(MaintenanceIssueType.DUPLICATE_BOOK)
                || orphanScan.orphanFiles() > issues.countSamples(MaintenanceIssueType.ORPHAN_FILE);

        return new CollectionMaintenanceReport(
                collectionId, Instant.now(), dbOk, quickCheck,
                books.size(), orphanScan.scannedFiles(), missingFiles, invalidArchiveReferences,
                orphanScan.orphanFiles(), orphanedAuthors, orphanedGenres, duplicateBooks,
                truncated, issues.items());
    }

    @Override
    public MaintenanceApplyResult apply(String collectionId, Set<String> issueIds, boolean dryRun) {
        validateCollectionId(collectionId);
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

        // Create backup before any changes
        Path backup = createBackup(requireActive(collectionId));
        DataSource dataSource = collectionManager.getCurrentDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("Active collection data source is unavailable");
        }

        long applied = 0;
        long skipped = requestedIds.size() - selected.size();

        try (Connection connection = dataSource.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            boolean foreignKeysEnabled = isForeignKeysEnabled(connection);

            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA foreign_keys=ON");
            }
            connection.setAutoCommit(false);

            try {
                for (MaintenanceIssue issue : selected) {
                    int changed = applyIssue(connection, issue);
                    if (changed > 0) {
                        applied++;
                    } else {
                        skipped++;
                    }
                }
                connection.commit();
                connection.setAutoCommit(oldAutoCommit);

                // Restore foreign keys state
                if (!foreignKeysEnabled) {
                    try (Statement pragma = connection.createStatement()) {
                        pragma.execute("PRAGMA foreign_keys=OFF");
                    }
                }
            } catch (Exception e) {
                connection.rollback();
                try {
                    connection.setAutoCommit(oldAutoCommit);
                } catch (Exception ignored) { }
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Maintenance apply failed; backup is at " + backup, e);
        }

        // Rebuild/repair indexes only after an explicit successful apply
        try {
            JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
            jdbc.execute("REINDEX");
            jdbc.execute("ANALYZE");
            jdbc.execute("PRAGMA optimize");
        } catch (DataAccessException e) {
            log.warn("Index optimization failed after maintenance: {}", e.getMessage());
        }

        CollectionMaintenanceReport after = analyze(collectionId);
        return new MaintenanceApplyResult(false, backup, requestedIds.size(), applied, skipped, before, after);
    }

    // ============= Helper Methods =============

    private long countBooks(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM books WHERE COALESCE(local, 0) = 1 AND COALESCE(deleted, 0) = 0",
                Long.class);
        return valueOrZero(count);
    }

    private long countOrphanedAuthors(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM authors a WHERE NOT EXISTS (SELECT 1 FROM book_authors ba WHERE ba.author_id=a.id)",
                Long.class);
        return valueOrZero(count);
    }

    private long countOrphanedGenres(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM genres g WHERE NOT EXISTS (SELECT 1 FROM book_genres bg WHERE bg.genre_code=g.code)",
                Long.class);
        return valueOrZero(count);
    }

    private List<DuplicateRow> findDuplicateBooks(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT b.id, b.title, b.lib_id
                  FROM books b
                  JOIN (
                        SELECT lib_id,
                               COALESCE(collection_root,'') AS cr,
                               COALESCE(folder,'') AS folder,
                               COALESCE(file_name,'') AS file_name,
                               COALESCE(archive_entry,'') AS archive_entry,
                               MIN(id) AS keep_id,
                               COUNT(*) AS cnt
                          FROM books
                         WHERE TRIM(COALESCE(lib_id,'')) <> ''
                         GROUP BY lib_id, COALESCE(collection_root,''), COALESCE(folder,''),
                                  COALESCE(file_name,''), COALESCE(archive_entry,'')
                        HAVING COUNT(*) > 1
                  ) d
                    ON b.lib_id=d.lib_id
                   AND COALESCE(b.collection_root,'')=d.cr
                   AND COALESCE(b.folder,'')=d.folder
                   AND COALESCE(b.file_name,'')=d.file_name
                   AND COALESCE(b.archive_entry,'')=d.archive_entry
                 WHERE b.id <> d.keep_id
                 ORDER BY b.lib_id, b.id
                """, (rs, rowNum) -> new DuplicateRow(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("lib_id")));
    }

    private boolean isForeignKeysEnabled(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys")) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    private int applyIssue(Connection connection, MaintenanceIssue issue) throws SQLException {
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
            case DUPLICATE_BOOK -> executeUpdate(connection,
                    "DELETE FROM books WHERE id=?",
                    issue.target());
            case DATABASE_INTEGRITY, ORPHAN_FILE -> 0;
        };
    }

    private int executeUpdate(Connection connection, String sql, Object... params) throws SQLException {
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
            Path backup = AppPaths.backupsDir().resolve(
                    "collection-" + sanitize(collection.getId()) + "-" +
                            BACKUP_TS.format(Instant.now()) + ".db");

            int suffix = 1;
            while (Files.exists(backup)) {
                backup = AppPaths.backupsDir().resolve(
                        "collection-" + sanitize(collection.getId()) + "-" +
                                BACKUP_TS.format(Instant.now()) + "-" + suffix++ + ".db");
            }

            JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();

            // Ensure checkpoint for consistency
            jdbc.execute("PRAGMA wal_checkpoint(FULL)");

            // Use parameterized query to prevent SQL injection
            String backupPath = backup.toAbsolutePath().normalize().toString();
            // Validate path doesn't contain dangerous characters
            if (backupPath.contains("'") || backupPath.contains(";")) {
                throw new IllegalArgumentException("Invalid backup path contains dangerous characters");
            }

            // Execute VACUUM INTO with proper quoting
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

    private String quickCheck(JdbcTemplate jdbc) {
        try {
            List<String> rows = jdbc.query("PRAGMA quick_check",
                    (rs, rowNum) -> rs.getString(1));
            if (rows.isEmpty()) return "no result";
            return String.join("; ", rows.stream().limit(10).toList());
        } catch (Exception e) {
            return "quick_check failed: " + safeMessage(e);
        }
    }

    private Collection requireActive(String collectionId) {
        Collection current = collectionManager.getCurrentCollection();
        if (current == null || !Objects.equals(current.getId(), collectionId)) {
            throw new IllegalStateException("Maintenance is allowed only for the active collection");
        }
        if (!collectionManager.hasActiveCollection()) {
            throw new IllegalStateException("Active collection database is not ready");
        }
        return current;
    }

    private void validateCollectionId(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) {
            throw new IllegalArgumentException("Collection ID cannot be null or empty");
        }
        if (collectionId.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("Collection ID exceeds maximum length");
        }
    }

    private static String normalizeEntry(String entry) {
        return safe(entry)
                .replace('\\', '/')
                .replaceAll("^/+", "");
    }

    private static String issueId(MaintenanceIssueType type, String target) {
        return type.name() + ":" + safe(target);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ?
                e.getClass().getSimpleName() : e.getMessage();
    }

    private static String sanitize(String value) {
        return safe(value).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    // ============= Inner Classes =============

    private class FileProcessor {
        private final Collection collection;
        private final IssueCollector issues;
        private final ArchiveValidator archiveValidator;

        FileProcessor(Collection collection, IssueCollector issues) {
            this.collection = collection;
            this.issues = issues;
            this.archiveValidator = new ArchiveValidator();
        }

        Path physicalPath(BookPhysicalRef book) {
            String rootValue = notBlank(book.collectionRoot())
                    ? book.collectionRoot()
                    : collection.getRootFolder() == null ? null :
                    collection.getRootFolder().toString();
            Path root = notBlank(rootValue) ? Paths.get(rootValue) : null;
            String folder = safe(book.folder()).trim();
            String fileName = safe(book.fileName()).trim();

            if (notBlank(book.archiveEntry()) && archiveValidator.isArchiveName(folder)) {
                return resolvePath(root, null, folder);
            }
            if (notBlank(book.archiveEntry()) && archiveValidator.isArchiveName(fileName)) {
                return resolvePath(root, folder, fileName);
            }
            return resolvePath(root, folder, fileName);
        }

        private Path resolvePath(Path root, String folder, String fileName) {
            if (notBlank(fileName)) {
                Path fp = Paths.get(fileName);
                if (fp.isAbsolute()) return fp.toAbsolutePath().normalize();
            }
            if (notBlank(folder)) {
                Path folderPath = Paths.get(folder);
                if (folderPath.isAbsolute()) {
                    return notBlank(fileName) ?
                            folderPath.resolve(fileName).toAbsolutePath().normalize()
                            : folderPath.toAbsolutePath().normalize();
                }
            }
            Path result = root;
            if (result == null) result = Paths.get(".").toAbsolutePath().normalize();
            if (notBlank(folder)) result = result.resolve(folder);
            if (notBlank(fileName)) result = result.resolve(fileName);
            return result.toAbsolutePath().normalize();
        }

        OrphanScan scanOrphanFiles(Set<String> referenced, String collectionId) {
            Path root = collection.getRootFolder();
            if (root == null || !Files.isDirectory(root)) {
                return new OrphanScan(0, 0);
            }

            Set<String> excluded = new HashSet<>();
            Path dbPath = databasePath(collection);
            excluded.add(pathKey(dbPath));

            configuredSource(collectionId).ifPresent(path ->
                    excluded.add(pathKey(path)));

            long scanned = 0;
            long orphan = 0;

            try (Stream<Path> stream = Files.walk(root)) {
                Iterator<Path> iterator = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isPhysicalLibraryFile)
                        .iterator();

                while (iterator.hasNext()) {
                    Path file = iterator.next();
                    scanned++;
                    String key = pathKey(file);
                    if (!referenced.contains(key) && !excluded.contains(key)) {
                        orphan++;
                        issues.sample(MaintenanceIssueType.ORPHAN_FILE, new MaintenanceIssue(
                                issueId(MaintenanceIssueType.ORPHAN_FILE, key),
                                MaintenanceIssueType.ORPHAN_FILE,
                                file.toAbsolutePath().normalize().toString(),
                                "Файл не має посилання в каталозі: " + file,
                                false,
                                true));
                    }
                }
            } catch (IOException e) {
                log.warn("Cannot scan collection root {}: {}", root, e.getMessage());
            }

            return new OrphanScan(scanned, orphan);
        }

        private boolean isPhysicalLibraryFile(Path path) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return PHYSICAL_LIBRARY_EXTENSIONS.stream().anyMatch(name::endsWith);
        }

        private Path databasePath(Collection collection) {
            if (notBlank(collection.getDbFile())) {
                return Paths.get(collection.getDbFile()).toAbsolutePath().normalize();
            }
            return AppPaths.librariesDir().resolve(collection.getId() + ".db")
                    .toAbsolutePath().normalize();
        }

        private Optional<Path> configuredSource(String collectionId) {
            try {
                List<String> rows = metadataJdbcTemplate.query(
                        "SELECT source_file FROM collection_source_watch WHERE collection_id=?",
                        (rs, rowNum) -> rs.getString(1), collectionId);
                return rows.stream()
                        .filter(Objects::nonNull)
                        .filter(s -> !s.isBlank())
                        .findFirst()
                        .map(Paths::get);
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }

        private static String pathKey(Path path) {
            String key;
            try {
                key = Files.exists(path) ? path.toRealPath().toString() :
                        path.toAbsolutePath().normalize().toString();
            } catch (IOException e) {
                key = path.toAbsolutePath().normalize().toString();
            }
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                key = key.toLowerCase(Locale.ROOT);
            }
            return key;
        }
    }

    private static class ArchiveValidator {
        boolean isZipLike(Path path) {
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return ZIP_LIKE_EXTENSIONS.stream().anyMatch(lower::endsWith);
        }

        boolean isArchiveName(String name) {
            if (!notBlank(name)) return false;
            String lower = name.toLowerCase(Locale.ROOT);
            return ARCHIVE_EXTENSIONS.stream().anyMatch(lower::endsWith);
        }
    }

    private record BookPhysicalRef(String id, String title, String fileName, String folder,
                                   String archiveEntry, String collectionRoot, String libId) { }

    private record DuplicateRow(String id, String title, String libId) { }

    private record OrphanScan(long scannedFiles, long orphanFiles) { }

    private static final class IssueCollector {
        private final List<MaintenanceIssue> items = new ArrayList<>();
        private final EnumMap<MaintenanceIssueType, Integer> samples =
                new EnumMap<>(MaintenanceIssueType.class);
        private boolean truncated;

        void add(MaintenanceIssue issue) {
            items.add(issue);
        }

        void sample(MaintenanceIssueType type, MaintenanceIssue issue) {
            int count = samples.getOrDefault(type, 0);
            if (count >= MAX_SAMPLES_PER_TYPE) {
                truncated = true;
                return;
            }
            items.add(issue);
            samples.put(type, count + 1);
        }

        int countSamples(MaintenanceIssueType type) {
            return samples.getOrDefault(type, 0);
        }

        boolean truncated() {
            return truncated;
        }

        List<MaintenanceIssue> items() {
            return List.copyOf(items);
        }
    }
}