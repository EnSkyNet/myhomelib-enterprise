package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AppPaths;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durable filesystem journal for the destructive part of Delete Collection.
 *
 * <p>The registry record is deliberately committed only after the owned SQLite database has been
 * detached into a same-directory tombstone. If registry deletion fails (or the process dies before
 * it), opening the still-registered collection rolls the tombstone back. If registry deletion
 * succeeds, normal commit removes the tombstone and marker.</p>
 */
public final class CollectionDeletionRecoveryStore {
    private static final String VERSION = "1";
    private static final int MAX_MARKER_BYTES = 64 * 1024;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private CollectionDeletionRecoveryStore() { }

    public record Pending(Path marker, String markerSha256, Path database, Path tombstone, String databaseSha256) { }

    public static Pending prepare(Collection collection, Path database) throws IOException {
        String id = requiredId(collection);
        Path db = SafeFilePublication.requireSafeRegularFile(database, "Collection deletion database");
        String dbHash = SafeFilePublication.sha256(db, "Collection deletion database snapshot");
        Path parent = db.getParent();
        if (parent == null) throw new IOException("Collection database has no parent: " + db);

        String base = db.getFileName().toString();
        Path tombstone = parent.resolve("." + base + ".mhl-collection-delete-" + safeSegment(id)
                + "-" + UUID.randomUUID() + ".recovery").toAbsolutePath().normalize();
        if (Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Collection deletion tombstone already exists: " + tombstone);
        }

        Path markerDir = SafeFilePublication.createDirectoriesNoLinks(
                AppPaths.collectionDeleteRecoveryDir(), "Collection deletion recovery directory");
        Path marker = AppPaths.collectionDeleteRecoveryMarker(id).toAbsolutePath().normalize();
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Pending collection deletion already exists: " + marker);
        }

        Properties props = new Properties();
        props.setProperty("version", VERSION);
        props.setProperty("collectionId", id);
        props.setProperty("database", db.toString());
        props.setProperty("tombstoneFile", tombstone.getFileName().toString());
        props.setProperty("databaseSha256", dbHash);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        props.store(encoded, "MyHomeLib collection deletion recovery");
        byte[] bytes = encoded.toByteArray();
        if (bytes.length > MAX_MARKER_BYTES) throw new IOException("Collection deletion marker is unexpectedly large");

