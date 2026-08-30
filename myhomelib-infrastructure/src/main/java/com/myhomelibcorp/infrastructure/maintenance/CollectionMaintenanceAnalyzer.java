package com.myhomelibcorp.infrastructure.maintenance;

import com.myhomelibcorp.application.collection.CollectionMaintenanceReport;
import com.myhomelibcorp.application.collection.MaintenanceIssue;
import com.myhomelibcorp.application.collection.MaintenanceIssueType;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only, bounded-memory analysis for collection maintenance.
 *
 * <p>The analyzer deliberately uses keyset traversal for the books table and samples issue details.
 * Exact counters are kept separately from samples so a 1M+ collection does not require a 1M-object list.</p>
 */
@Slf4j
final class CollectionMaintenanceAnalyzer {

    static final int MAX_SAMPLES_PER_TYPE = 500;
    private static final int DEFAULT_BATCH_SIZE = 1_000;
    private static final int MAX_PATH_LENGTH = 255;
    private static final int MAX_OPEN_ARCHIVES = 4;

    private static final Set<String> PHYSICAL_LIBRARY_EXTENSIONS = Set.of(
            ".fb2", ".fbd", ".epub", ".txt", ".text", ".mobi", ".azw", ".azw3",
            ".pdf", ".djvu", ".djv", ".zip", ".fb2zip", ".7z", ".rar", ".cbz", ".cbr");

