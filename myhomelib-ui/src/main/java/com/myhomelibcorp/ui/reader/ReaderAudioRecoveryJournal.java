package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.audio.AudioPosition;
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

/** Durable, per-book audiobook resume evidence, isolated from text-position journals. */
@Component
public final class ReaderAudioRecoveryJournal {
    private static final String VERSION = "1";
    private static final String REVISION_VERSION = "2";
    private static final int MAX_BYTES = 4096;
    private final Path directory;

    public ReaderAudioRecoveryJournal() {
        this(AppPaths.dataDir().resolve("reader-audio-recovery"));
    }

    ReaderAudioRecoveryJournal(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    record Pending(String originalAnchor, AudioPosition position, double percent, Long expectedRevision) {
        Pending(String originalAnchor, AudioPosition position, double percent) {
            this(originalAnchor, position, percent, null);
        }
    }

    Pending prepare(String collectionId, String bookId, String previousAnchor,
                 AudioPosition position, double percent) throws IOException {
        return prepare(collectionId, bookId, previousAnchor, position, percent, null);
    }

    Pending prepare(String collectionId, String bookId, String previousAnchor,
                 AudioPosition position, double percent, Long expectedRevision) throws IOException {
        if (collectionId == null || collectionId.isBlank() || bookId == null || bookId.isBlank()
                || position == null || previousAnchor != null && previousAnchor.length() > 1024
                || !Double.isFinite(percent) || percent < 0 || percent > 100) {
            throw new IOException("Invalid audiobook recovery input");
        }
        String body = String.join("\n", expectedRevision == null ? VERSION : REVISION_VERSION, encode(collectionId), encode(bookId),
                encode(previousAnchor == null ? "" : previousAnchor), position.serialize(),
                Double.toString(percent));
        if (expectedRevision != null) body += "\n" + expectedRevision;
        String data = body + "\n" + sha256(body) + "\n";
        if (data.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("Audiobook recovery record exceeds size limit");
        }
        Path path = file(collectionId, bookId);
        return ReaderRecoveryFileGuard.withLock(path, () -> {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) loadUnlocked(collectionId, bookId);
            AtomicFileSupport.writeStringReplacing(path, data, StandardCharsets.UTF_8);
            return new Pending(previousAnchor == null || previousAnchor.isEmpty() ? null : previousAnchor,
                    position, percent, expectedRevision);
        });
    }

    Optional<Pending> load(String collectionId, String bookId) throws IOException {
        return ReaderRecoveryFileGuard.withLock(file(collectionId, bookId),
                () -> loadUnlocked(collectionId, bookId));
    }

    private Optional<Pending> loadUnlocked(String collectionId, String bookId) throws IOException {
        Path path = file(collectionId, bookId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        AtomicFileSupport.requireSafeRegularFile(path, "audiobook recovery record");
        if (Files.size(path) > MAX_BYTES) throw new IOException("Audiobook recovery record is too large");
        String[] parts = Files.readString(path, StandardCharsets.UTF_8).split("\n", -1);
        boolean hasRevision = parts.length == 9 && REVISION_VERSION.equals(parts[0]) && parts[8].isEmpty();
        boolean legacy = parts.length == 8 && VERSION.equals(parts[0]) && parts[7].isEmpty();
        if (!hasRevision && !legacy) {
            throw new IOException("Invalid audiobook recovery record format");
        }
        String body = String.join("\n", parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
        if (hasRevision) body += "\n" + parts[6];
        if (!sha256(body).equals(parts[hasRevision ? 7 : 6])) throw new IOException("Audiobook recovery checksum mismatch");
        try {
            if (!collectionId.equals(decode(parts[1])) || !bookId.equals(decode(parts[2]))) {
                throw new IOException("Audiobook recovery identity mismatch");
            }
            var position = AudioPosition.parse(parts[4]);
            if (position.isEmpty() || !position.get().serialize().equals(parts[4])) {
                throw new IOException("Audiobook recovery position is invalid");
            }
            double percent = Double.parseDouble(parts[5]);
            if (!Double.isFinite(percent) || percent < 0 || percent > 100) {
                throw new IOException("Audiobook recovery percentage is invalid");
            }
            String prior = decode(parts[3]);
            return Optional.of(new Pending(prior.isEmpty() ? null : prior, position.get(), percent,
                    hasRevision ? Long.valueOf(parts[6]) : null));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Malformed audiobook recovery record", invalid);
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

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
