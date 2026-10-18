package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Per-book write-ahead evidence for Reader text positions. A journal is written
 * before SQLite and acknowledged only after SQLite reports success. Recovery
 * is conservative: the saved base anchor must still match SQLite; an unrelated
 * or newer position must never be silently overwritten.
 *
 * <p>Only the owning Reader service may call this store. All read/write/ack
 * operations are serialized by that service's monitor.
 * Version 2 also binds the pending write to its observed SQLite row revision.
 * Legacy version 1 records can be read for compatibility but do not prove
 * a revision in a versioned database.</p>
 */
@Component
public final class ReaderPositionRecoveryJournal {
    private static final int MAX_FILE_BYTES = 4096;
    private static final String VERSION = "1";
    private static final String REVISION_VERSION = "2";
    private final Path directory;

    public ReaderPositionRecoveryJournal() {
        this(AppPaths.dataDir().resolve("reader-position-recovery"));
    }

    ReaderPositionRecoveryJournal(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    record Pending(String collectionId, String bookId, String originalAnchor, ReaderPosition position,
                   long totalLength, Long expectedRevision) {
        Pending(String collectionId, String bookId, String originalAnchor, ReaderPosition position, long totalLength) {
            this(collectionId, bookId, originalAnchor, position, totalLength, null);
        }
    }

    Pending prepare(String collectionId, String bookId, String originalAnchor,
                 ReaderPosition position, long totalLength) throws IOException {
        return prepare(collectionId, bookId, originalAnchor, position, totalLength, null);
    }

    Pending prepare(String collectionId, String bookId, String originalAnchor,
                 ReaderPosition position, long totalLength, Long expectedRevision) throws IOException {
        if (position == null || collectionId == null || collectionId.isBlank() || bookId == null || bookId.isBlank()) {
            throw new IOException("Missing Reader journal identity or position");
        }
        if (originalAnchor != null && originalAnchor.length() > 1024) {
            throw new IOException("Reader journal anchor is too long");
        }
        if (!position.isValid(Long.MAX_VALUE)) {
            throw new IOException("Reader journal position is invalid");
        }
        String body = String.join("\n", expectedRevision == null ? VERSION : REVISION_VERSION, encoded(collectionId), encoded(bookId),
                encoded(originalAnchor == null ? "" : originalAnchor),
                position.serialize(), Long.toString(Math.max(Math.max(0L, totalLength), position.textOffset())));
        if (expectedRevision != null) body += "\n" + expectedRevision;
        String record = body + "\n" + sha256(body) + "\n";
        // load() has an explicit 4 KiB bound. Never publish a record that the
        // next process would reject during recovery after a database failure.
        if (record.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw new IOException("Reader journal record exceeds recovery size limit");
        }
        Path path = file(collectionId, bookId);
        return ReaderRecoveryFileGuard.withLock(path, () -> {
            // Validation and publication MUST hold one cross-process lock.
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) loadUnlocked(collectionId, bookId);
            AtomicFileSupport.writeStringReplacing(path, record, StandardCharsets.UTF_8);
            return new Pending(collectionId, bookId,
                    originalAnchor == null || originalAnchor.isEmpty() ? null : originalAnchor, position,
                    Math.max(Math.max(0L, totalLength), position.textOffset()), expectedRevision);
        });
    }

    Optional<Pending> load(String collectionId, String bookId) throws IOException {
        return ReaderRecoveryFileGuard.withLock(file(collectionId, bookId),
                () -> loadUnlocked(collectionId, bookId));
    }

    private Optional<Pending> loadUnlocked(String collectionId, String bookId) throws IOException {
        Path path = file(collectionId, bookId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        AtomicFileSupport.requireSafeRegularFile(path, "Reader position recovery");
        if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("Oversized Reader recovery record");
        String[] fields = Files.readString(path, StandardCharsets.UTF_8).split("\n", -1);
        boolean hasRevision = fields.length == 9 && REVISION_VERSION.equals(fields[0]) && fields[8].isEmpty();
        boolean legacy = fields.length == 8 && VERSION.equals(fields[0]) && fields[7].isEmpty();
        if (!hasRevision && !legacy) {
            throw new IOException("Invalid Reader recovery format");
        }
        String body = String.join("\n", fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]);
        if (hasRevision) body += "\n" + fields[6];
        if (!sha256(body).equals(fields[hasRevision ? 7 : 6])) throw new IOException("Reader recovery checksum mismatch");
        try {
            String storedCollection = decoded(fields[1]);
            String storedBook = decoded(fields[2]);
            if (!collectionId.equals(storedCollection) || !bookId.equals(storedBook)) {
                throw new IOException("Reader recovery identity mismatch");
            }
            if (!fields[4].matches("[0-9]+:[0-9]+:[0-9]+:[0-9]+")) {
                throw new IOException("Invalid Reader recovery position");
            }
            ReaderPosition position = ReaderPosition.parse(fields[4]);
            if (!position.serialize().equals(fields[4])) {
                throw new IOException("Invalid Reader recovery position values");
            }
            long length = Long.parseLong(fields[5]);
            if (length < 0 || !position.isValid(length)) throw new IOException("Invalid Reader recovery offset");
            String original = decoded(fields[3]);
            return Optional.of(new Pending(storedCollection, storedBook,
                    original.isEmpty() ? null : original, position, length,
                    hasRevision ? Long.valueOf(fields[6]) : null));
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid Reader recovery content", malformed);
        }
    }

    /** Old explicit cleanup API; callers that observed a Pending must use the
     * conditional overload instead to avoid deleting another writer's evidence. */
    void acknowledge(String collectionId, String bookId) throws IOException {
        Path path = file(collectionId, bookId);
        ReaderRecoveryFileGuard.withLock(path, () -> {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                AtomicFileSupport.requireSafeRegularFile(path, "Reader recovery acknowledgement");
                Files.delete(path);
            }
            return null;
        });
    }

    /** Remove ONLY the exact pending record observed/committed by this writer.
     * Verification and deletion share an OS file lock; corrupted evidence is
     * preserved, never silently deleted. */
    boolean acknowledgeIfMatches(String collectionId, String bookId, Pending expected) throws IOException {
        if (expected == null) return false;
        Path path = file(collectionId, bookId);
        return ReaderRecoveryFileGuard.withLock(path, () -> {
            Optional<Pending> current = loadUnlocked(collectionId, bookId);
            if (current.isEmpty() || !current.get().equals(expected)) return false;
            AtomicFileSupport.requireSafeRegularFile(path, "Reader conditional recovery acknowledgement");
            Files.delete(path);
            return true;
        });
    }

    Path file(String collectionId, String bookId) {
        return directory.resolve(sha256(collectionId + "\u0000" + bookId) + ".pending");
    }

    private static String encoded(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String raw) {
        return new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
    }

    private static String sha256(String data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
