package com.myhomelibcorp.infrastructure.resource;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Durable intent log for crash-safe local-copy deletion.
 *
 * <p>The marker is persisted before the visible book path is removed. At the next collection open
 * it is reconciled against the committed {@code books.local} flags in SQLite:</p>
 * <ul>
 *   <li>all affected rows still local -> restore the recovery file;</li>
 *   <li>all affected rows non-local -> release the recovery file;</li>
 *   <li>mixed state -> keep all recovery artifacts and fail closed.</li>
 * </ul>
 */
@Slf4j
public final class LocalCopyDeletionRecoveryStore {
    private static final String VERSION = "1";
    private static final String EXTENSION = ".pending";

    private LocalCopyDeletionRecoveryStore() { }

    public static Path prepare(Path original, Path recovery, Path managedRoot, String collectionId, List<BookId> affectedBookIds) throws IOException {
        if (original == null || recovery == null || managedRoot == null) {
            throw new IllegalArgumentException("Local-copy recovery paths are required");
        }
        if (collectionId == null || collectionId.isBlank()) {
            throw new IllegalArgumentException("Stable collection id is required for crash recovery");
        }
        if (affectedBookIds == null || affectedBookIds.isEmpty()) {
            throw new IllegalArgumentException("Affected book ids are required for crash recovery");
        }

        Path dir = recoveryDir();
        Files.createDirectories(dir);
        Path marker = dir.resolve(UUID.randomUUID() + EXTENSION);
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp");

        Properties properties = new Properties();
        properties.setProperty("version", VERSION);
        properties.setProperty("original", original.toAbsolutePath().normalize().toString());
        properties.setProperty("recovery", recovery.toAbsolutePath().normalize().toString());
        properties.setProperty("managedRoot", managedRoot.toAbsolutePath().normalize().toString());
        properties.setProperty("collectionId", collectionId);
        properties.setProperty("bookIds", affectedBookIds.stream().map(BookId::asString).distinct().reduce((a, b) -> a + "," + b).orElseThrow());

        try (OutputStream output = Files.newOutputStream(temporary,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            properties.store(output, "MyHomeLib pending local-copy deletion");
        }
        try {
            AtomicFileSupport.moveReplacing(temporary, marker);
        } catch (IOException moveFailure) {
            Files.deleteIfExists(temporary);
            throw moveFailure;
        }
        return marker;
    }

    public static void clear(Path marker) throws IOException {
        if (marker != null) Files.deleteIfExists(marker);
    }

    /** Reconciles only markers whose complete affected-id set belongs to the database being opened. */
    public static void recoverForDatabase(String collectionId, Path database) throws IOException {
        if (collectionId == null || collectionId.isBlank() || database == null || !Files.isRegularFile(database)) return;
        Path dir = recoveryDir();
        if (!Files.isDirectory(dir)) return;

        List<Path> markers;
        try (var stream = Files.list(dir)) {
            markers = stream.filter(path -> path.getFileName().toString().endsWith(EXTENSION)).toList();
        }
        if (markers.isEmpty()) return;

        String url = "jdbc:sqlite:" + database.toAbsolutePath().normalize();
        try (Connection connection = DriverManager.getConnection(url)) {
            for (Path marker : markers) {
                recoverMarkerIfOwned(collectionId, connection, marker);
            }
        } catch (SQLException databaseFailure) {
            throw new IOException("Cannot reconcile pending local-copy deletion against " + database, databaseFailure);
        }
    }

    static Path recoveryDir() {
        return AppPaths.cacheDir().resolve("local-copy-deletion-recovery");
    }

    private static void recoverMarkerIfOwned(String collectionId, Connection connection, Path marker) throws IOException, SQLException {
        RecoveryRecord record = read(marker);
        if (!collectionId.equals(record.collectionId())) return;
        List<Integer> localFlags = findLocalFlags(connection, record.bookIds());
        if (localFlags.isEmpty()) return; // Marker belongs to another collection DB.
        if (localFlags.size() != record.bookIds().size()) {
            throw new IOException("Pending local-copy deletion matches only part of its affected book set: " + marker);
        }

        validateRecoveryPaths(record);
        boolean allLocal = localFlags.stream().allMatch(flag -> flag != 0);
        boolean allNonLocal = localFlags.stream().allMatch(flag -> flag == 0);
        if (!allLocal && !allNonLocal) {
            throw new IOException("Mixed books.local state for pending local-copy deletion; recovery kept intact: " + marker);
        }

        if (allLocal) {
            restoreVisibleFile(record);
            clear(marker);
            log.warn("Recovered interrupted local-copy deletion by restoring {}", record.original());
            return;
        }

        // The catalog transaction committed. The hidden recovery bytes are now obsolete.
        Files.deleteIfExists(record.recovery());
        clear(marker);
        log.warn("Completed interrupted local-copy deletion cleanup for {}", record.original());
    }

    private static RecoveryRecord read(Path marker) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(marker)) {
            properties.load(input);
        }
        if (!VERSION.equals(properties.getProperty("version"))) {
            throw new IOException("Unsupported local-copy deletion recovery marker version: " + marker);
        }
        try {
            Path original = Path.of(required(properties, "original"));
            Path recovery = Path.of(required(properties, "recovery"));
            Path managedRoot = Path.of(required(properties, "managedRoot"));
            String collectionId = required(properties, "collectionId");
            String ids = required(properties, "bookIds");
            List<String> bookIds = new ArrayList<>();
            for (String id : ids.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty() && !bookIds.contains(trimmed)) bookIds.add(trimmed);
            }
            if (bookIds.isEmpty()) throw new IOException("Recovery marker has no book ids: " + marker);
            return new RecoveryRecord(original, recovery, managedRoot, collectionId, List.copyOf(bookIds));
        } catch (RuntimeException invalidMarker) {
            throw new IOException("Invalid local-copy deletion recovery marker: " + marker, invalidMarker);
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Recovery marker field is missing: " + key);
        return value;
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
        if (root.getParent() == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IOException("Unsafe managed root in local-copy recovery marker: " + root);
        }
        Path canonicalRoot = root.toRealPath();

        Path original = record.original().toAbsolutePath().normalize();
        Path recovery = record.recovery().toAbsolutePath().normalize();
        Path originalParent = original.getParent();
        Path recoveryParent = recovery.getParent();
        if (originalParent == null || recoveryParent == null || !Files.isDirectory(originalParent)) {
            throw new IOException("Recovery parent directory is unavailable: " + original);
        }
        Path canonicalParent = originalParent.toRealPath();
        Path canonicalRecoveryParent = recoveryParent.toRealPath();
        if (!canonicalParent.startsWith(canonicalRoot) || !canonicalRecoveryParent.equals(canonicalParent)) {
            throw new IOException("Recovery marker escapes its managed root: " + original);
        }
        if (Files.isSymbolicLink(original) || Files.isSymbolicLink(recovery)) {
            throw new IOException("Symbolic links are forbidden in local-copy deletion recovery");
        }
        if (Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(recovery, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recovery artifact is not a regular file: " + recovery);
        }
        if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Original recovery target is not a regular file: " + original);
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
            Files.deleteIfExists(recovery); // Crash happened before the visible path was removed.
            return;
        }
        if (!Files.isRegularFile(recovery, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recovery bytes are missing for interrupted local-copy deletion: " + original);
        }
        try {
            Files.move(recovery, original, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(recovery, original);
        }
    }

    private record RecoveryRecord(Path original, Path recovery, Path managedRoot, String collectionId, List<String> bookIds) { }
}
