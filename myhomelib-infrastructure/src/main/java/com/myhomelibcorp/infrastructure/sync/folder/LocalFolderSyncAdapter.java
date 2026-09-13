package com.myhomelibcorp.infrastructure.sync.folder;

import com.myhomelibcorp.application.port.out.sync.SyncTransportPort;
import com.myhomelibcorp.domain.model.sync.ChangeSet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Syncthing-compatible transport using immutable change-bundle files in a shared folder.
 * Only finalized *.mhl-sync.json files are visible to readers; *.part files are ignored.
 */
public final class LocalFolderSyncAdapter implements SyncTransportPort {
    static final String FINAL_SUFFIX = ".mhl-sync.json";
    static final String PART_SUFFIX = ".mhl-sync.part";
    static final String LOCK_FILE = ".myhomelib-sync.lock";

    private final Path folder;
    private final SyncBundleJsonCodec codec;

    public LocalFolderSyncAdapter(Path folder, SyncBundleJsonCodec codec) {
        this.folder = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public void push(ChangeSet changeSet) {
        Objects.requireNonNull(changeSet, "changeSet");
        ensureDirectory();
        withExclusiveLock(() -> publishLocked(changeSet));
    }

    @Override
    public List<ChangeSet> pull(Map<String, Long> lastSequenceByDevice) {
        ensureDirectory();
        Map<String, Long> cursor = lastSequenceByDevice == null ? Map.of() : Map.copyOf(lastSequenceByDevice);
        List<ChangeSet> bundles = new ArrayList<>();
        Map<String, ChangeSet> byDeviceSequence = new HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*" + FINAL_SUFFIX)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) continue;
                byte[] bytes = Files.readAllBytes(path);
                ChangeSet set = codec.decode(bytes);
                String key = set.sourceDeviceId() + ":" + set.sequence();
                ChangeSet previous = byDeviceSequence.putIfAbsent(key, set);
                if (previous != null && !previous.changeSetId().equals(set.changeSetId())) {
                    throw new IllegalStateException("Conflicting sync bundles for cursor " + key);
                }
                if (set.sequence() > cursor.getOrDefault(set.sourceDeviceId(), 0L)) bundles.add(set);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read local sync folder", e);
        }
        bundles.sort(Comparator.comparing(ChangeSet::sourceDeviceId).thenComparingLong(ChangeSet::sequence));
        return List.copyOf(bundles);
    }

    private void publishLocked(ChangeSet changeSet) {
        byte[] bytes = codec.encode(changeSet);
        String safeDevice = fileToken(changeSet.sourceDeviceId());
        String base = safeDevice + "-" + changeSet.sequence() + "-" + fileToken(changeSet.changeSetId());
        Path target = folder.resolve(base + FINAL_SUFFIX);
        if (Files.exists(target)) {
            ChangeSet existing = read(target);
            if (existing.equals(changeSet)) return;
            throw new IllegalStateException("Sync target already exists with different contents: " + target.getFileName());
        }

        Path part = folder.resolve("." + base + "-" + UUID.randomUUID() + PART_SUFFIX);
        try {
            try (FileChannel out = FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IllegalStateException("Selected sync folder does not support atomic rename", e);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not publish local sync bundle", e);
        } finally {
            try { Files.deleteIfExists(part); } catch (IOException ignored) { }
        }
    }

    private ChangeSet read(Path path) {
        try {
            return codec.decode(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read sync bundle " + path.getFileName(), e);
        }
    }

    private void withExclusiveLock(Runnable action) {
        Path lockPath = folder.resolve(LOCK_FILE);
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw new IllegalStateException("Sync folder is locked by another operation", e);
            }
            if (lock == null) throw new IllegalStateException("Sync folder is locked by another operation");
            try (lock) {
                action.run();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not lock sync folder", e);
        }
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(folder);
            if (!Files.isDirectory(folder)) throw new IllegalStateException("Sync folder is not a directory");
        } catch (IOException e) {
            throw new IllegalStateException("Could not create sync folder", e);
        }
    }

    private static String fileToken(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (normalized.length() > 96) normalized = normalized.substring(0, 96);
        if (normalized.isBlank()) throw new IllegalArgumentException("Sync identifier cannot be represented as a filename");
        return normalized;
    }
}