        Path workspace = SafeFilePublication.createPrivateWorkspace(
                markerDir, ".mhl-collection-delete-marker-", "Collection deletion marker workspace");
        Path staged = workspace.resolve("pending.properties");
        String markerHash = null;
        boolean markerPublished = false;
        try {
            try (FileChannel channel = SafeFilePublication.openFreshReadWriteChannel(staged,
                    "Collection deletion staged marker")) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            SafeFilePublication.moveNoReplace(staged, marker, "Collection deletion marker publication");
            markerHash = SafeFilePublication.sha256(marker, "Collection deletion published marker");
            markerPublished = true;

            // Re-prove ownership immediately before detach. If the path changes between proof and
            // move, the tombstone hash check below detects it and restores the moved bytes.
            SafeFilePublication.requireOwnedPath(db, dbHash, "Collection deletion database pre-detach");
            SafeFilePublication.moveNoReplace(db, tombstone, "Collection deletion database detach");
            String detachedHash = SafeFilePublication.sha256(tombstone, "Collection deletion tombstone");
            if (!dbHash.equalsIgnoreCase(detachedHash)) {
                IOException failure = new IOException("Collection database changed during deletion detach: " + db);
                tryRestoreDetached(db, tombstone, failure);
                throw failure;
            }
            return new Pending(marker, markerHash, db, tombstone, dbHash);
        } catch (IOException failure) {
            if (Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(db, LinkOption.NOFOLLOW_LINKS)) {
                tryRestoreDetached(db, tombstone, failure);
            }
            if (markerPublished && markerHash != null && Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    SafeFilePublication.removeIfOwned(marker, markerHash, "Collection deletion marker rollback");
                } catch (IOException markerFailure) {
                    failure.addSuppressed(markerFailure);
                }
            }
            throw failure;
        } finally {
            tryDeleteEmptyWorkspace(workspace);
        }
    }

    public static Pending read(Collection collection, Path database) throws IOException {
        String id = requiredId(collection);
        Path marker = AppPaths.collectionDeleteRecoveryMarker(id).toAbsolutePath().normalize();
        String markerHash = SafeFilePublication.sha256(marker, "Collection deletion recovery marker");
        byte[] bytes = SafeFilePublication.readOwnedBytes(marker, markerHash, MAX_MARKER_BYTES,
                "Collection deletion recovery marker");
        Properties props = new Properties();
        props.load(new ByteArrayInputStream(bytes));
        if (!VERSION.equals(props.getProperty("version"))) {
            throw new IOException("Unsupported collection deletion marker version: " + props.getProperty("version"));
        }
        if (!id.equals(props.getProperty("collectionId"))) {
            throw new IOException("Collection deletion marker id mismatch: " + marker);
        }

        Path expectedDb = database.toAbsolutePath().normalize();
        Path recordedDb;
        try {
            recordedDb = Path.of(required(props, "database")).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            throw new IOException("Invalid database path in collection deletion marker", invalidPath);
        }
        if (!expectedDb.equals(recordedDb)) {
            throw new IOException("Collection deletion marker database mismatch: " + marker);
        }
        String hash = required(props, "databaseSha256");
        if (!SHA256.matcher(hash).matches()) throw new IOException("Invalid database hash in collection deletion marker");

        String tombstoneName = required(props, "tombstoneFile");
        if (!tombstoneName.equals(Path.of(tombstoneName).getFileName().toString())
                || tombstoneName.contains("/") || tombstoneName.contains("\\")) {
            throw new IOException("Invalid tombstone filename in collection deletion marker");
        }
        String requiredPrefix = "." + expectedDb.getFileName() + ".mhl-collection-delete-" + safeSegment(id) + "-";
        if (!tombstoneName.startsWith(requiredPrefix) || !tombstoneName.endsWith(".recovery")) {
            throw new IOException("Unexpected tombstone filename in collection deletion marker: " + tombstoneName);
        }
        Path tombstone = expectedDb.getParent().resolve(tombstoneName).toAbsolutePath().normalize();
        if (!tombstone.getParent().equals(expectedDb.getParent())) {
            throw new IOException("Collection deletion tombstone escaped database directory");
        }
        return new Pending(marker, markerHash, expectedDb, tombstone, hash.toLowerCase());
    }

    /** Roll back a prepared deletion while the registry entry is still authoritative. */
    public static void rollback(Collection collection, Path database) throws IOException {
        Pending pending = read(collection, database);
        boolean live = Files.exists(pending.database(), LinkOption.NOFOLLOW_LINKS);
        boolean tomb = Files.exists(pending.tombstone(), LinkOption.NOFOLLOW_LINKS);
        if (live && tomb) {
            throw new IOException("Both live database and collection deletion tombstone exist; refusing ambiguous rollback");
        }
        if (live) {
            SafeFilePublication.requireOwnedPath(pending.database(), pending.databaseSha256(),
                    "Collection deletion rollback live database");
        } else if (tomb) {
            SafeFilePublication.requireOwnedPath(pending.tombstone(), pending.databaseSha256(),
                    "Collection deletion rollback tombstone");
            SafeFilePublication.moveNoReplace(pending.tombstone(), pending.database(),
                    "Collection deletion rollback restore");
            SafeFilePublication.requireOwnedPath(pending.database(), pending.databaseSha256(),
                    "Collection deletion rollback restored database");
        } else {
            throw new IOException("Collection deletion rollback has neither live database nor tombstone");
        }
        SafeFilePublication.removeIfOwned(pending.marker(), pending.markerSha256(),
                "Collection deletion rollback marker cleanup");
    }

    /** Finalize a committed registry deletion by removing only caller-owned tombstone bytes. */
    public static void commit(Collection collection, Path database) throws IOException {
        Pending pending = read(collection, database);
        if (Files.exists(pending.database(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Live collection database unexpectedly exists during deletion commit: " + pending.database());
        }
        if (Files.exists(pending.tombstone(), LinkOption.NOFOLLOW_LINKS)) {
            SafeFilePublication.removeIfOwned(pending.tombstone(), pending.databaseSha256(),
                    "Collection deletion tombstone cleanup");
        }
        SafeFilePublication.removeIfOwned(pending.marker(), pending.markerSha256(),
                "Collection deletion marker cleanup");
    }

    /** Called before opening a still-registered collection after an interrupted delete. */
    public static void recoverRegisteredCollection(Collection collection, Path database) throws IOException {
        Path marker = AppPaths.collectionDeleteRecoveryMarker(requiredId(collection)).toAbsolutePath().normalize();
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
        rollback(collection, database);
    }

    private static void tryRestoreDetached(Path database, Path tombstone, IOException failure) {
        try {
            if (!Files.exists(database, LinkOption.NOFOLLOW_LINKS)
                    && Files.exists(tombstone, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.moveNoReplace(tombstone, database, "Collection deletion failed-detach restore");
            }
        } catch (IOException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    private static void tryDeleteEmptyWorkspace(Path workspace) {
        if (workspace == null) return;
        try {
            if (Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
                try (var entries = Files.list(workspace)) {
                    if (entries.findAny().isEmpty()) Files.delete(workspace);
                }
            }
        } catch (IOException ignored) {
            // Private workspace is non-authoritative; preserve it on cleanup failure.
        }
    }

    private static String requiredId(Collection collection) {
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) {
            throw new IllegalArgumentException("Collection/id must be specified");
        }
        return collection.getId();
    }

    private static String required(Properties props, String key) throws IOException {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing " + key + " in collection deletion marker");
        return value;
    }

    private static String safeSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