    private static final Set<String> ZIP_LIKE_EXTENSIONS = Set.of(
            ".zip", ".fb2zip", ".cbz", ".jar");

    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            ".zip", ".fb2zip", ".7z", ".rar", ".cbz", ".cbr");

    private final CollectionManager collectionManager;
    private final JdbcTemplate metadataJdbcTemplate;

    CollectionMaintenanceAnalyzer(CollectionManager collectionManager, JdbcTemplate metadataJdbcTemplate) {
        this.collectionManager = Objects.requireNonNull(collectionManager, "collectionManager");
        this.metadataJdbcTemplate = Objects.requireNonNull(metadataJdbcTemplate, "metadataJdbcTemplate");
    }

    CollectionMaintenanceReport analyze(String collectionId) {
        validateCollectionId(collectionId);
        Collection collection = requireActive(collectionId);
        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        IssueCollector issues = new IssueCollector();

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

        FileProcessor fileProcessor = new FileProcessor(collection, issues);
        boolean scanOrphans = fileProcessor.canScanOrphanFiles();

        long scannedBooks = 0;
        long missingFiles = 0;
        long invalidArchiveReferences = 0;
        String afterId = "";
        OrphanScan orphanScan;

        try (ArchiveValidator archiveValidator = new ArchiveValidator();
             DiskBackedPathIndex referencedPhysical = scanOrphans ? DiskBackedPathIndex.create() : null) {
            while (true) {
                List<BookPhysicalRef> batch = loadBookBatch(jdbc, afterId);
                if (batch.isEmpty()) break;

                for (BookPhysicalRef book : batch) {
                    scannedBooks++;
                    afterId = book.id();
                    Path physical = fileProcessor.physicalPath(book);
                    if (physical == null) continue;

                    if (referencedPhysical != null) {
                        referencedPhysical.add(FileProcessor.pathKey(physical));
                    }

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

                    if (notBlank(book.archiveEntry()) && archiveValidator.isZipLike(physical)) {
                        ArchiveValidation validation = archiveValidator.validate(physical, book.archiveEntry());
                        if (!validation.valid()) {
                            invalidArchiveReferences++;
                            String description = validation.error() == null
                                    ? "В архіві немає запису " + book.archiveEntry() + ": " + physical
                                    : "Архів не читається: " + physical + " (" + validation.error() + ")";
                            issues.sample(MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE, new MaintenanceIssue(
                                    issueId(MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE, book.id()),
                                    MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE,
                                    book.id(),
                                    description,
                                    true,
                                    false));
                        }
                    }
                }
            }

            if (referencedPhysical != null) referencedPhysical.seal();
            orphanScan = fileProcessor.scanOrphanFiles(referencedPhysical, collectionId);
        }

        long orphanedAuthors = countOrphanedAuthors(jdbc);
        sampleOrphanedAuthors(jdbc, issues);

        long orphanedGenres = countOrphanedGenres(jdbc);
        sampleOrphanedGenres(jdbc, issues);

        long duplicateBooks = countDuplicateBooks(jdbc);
        sampleDuplicateBooks(jdbc, issues);

        boolean truncated = issues.truncated()
                || missingFiles > issues.countSamples(MaintenanceIssueType.MISSING_FILE)
                || invalidArchiveReferences > issues.countSamples(MaintenanceIssueType.INVALID_ARCHIVE_REFERENCE)
                || orphanedAuthors > issues.countSamples(MaintenanceIssueType.ORPHANED_AUTHOR)
                || orphanedGenres > issues.countSamples(MaintenanceIssueType.ORPHANED_GENRE)
                || duplicateBooks > issues.countSamples(MaintenanceIssueType.DUPLICATE_BOOK)
                || orphanScan.orphanFiles() > issues.countSamples(MaintenanceIssueType.ORPHAN_FILE);

        return new CollectionMaintenanceReport(
                collectionId, Instant.now(), dbOk, quickCheck,
                scannedBooks, orphanScan.scannedFiles(), missingFiles, invalidArchiveReferences,
                orphanScan.orphanFiles(), orphanedAuthors, orphanedGenres, duplicateBooks,
                truncated, issues.items());
    }

    Collection requireActive(String collectionId) {
        Collection current = collectionManager.getCurrentCollection();
        if (current == null || !Objects.equals(current.getId(), collectionId)) {
            throw new IllegalStateException("Maintenance is allowed only for the active collection");
        }
        if (!collectionManager.hasActiveCollection()) {
            throw new IllegalStateException("Active collection database is not ready");
        }
        return current;
    }

    void validateCollectionId(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) {
            throw new IllegalArgumentException("Collection ID cannot be null or empty");
        }
        if (collectionId.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("Collection ID exceeds maximum length");
        }
    }

    private List<BookPhysicalRef> loadBookBatch(JdbcTemplate jdbc, String afterId) {
        return jdbc.query("""
                SELECT id, title, file_name, folder, archive_entry, collection_root, lib_id
                  FROM books
                 WHERE COALESCE(local, 0) = 1
                   AND COALESCE(deleted, 0) = 0
                   AND id > ?
                 ORDER BY id
                 LIMIT ?
                """,
                (rs, rowNum) -> new BookPhysicalRef(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("file_name"),
                        rs.getString("folder"),
                        rs.getString("archive_entry"),
                        rs.getString("collection_root"),
                        rs.getString("lib_id")),
                afterId, DEFAULT_BATCH_SIZE);
    }

    private long countOrphanedAuthors(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM authors a WHERE NOT EXISTS (SELECT 1 FROM book_authors ba WHERE ba.author_id=a.id)",
                Long.class);
        return valueOrZero(count);
    }

    private void sampleOrphanedAuthors(JdbcTemplate jdbc, IssueCollector issues) {
        jdbc.query("""
                SELECT a.id, TRIM(COALESCE(a.last_name,'') || ' ' || COALESCE(a.first_name,'') || ' ' || COALESCE(a.middle_name,'')) AS label
                  FROM authors a
                 WHERE NOT EXISTS (SELECT 1 FROM book_authors ba WHERE ba.author_id=a.id)
                 ORDER BY a.id LIMIT ?
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> issues.sample(MaintenanceIssueType.ORPHANED_AUTHOR, new MaintenanceIssue(
                        issueId(MaintenanceIssueType.ORPHANED_AUTHOR, rs.getString("id")),
                        MaintenanceIssueType.ORPHANED_AUTHOR,
                        rs.getString("id"),
                        "Автор без книг: " + safe(rs.getString("label")),
                        true,
                        true)),
                MAX_SAMPLES_PER_TYPE);
    }

    private long countOrphanedGenres(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM genres g WHERE NOT EXISTS (SELECT 1 FROM book_genres bg WHERE bg.genre_code=g.code)",
                Long.class);
        return valueOrZero(count);
    }

    private void sampleOrphanedGenres(JdbcTemplate jdbc, IssueCollector issues) {
        jdbc.query("""
                SELECT g.code, g.name FROM genres g
                 WHERE NOT EXISTS (SELECT 1 FROM book_genres bg WHERE bg.genre_code=g.code)
                 ORDER BY g.code LIMIT ?
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> issues.sample(MaintenanceIssueType.ORPHANED_GENRE, new MaintenanceIssue(
                        issueId(MaintenanceIssueType.ORPHANED_GENRE, rs.getString("code")),
                        MaintenanceIssueType.ORPHANED_GENRE,
                        rs.getString("code"),
                        "Жанр без книг: " + safe(rs.getString("name")) + " [" + rs.getString("code") + "]",
                        true,
                        true)),
                MAX_SAMPLES_PER_TYPE);
    }

    private long countDuplicateBooks(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject("""
                SELECT COALESCE(SUM(cnt - 1), 0)
                  FROM (
                        SELECT COUNT(*) AS cnt
                          FROM books
                         WHERE TRIM(COALESCE(lib_id,'')) <> ''
                         GROUP BY lib_id, COALESCE(collection_root,''), COALESCE(folder,''),
                                  COALESCE(file_name,''), COALESCE(archive_entry,'')
                        HAVING COUNT(*) > 1
                  ) d
                """, Long.class);
        return valueOrZero(count);
    }

    private void sampleDuplicateBooks(JdbcTemplate jdbc, IssueCollector issues) {
        List<DuplicateRow> samples = jdbc.query("""
                SELECT b.id, b.title, b.lib_id
                  FROM books b
                  JOIN (
                        SELECT lib_id,
                               COALESCE(collection_root,'') AS cr,
                               COALESCE(folder,'') AS folder,
                               COALESCE(file_name,'') AS file_name,
                               COALESCE(archive_entry,'') AS archive_entry,
                               MIN(id) AS keep_id
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
                 LIMIT ?
                """, (rs, rowNum) -> new DuplicateRow(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("lib_id")), MAX_SAMPLES_PER_TYPE);

        for (DuplicateRow row : samples) {
            issues.sample(MaintenanceIssueType.DUPLICATE_BOOK, new MaintenanceIssue(
                    issueId(MaintenanceIssueType.DUPLICATE_BOOK, row.id()),
                    MaintenanceIssueType.DUPLICATE_BOOK,
                    row.id(),
                    "Точний дублікат storage+LibID: " + safe(row.title()) + " [" + safe(row.libId()) + "]",
                    true,
                    true));
        }
    }

    private String quickCheck(JdbcTemplate jdbc) {
        try {
            List<String> rows = jdbc.query("PRAGMA quick_check", (rs, rowNum) -> rs.getString(1));
            if (rows.isEmpty()) return "no result";
            return String.join("; ", rows.stream().limit(10).toList());
        } catch (Exception e) {
            return "quick_check failed: " + safeMessage(e);
        }
    }

    private static String normalizeEntry(String entry) {
        return safe(entry).replace('\\', '/').replaceAll("^/+", "");
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
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    private final class FileProcessor {
        private final Collection collection;
        private final IssueCollector issues;
        private final ArchiveValidator archiveValidator = new ArchiveValidator(false);

        private FileProcessor(Collection collection, IssueCollector issues) {
            this.collection = collection;
            this.issues = issues;
        }

        private Path physicalPath(BookPhysicalRef book) {
            String rootValue = notBlank(book.collectionRoot())
                    ? book.collectionRoot()
                    : collection.getRootFolder() == null ? null : collection.getRootFolder().toString();
            Path root = notBlank(rootValue) ? Paths.get(rootValue) : null;
            String folder = safe(book.folder()).trim();
            String fileName = safe(book.fileName()).trim();

            if (notBlank(book.archiveEntry()) && archiveValidator.isArchiveName(folder)) {
                return resolvePath(root, null, folder);
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
                    return notBlank(fileName)
                            ? folderPath.resolve(fileName).toAbsolutePath().normalize()
                            : folderPath.toAbsolutePath().normalize();
                }
            }
            Path result = root;
            if (result == null) result = Paths.get(".").toAbsolutePath().normalize();
            if (notBlank(folder)) result = result.resolve(folder);
            if (notBlank(fileName)) result = result.resolve(fileName);
            return result.toAbsolutePath().normalize();
        }

        private boolean canScanOrphanFiles() {
            Path root = collection.getRootFolder();
            return root != null && Files.isDirectory(root);
        }

        private OrphanScan scanOrphanFiles(DiskBackedPathIndex referenced, String collectionId) {
            Path root = collection.getRootFolder();
            if (root == null || !Files.isDirectory(root) || referenced == null) return new OrphanScan(0, 0);

            Set<String> excluded = new HashSet<>();
            excluded.add(pathKey(databasePath(collection)));
            configuredSource(collectionId).ifPresent(path -> excluded.add(pathKey(path)));

            long scanned = 0;
            long orphan = 0;
            try (Stream<Path> stream = Files.walk(root)) {
                Iterator<Path> iterator = stream.filter(Files::isRegularFile).filter(this::isPhysicalLibraryFile).iterator();
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
            return AppPaths.librariesDir().resolve(collection.getId() + ".db").toAbsolutePath().normalize();
        }

        private Optional<Path> configuredSource(String collectionId) {
            try {
                List<String> rows = metadataJdbcTemplate.query(
                        "SELECT source_file FROM collection_source_watch WHERE collection_id=?",
                        (rs, rowNum) -> rs.getString(1), collectionId);
                return rows.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).findFirst().map(Paths::get);
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }

        private static String pathKey(Path path) {
            String key;
            try {
                key = Files.exists(path) ? path.toRealPath().toString() : path.toAbsolutePath().normalize().toString();
            } catch (IOException e) {
                key = path.toAbsolutePath().normalize().toString();
            }
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                key = key.toLowerCase(Locale.ROOT);
            }
            return key;
        }
    }

    private static final class ArchiveValidator implements AutoCloseable {
        private final boolean keepOpen;
        private final LinkedHashMap<Path, ZipFile> openArchives = new LinkedHashMap<>(8, 0.75f, true);

        private ArchiveValidator() {
            this(true);
        }

        private ArchiveValidator(boolean keepOpen) {
            this.keepOpen = keepOpen;
        }

        private boolean isZipLike(Path path) {
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return ZIP_LIKE_EXTENSIONS.stream().anyMatch(lower::endsWith);
        }

        private boolean isArchiveName(String name) {
            if (!notBlank(name)) return false;
            String lower = name.toLowerCase(Locale.ROOT);
            return ARCHIVE_EXTENSIONS.stream().anyMatch(lower::endsWith);
        }

        private ArchiveValidation validate(Path archive, String requestedEntry) {
            try {
                ZipFile zip = zip(archive);
                String normalized = normalizeEntry(requestedEntry);
                ZipEntry direct = zip.getEntry(requestedEntry);
                if (direct == null && !normalized.equals(requestedEntry)) direct = zip.getEntry(normalized);
                if (direct != null && !direct.isDirectory()) return ArchiveValidation.validResult();

                // Preserve compatibility with archives that use a leading slash or backslashes,
                // but only pay the linear scan cost for a direct lookup miss.
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && normalizeEntry(entry.getName()).equals(normalized)) {
                        return ArchiveValidation.validResult();
                    }
                }
                return ArchiveValidation.missing();
            } catch (Exception e) {
                return ArchiveValidation.error(safeMessage(e));
            }
        }

        private ZipFile zip(Path archive) throws IOException {
            if (!keepOpen) return new ZipFile(archive.toFile());
            ZipFile existing = openArchives.get(archive);
            if (existing != null) return existing;

            ZipFile created = new ZipFile(archive.toFile());
            openArchives.put(archive, created);
            if (openArchives.size() > MAX_OPEN_ARCHIVES) {
                Iterator<Map.Entry<Path, ZipFile>> iterator = openArchives.entrySet().iterator();
                Map.Entry<Path, ZipFile> eldest = iterator.next();
                iterator.remove();
                eldest.getValue().close();
            }
            return created;
        }

        @Override
        public void close() {
            for (ZipFile zip : openArchives.values()) {
                try {
                    zip.close();
                } catch (IOException ignored) { }
            }
            openArchives.clear();
        }
    }

    private record ArchiveValidation(boolean valid, String error) {
        private static ArchiveValidation validResult() { return new ArchiveValidation(true, null); }
        private static ArchiveValidation missing() { return new ArchiveValidation(false, null); }
        private static ArchiveValidation error(String error) { return new ArchiveValidation(false, error); }
    }

    private record BookPhysicalRef(String id, String title, String fileName, String folder,
                                   String archiveEntry, String collectionRoot, String libId) { }
    private record DuplicateRow(String id, String title, String libId) { }
    private record OrphanScan(long scannedFiles, long orphanFiles) { }

    private static final class IssueCollector {
        private final List<MaintenanceIssue> items = new ArrayList<>();
        private final EnumMap<MaintenanceIssueType, Integer> samples = new EnumMap<>(MaintenanceIssueType.class);
        private boolean truncated;

        private void add(MaintenanceIssue issue) {
            items.add(issue);
        }

        private void sample(MaintenanceIssueType type, MaintenanceIssue issue) {
            int count = samples.getOrDefault(type, 0);
            if (count >= MAX_SAMPLES_PER_TYPE) {
                truncated = true;
                return;
            }
            items.add(issue);
            samples.put(type, count + 1);
        }

        private int countSamples(MaintenanceIssueType type) {
            return samples.getOrDefault(type, 0);
        }

        private boolean truncated() {
            return truncated;
        }

        private List<MaintenanceIssue> items() {
            return List.copyOf(items);
        }
    }
}
