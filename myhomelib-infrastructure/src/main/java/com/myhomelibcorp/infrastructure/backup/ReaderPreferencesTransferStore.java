package com.myhomelibcorp.infrastructure.backup;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.Sha256Support;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Owns bounded, race-safe import/export lifecycle for global reader preferences. */
final class ReaderPreferencesTransferStore {
    private static final int MAX_JSON_BYTES = 1024 * 1024;

    private final ObjectMapper mapper;
    private final Path file;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    ReaderPreferencesTransferStore(ObjectMapper mapper) {
        this(mapper, AppPaths.configDir().resolve("reader-preferences.json"));
    }

    ReaderPreferencesTransferStore(ObjectMapper mapper, Path file) {
        this.mapper = mapper;
        this.file = file;
    }

    Snapshot snapshot() throws IOException {
        lock.readLock().lock();
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return new Snapshot(false, new byte[0], null);
            }
            Path safe = SafeFilePublication.requireSafeRegularFile(file, "Reader preferences snapshot");
            byte[] bytes = readBoundedFile(safe, MAX_JSON_BYTES, "Reader preferences");
            String fileHash = SafeFilePublication.sha256(safe, "Reader preferences snapshot");
            var digest = Sha256Support.newDigest();
            digest.update(bytes);
            String bytesHash = Sha256Support.finish(digest);
            if (!bytesHash.equalsIgnoreCase(fileHash)) {
                throw new IOException("Reader preferences changed while being snapshotted: " + file);
            }
            return new Snapshot(true, bytes, fileHash);
        } finally {
            lock.readLock().unlock();
        }
    }

    JsonNode readForExport() throws IOException {
        lock.readLock().lock();
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
            Path safe = SafeFilePublication.requireSafeRegularFile(file, "Reader preferences");
            return mapper.readTree(readBoundedFile(safe, MAX_JSON_BYTES, "Reader preferences"));
        } finally {
            lock.readLock().unlock();
        }
    }

    JsonNode validateNode(JsonNode node, String field) throws IOException {
        if (node == null || !node.isObject()) throw new IOException(field + " must be an object");
        byte[] encoded = mapper.writeValueAsBytes(node);
        if (encoded.length > MAX_JSON_BYTES) {
            throw new IOException(field + " exceeds " + MAX_JSON_BYTES + " bytes");
        }
        return node;
    }

    String restoreGlobal(JsonNode global, Snapshot baseline) throws IOException {
        lock.writeLock().lock();
        try {
            if (global == null || global.isNull()) {
                if (baseline.existed()) {
                    SafeFilePublication.removeIfOwned(file, baseline.sha256(), "Reader preferences import delete");
                } else {
                    Path safeTarget = SafeFilePublication.prepareTarget(file, "Reader preferences import target");
                    if (Files.exists(safeTarget, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Reader preferences changed concurrently before import: " + safeTarget);
                    }
                }
                return null;
            }

            Path parent = SafeFilePublication.createDirectoriesNoLinks(
                    file.toAbsolutePath().getParent(), "Reader preferences import parent");
            Path workspace = SafeFilePublication.createPrivateWorkspace(
                    parent, ".mhl-reader-preferences-import-", "Reader preferences import workspace");
            Path staged = workspace.resolve("reader-preferences.json");
            try {
                try (OutputStream out = SafeFilePublication.openFreshOutputStream(
                        staged, "Reader preferences import staging")) {
                    mapper.writerWithDefaultPrettyPrinter().writeValue(out, global);
                }
                SafeFilePublication.publishReplacingIfUnchanged(
                        staged, file, baseline.sha256(), "Reader preferences import");
                return SafeFilePublication.sha256(file, "Reader preferences imported target");
            } finally {
                SafeFilePublication.cleanupPrivateWorkspace(workspace);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    void rollback(Snapshot snapshot, boolean changed, String installedHash) throws IOException {
        if (!changed) return;
        lock.writeLock().lock();
        try {
            if (!snapshot.existed()) {
                if (installedHash != null) {
                    SafeFilePublication.removeIfOwned(file, installedHash, "Reader preferences rollback cleanup");
                } else if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Reader preferences changed concurrently before rollback: " + file);
                }
                return;
            }

            Path parent = SafeFilePublication.createDirectoriesNoLinks(
                    file.toAbsolutePath().getParent(), "Reader preferences rollback parent");
            Path workspace = SafeFilePublication.createPrivateWorkspace(
                    parent, ".mhl-reader-preferences-rollback-", "Reader preferences rollback workspace");
            Path staged = workspace.resolve("reader-preferences.json");
            boolean cleanup = true;
            try {
                try (OutputStream out = SafeFilePublication.openFreshOutputStream(
                        staged, "Reader preferences rollback staging")) {
                    out.write(snapshot.bytes());
                }
                SafeFilePublication.publishReplacingIfUnchanged(
                        staged, file, installedHash, "Reader preferences rollback");
            } catch (IOException failure) {
                cleanup = false;
                throw new IOException("Reader preferences rollback failed; snapshot evidence preserved at " + workspace,
                        failure);
            } finally {
                if (cleanup) SafeFilePublication.cleanupPrivateWorkspace(workspace);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static byte[] readBoundedFile(Path file, int maxBytes, String label) throws IOException {
        Path safe = SafeFilePublication.requireSafeRegularFile(file, label);
        try (InputStream in = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException(label + " exceeds " + maxBytes + " bytes");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    record Snapshot(boolean existed, byte[] bytes, String sha256) { }
}
