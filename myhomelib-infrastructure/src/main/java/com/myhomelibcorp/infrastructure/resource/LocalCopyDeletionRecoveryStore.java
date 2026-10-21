package com.myhomelibcorp.infrastructure.resource;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.BoundedIoSupport;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Durable intent log for crash-safe local-copy deletion.
 *
 * <p>Version 2 binds the marker to the SHA-256 of the staged book bytes. Recovery therefore never
 * deletes or restores a path merely because its name matches a journal entry: the bytes must still
 * be owned by the operation. Legacy v1 markers are retained as evidence and fail closed because
 * they cannot prove ownership.</p>
 */
@Slf4j
public final class LocalCopyDeletionRecoveryStore {
    private static final String VERSION = "2";
    private static final String LEGACY_VERSION = "1";
    private static final String EXTENSION = ".pending";
    private static final int MAX_MARKER_BYTES = 256 * 1024;

    private LocalCopyDeletionRecoveryStore() { }

    public static PreparedMarker prepare(
            Path original,
            Path recovery,
            Path managedRoot,
            String collectionId,
            List<BookId> affectedBookIds,
            String contentSha256) throws IOException {
        if (original == null || recovery == null || managedRoot == null) {
            throw new IllegalArgumentException("Local-copy recovery paths are required");
        }
        if (collectionId == null || collectionId.isBlank()) {
            throw new IllegalArgumentException("Stable collection id is required for crash recovery");
        }
        if (affectedBookIds == null || affectedBookIds.isEmpty()) {
            throw new IllegalArgumentException("Affected book ids are required for crash recovery");
        }
        String contentHash = normalizeHash(contentSha256, "book content SHA-256");

        Path dir = SafeFilePublication.createDirectoriesNoLinks(
                recoveryDir(), "local-copy deletion recovery directory");
        Path marker = dir.resolve(UUID.randomUUID() + EXTENSION);
        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        properties.setProperty("original", original.toAbsolutePath().normalize().toString());
        properties.setProperty("recovery", recovery.toAbsolutePath().normalize().toString());
        properties.setProperty("managedRoot", managedRoot.toAbsolutePath().normalize().toString());
        properties.setProperty("collectionId", collectionId);
        properties.setProperty("bookIds", affectedBookIds.stream().map(BookId::asString).distinct()
                .collect(Collectors.joining(",")));
        properties.setProperty("contentSha256", contentHash);

        Path workspace = SafeFilePublication.createPrivateWorkspace(
                dir, ".local-copy-marker-work-", "local-copy deletion marker workspace");
        try {
            Path staged = workspace.resolve("marker.pending");
            try (OutputStream output = SafeFilePublication.openFreshOutputStream(
                    staged, "staged local-copy deletion marker")) {
                properties.store(output, "MyHomeLib pending local-copy deletion");
            }
            // Recovery refuses to read markers above this bound. Fail before publishing
            // one that would leave the staged deletion unrecoverable after a crash.
            if (Files.size(staged) > MAX_MARKER_BYTES) {
                throw new IOException("Local-copy deletion marker exceeds "
                        + MAX_MARKER_BYTES + "-byte recovery read limit");
            }
            SafeFilePublication.publishReplacingIfUnchanged(
                    staged, marker, null, "local-copy deletion marker publication");
            String markerHash = SafeFilePublication.sha256(marker, "local-copy deletion marker");
            return new PreparedMarker(marker, markerHash);
        } finally {
            try { SafeFilePublication.cleanupPrivateWorkspace(workspace); }
            catch (Exception ignored) { }
        }
    }

    public static void clear(PreparedMarker marker) throws IOException {
        if (marker == null) return;
        SafeFilePublication.removeIfOwned(
                marker.path(), marker.sha256(), "local-copy deletion marker cleanup");
    }

    /** Reconciles only markers whose complete affected-id set belongs to the database being opened. */
    public static void recoverForDatabase(String collectionId, Path database) throws IOException {
        if (collectionId == null || collectionId.isBlank() || database == null
                || !Files.exists(database, LinkOption.NOFOLLOW_LINKS)) return;
        Path safeDatabase = SafeFilePublication.requireSafeRegularFile(database, "collection database for local-copy recovery");
        Path dir = recoveryDir();
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            SafeFilePublication.createDirectoriesNoLinks(dir, "local-copy deletion recovery directory");
        } catch (IOException unsafe) {
            throw new IOException("Unsafe local-copy deletion recovery directory: " + dir, unsafe);
        }

        List<Path> markers;
        try (var stream = Files.list(dir)) {
            markers = stream.filter(path -> path.getFileName().toString().endsWith(EXTENSION)).toList();
        }
        if (markers.isEmpty()) return;

