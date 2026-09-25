package com.myhomelibcorp.organizer.fingerprint;

import com.myhomelibcorp.epub.optimizer.semantic.EpubSemanticSnapshot;
import com.myhomelibcorp.epub.optimizer.semantic.EpubSemanticSnapshotService;
import com.myhomelibcorp.organizer.detect.DetectedBookFormat;
import com.myhomelibcorp.organizer.fs.OrganizerPathSafety;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Computes physical/semantic fingerprints from the current file bytes.
 *
 * <p>Fingerprints are intentionally not cached by size/mtime/fileKey. Those metadata can be
 * preserved across an in-place rewrite, which would make duplicate analysis reuse a stale hash.</p>
 */
@Service
public final class BookFingerprintService {
    private final EpubSemanticSnapshotService epubSnapshots;

    public BookFingerprintService(EpubSemanticSnapshotService epubSnapshots) { this.epubSnapshots = epubSnapshots; }

    public BookFingerprint fingerprint(Path source, DetectedBookFormat format) throws Exception {
        Path file = OrganizerPathSafety.canonicalExistingFile(source, "Organizer fingerprint source");
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IllegalArgumentException("Fingerprint source is not a regular file: " + file);
        String physical;
        String semantic;
        if (format != null && "epub".equals(format.format())) {
            StableEpubCopy stable = stageEpubAndHash(file, attributes.size());
            try {
                if (stable.size() != attributes.size()) {
                    throw new IOException("Organizer fingerprint source changed while it was being read: " + file);
                }
                EpubSemanticSnapshot snapshot = epubSnapshots.snapshot(stable.path());
                physical = stable.sha256();
                semantic = semanticHash(snapshot);
            } finally {
                cleanupStableCopy(stable.path());
            }
        } else {
            physical = sha256(file, attributes.size());
            semantic = physical;
        }

        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!sameIdentity(attributes, after)) {
            throw new IOException("Organizer fingerprint source changed during analysis: " + file);
        }
        return new BookFingerprint(physical, semantic, after.size(), after.lastModifiedTime().toMillis());
    }


    private StableEpubCopy stageEpubAndHash(Path source, long expectedSize) throws Exception {
        Path tempRoot = Files.createTempDirectory("myhomelib-organizer-epub-fingerprint-");
        Path staged = tempRoot.resolve("source.epub");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        boolean success = false;
        try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream out = OrganizerPathSafety.openFreshOutputStream(staged, "Organizer EPUB fingerprint staging")) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = in.read(buffer)) >= 0;) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("Organizer EPUB fingerprint cancelled");
                }
                if (read == 0) continue;
                if (total > expectedSize - read) {
                    throw new IOException("Organizer fingerprint source grew or was replaced while reading: " + source);
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();
            if (total != expectedSize) {
                throw new IOException("Organizer fingerprint source shrank or was replaced while reading: " + source);
            }
            success = true;
            return new StableEpubCopy(staged, HexFormat.of().formatHex(digest.digest()), total);
        } finally {
            if (!success) cleanupStableCopy(staged);
        }
    }

    private String semanticHash(EpubSemanticSnapshot snapshot) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String path : snapshot.spineOrder()) {
            update(digest, path);
            update(digest, snapshot.chapterTextHashes().getOrDefault(path, ""));
        }
        snapshot.metadata().forEach((k, v) -> { update(digest, k); update(digest, v); });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean sameIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        if (before == null || after == null || !after.isRegularFile()) return false;
        if (before.size() != after.size()) return false;
        if (!before.lastModifiedTime().equals(after.lastModifiedTime())) return false;
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        return beforeKey == null || afterKey == null || Objects.equals(String.valueOf(beforeKey), String.valueOf(afterKey));
    }

    private static void cleanupStableCopy(Path staged) throws IOException {
        if (staged == null) return;
        Files.deleteIfExists(staged);
        Path parent = staged.getParent();
        if (parent != null) Files.deleteIfExists(parent);
    }

    private String sha256(Path file, long expectedSize) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        long total = 0L;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            for (int read; (read = in.read(buffer)) >= 0;) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("Organizer fingerprint cancelled");
                }
                if (read == 0) continue;
                if (total > expectedSize - read) {
                    throw new IOException("Organizer fingerprint source grew or was replaced while reading: " + file);
                }
                digest.update(buffer, 0, read);
                total += read;
            }
        }
        if (total != expectedSize) {
            throw new IOException("Organizer fingerprint source shrank or was replaced while reading: " + file);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        if (value != null) digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private record StableEpubCopy(Path path, String sha256, long size) { }
}