        String url = "jdbc:sqlite:" + safeDatabase;
        try (Connection connection = DriverManager.getConnection(url)) {
            // JDBC reopens SQLite by pathname; refuse to reconcile recovery markers if the path
            // became a symlink/reparse entry during that open. The database itself is intentionally
            // mutated by recovery, so identity is checked structurally rather than by content hash.
            SafeFilePublication.requireSafeRegularFile(safeDatabase, "opened collection database for local-copy recovery");
            for (Path marker : markers) recoverMarkerIfOwned(collectionId, connection, marker);
            SafeFilePublication.requireSafeRegularFile(safeDatabase, "collection database after local-copy recovery");
        } catch (SQLException databaseFailure) {
            throw new IOException("Cannot reconcile pending local-copy deletion against " + safeDatabase, databaseFailure);
        }
    }

    static Path recoveryDir() {
        return AppPaths.cacheDir().resolve("local-copy-deletion-recovery");
    }

    private static void recoverMarkerIfOwned(String collectionId, Connection connection, Path marker) throws IOException, SQLException {
        RecoveryRecord record = read(marker);
        if (!collectionId.equals(record.collectionId())) return;
        List<Integer> localFlags = findLocalFlags(connection, record.bookIds());
        if (localFlags.isEmpty()) return;
        if (localFlags.size() != record.bookIds().size()) {
            throw new IOException("Pending local-copy deletion matches only part of its affected book set: " + marker);
        }
        if (record.contentSha256() == null) {
            throw new IOException("Legacy local-copy deletion marker cannot prove file ownership; evidence kept: " + marker);
        }

        validateRecoveryPaths(record);
        boolean allLocal = localFlags.stream().allMatch(flag -> flag != 0);
        boolean allNonLocal = localFlags.stream().allMatch(flag -> flag == 0);
        if (!allLocal && !allNonLocal) {
            throw new IOException("Mixed books.local state for pending local-copy deletion; recovery kept intact: " + marker);
        }

        if (allLocal) {
            restoreVisibleFile(record);
            clear(record.marker());
            log.warn("Recovered interrupted local-copy deletion by restoring {}", record.original());
            return;
        }

        if (Files.exists(record.recovery(), LinkOption.NOFOLLOW_LINKS)) {
            SafeFilePublication.removeIfOwned(
                    record.recovery(), record.contentSha256(), "committed local-copy recovery cleanup");
        }
        clear(record.marker());
        log.warn("Completed interrupted local-copy deletion cleanup for {}", record.original());
    }

    private static RecoveryRecord read(Path marker) throws IOException {
        Path safeMarker = SafeFilePublication.requireSafeRegularFile(marker, "local-copy deletion recovery marker");
        String beforeHash = SafeFilePublication.sha256(safeMarker, "local-copy deletion recovery marker before read");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(safeMarker, LinkOption.NOFOLLOW_LINKS);
             InputStream bounded = new java.io.ByteArrayInputStream(
                     BoundedIoSupport.readFully(input, MAX_MARKER_BYTES))) {
            properties.load(bounded);
        }
        String afterHash = SafeFilePublication.sha256(safeMarker, "local-copy deletion recovery marker after read");
        if (!beforeHash.equalsIgnoreCase(afterHash)) {
            throw new IOException("Local-copy deletion recovery marker changed while being read: " + marker);
        }
        String version = properties.getProperty("version");
        if (!VERSION.equals(version) && !LEGACY_VERSION.equals(version)) {
            throw new IOException("Unsupported local-copy deletion recovery marker version: " + marker);
        }
        try {
            Path original = Path.of(required(properties, "original"));
            Path recovery = Path.of(required(properties, "recovery"));
            Path managedRoot = Path.of(required(properties, "managedRoot"));
            String collectionId = required(properties, "collectionId");
            String ids = required(properties, "bookIds");
            List<String> bookIds = parseAffectedBookIds(ids);
            if (bookIds.isEmpty()) throw new IOException("Recovery marker has no book ids: " + marker);
            String contentHash = VERSION.equals(version)
                    ? normalizeHash(required(properties, "contentSha256"), "marker content SHA-256")
                    : null;
            return new RecoveryRecord(original, recovery, managedRoot, collectionId, List.copyOf(bookIds),
                    contentHash, new PreparedMarker(safeMarker, afterHash));
        } catch (RuntimeException invalidMarker) {
            throw new IOException("Invalid local-copy deletion recovery marker: " + marker, invalidMarker);
        }
    }

    /**
     * Retain marker order while deduplicating in linear time. A bounded on-disk marker may
     * still contain thousands of ids; repeated List.contains made startup recovery quadratic.
     */
    static List<String> parseAffectedBookIds(String csv) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : csv.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) unique.add(trimmed);
        }
        return List.copyOf(unique);
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Recovery marker field is missing: " + key);
        return value;
    }

    private static String normalizeHash(String value, String role) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(role + " is invalid");
        }
        return normalized;
    }

    private static List<Integer> findLocalFlags(Connection connection, List<String> bookIds) throws SQLException {
        List<Integer> flags = new ArrayList<>(bookIds.size());
        try (PreparedStatement statement = connection.prepareStatement("SELECT local FROM books WHERE id = ?")) {
            for (String bookId : bookIds) {
                statement.setString(1, bookId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) flags.add(rows.getInt(1));
                }
            }
        }
        return flags;
    }

    private static void validateRecoveryPaths(RecoveryRecord record) throws IOException {
        Path root = record.managedRoot().toAbsolutePath().normalize();
        if (root.getParent() == null) {
            throw new IOException("Unsafe managed root in local-copy recovery marker: " + root);
        }
        // Keep containment lexical after validating every existing component. Calling
        // toRealPath() here would reopen a caller-influenced pathname and can follow a parent
        // symlink introduced after validation (NOFOLLOW_LINKS protects only the final entry).
        Path canonicalRoot = SafeFilePublication.requireSafeDirectoryPath(
                root, "local-copy recovery managed root");

        Path original = record.original().toAbsolutePath().normalize();
        Path recovery = record.recovery().toAbsolutePath().normalize();
        Path originalParent = original.getParent();
        Path recoveryParent = recovery.getParent();
        if (originalParent == null || recoveryParent == null) {
            throw new IOException("Recovery parent directory is unavailable or unsafe: " + original);
        }
        Path canonicalParent = SafeFilePublication.requireSafeDirectoryPath(
                originalParent, "local-copy recovery original parent");
        Path canonicalRecoveryParent = SafeFilePublication.requireSafeDirectoryPath(
                recoveryParent, "local-copy recovery staging parent");
        if (!canonicalParent.startsWith(canonicalRoot) || !canonicalRecoveryParent.equals(canonicalParent)) {
            throw new IOException("Recovery marker escapes its managed root: " + original);
        }
        if (Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)) {
            SafeFilePublication.requireSafeRegularFile(recovery, "local-copy recovery artifact");
        }
        if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            SafeFilePublication.requireSafeRegularFile(original, "local-copy recovery original");
        }
        String originalName = original.getFileName() == null ? "" : original.getFileName().toString();
        String recoveryName = recovery.getFileName() == null ? "" : recovery.getFileName().toString();
        if (!recoveryName.startsWith("." + originalName + ".mhl-delete-") || !recoveryName.endsWith(".recovery")) {
            throw new IOException("Unexpected recovery filename: " + recovery);
        }
    }

    private static void restoreVisibleFile(RecoveryRecord record) throws IOException {
        Path original = record.original().toAbsolutePath().normalize();
        Path recovery = record.recovery().toAbsolutePath().normalize();
        if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            String originalHash = SafeFilePublication.sha256(original, "visible book during local-copy recovery");
            if (!record.contentSha256().equalsIgnoreCase(originalHash)) {
                throw new IOException("Visible book changed during local-copy recovery; recovery evidence kept: " + original);
            }
            if (Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.removeIfOwned(
                        recovery, record.contentSha256(), "duplicate local-copy recovery cleanup");
            }
            return;
        }
        String recoveryHash = SafeFilePublication.sha256(recovery, "local-copy recovery bytes");
        if (!record.contentSha256().equalsIgnoreCase(recoveryHash)) {
            throw new IOException("Recovery bytes changed for interrupted local-copy deletion: " + original);
        }
        SafeFilePublication.moveNoReplace(recovery, original, "local-copy recovery restore");
        String restoredHash = SafeFilePublication.sha256(original, "restored local-copy book");
        if (!record.contentSha256().equalsIgnoreCase(restoredHash)) {
            throw new IOException("Restored local-copy bytes differ from recovery marker hash: " + original);
        }
    }

    public record PreparedMarker(Path path, String sha256) {
        public PreparedMarker {
            if (path == null) throw new IllegalArgumentException("marker path is required");
            sha256 = normalizeHash(sha256, "marker SHA-256");
        }
    }

    private record RecoveryRecord(
            Path original,
            Path recovery,
            Path managedRoot,
            String collectionId,
            List<String> bookIds,
            String contentSha256,
            PreparedMarker marker) { }
}
