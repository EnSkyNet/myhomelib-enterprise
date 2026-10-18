package com.myhomelibcorp.organizer.fs;

import com.myhomelibcorp.application.util.SafeFilePublication;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;

/**
 * Append-only, checksum-framed state updates for a single immutable recovery snapshot.
 * Avoids writing and SHA-256 hashing an entire N-item manifest for every item transition.
 * A torn final record is not accepted; the last complete record remains usable for manual recovery.
 */
final class ManifestEventLog {
    static final int MAX_LOG_BYTES = 128 * 1024 * 1024;
    private static final int MAX_RECORD_BYTES = 128 * 1024;
    // Bounded lock striping: independent operations can proceed concurrently without
    // retaining one monitor per operation or library (important for large catalogs).
    private static final Object[] WRITE_LOCKS = new Object[64];
    static {
        for (int i = 0; i < WRITE_LOCKS.length; i++) WRITE_LOCKS[i] = new Object();
    }

    private ManifestEventLog() { }

    static Path path(Path manifest) {
        return manifest.resolveSibling(manifest.getFileName() + ".events");
    }

    static void create(Path manifest) throws Exception {
        Path file = OrganizerPathSafety.canonicalForCreation(path(manifest), "Organizer manifest events");
        OrganizerPathSafety.createDirectoriesNoLinks(file.getParent(), "Organizer manifest events directory");
        try (FileChannel unused = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            unused.force(true);
        }
    }

    static void append(Path manifest, String json) throws Exception {
        appendBatch(manifest, List.of(json));
    }

    /**
     * Write several independent state events through one channel and one durable sync.
     * Only non-mutating item transitions may be combined by the caller: a crash before
     * this batch is durable may leave those items PLANNED, but cannot lose file mutations.
     */
    static void appendBatch(Path manifest, List<String> jsonEvents) throws Exception {
        if (jsonEvents == null || jsonEvents.isEmpty()) return;
        Path file = OrganizerPathSafety.canonicalExistingFile(path(manifest), "Organizer manifest events");
        List<byte[]> records = new ArrayList<>(jsonEvents.size());
        long additionalBytes = 0;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String json : jsonEvents) {
            if (json == null || json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Organizer manifest event must be one JSON line");
            }
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            if (payload.length > MAX_RECORD_BYTES)
                throw new IllegalArgumentException("Organizer manifest event is too large");
            byte[] hash = HexFormat.of().formatHex(digest.digest(payload)).getBytes(StandardCharsets.US_ASCII);
            byte[] record = new byte[hash.length + 1 + payload.length + 1];
            System.arraycopy(hash, 0, record, 0, hash.length);
            record[hash.length] = '\t';
            System.arraycopy(payload, 0, record, hash.length + 1, payload.length);
            record[record.length - 1] = '\n';
            additionalBytes += record.length;
            if (additionalBytes > MAX_LOG_BYTES)
                throw new IllegalStateException("Organizer manifest event log exceeds safety limit");
            records.add(record);
        }
        // APPEND alone cannot make a multi-frame batch atomic against another
        // writer. The stripe prevents same-JVM OverlappingFileLockException;
        // the OS lock serializes independent processes on the same file.
        Object monitor = WRITE_LOCKS[Math.floorMod(file.hashCode(), WRITE_LOCKS.length)];
        synchronized (monitor) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = channel.lock()) {
                long size = channel.size();
                if (size > (long) MAX_LOG_BYTES - additionalBytes)
                    throw new IllegalStateException("Organizer manifest event log exceeds safety limit");
                if (size > 0) {
                    ByteBuffer lastByte = ByteBuffer.allocate(1);
                    if (channel.read(lastByte, size - 1) != 1 || lastByte.array()[0] != '\n') {
                        // Do not append behind a torn final record: that would turn
                        // recoverable evidence into an apparently completed bad frame.
                        throw new IllegalStateException("Organizer manifest has an unfinished event; recovery inspection required");
                    }
                }
                channel.position(size);
                for (byte[] record : records) {
                    ByteBuffer bytes = ByteBuffer.wrap(record);
                    while (bytes.hasRemaining()) channel.write(bytes);
                }
                channel.force(true);
            }
        }
    }

    /** For automated recovery: never accept a journal with an incomplete final frame. */
    static List<String> read(Path manifest) throws Exception {
        return readInternal(manifest, false);
    }

    /** Manual diagnostics only: replay fully checksummed frames and ignore an incomplete tail. */
    static List<String> readLastComplete(Path manifest) throws Exception {
        return readInternal(manifest, true);
    }

    private static List<String> readInternal(Path manifest, boolean manualInspection) throws Exception {
        Path eventPath = path(manifest);
        if (!Files.exists(eventPath, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Schema 3 crash manifest event log is missing");
        Path safe = OrganizerPathSafety.canonicalExistingFile(eventPath, "Organizer manifest event log");
        byte[] bytes = SafeFilePublication.readOwnedBytes(safe,
                SafeFilePublication.sha256(safe, "Organizer manifest event log"),
                MAX_LOG_BYTES, "Organizer manifest event log");
        List<String> events = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; ++i) {
            if (bytes[i] != '\n') continue;
            int length = i - start;
            if (length > MAX_RECORD_BYTES + 65) throw new IllegalArgumentException("Oversized manifest event");
            if (length < 66 || bytes[start + 64] != '\t')
                throw new IllegalArgumentException("Corrupted organizer manifest event framing");
            String hash = new String(bytes, start, 64, StandardCharsets.US_ASCII);
            byte[] payload = java.util.Arrays.copyOfRange(bytes, start + 65, i);
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
            if (!actual.equals(hash)) throw new IllegalArgumentException("Corrupted organizer manifest event checksum");
            events.add(new String(payload, StandardCharsets.UTF_8));
            start = i + 1;
        }
        // A fully framed event can be incomplete or corrupted after a hard process exit.
        // Automatic recovery must fail closed, even if the authoritative SQLite journal
        // could otherwise appear consistent with the previous complete state. Only an
        // explicitly invoked manual-inspection method may examine the trusted prefix.
        if (start != bytes.length) {
            if (bytes.length - start > MAX_RECORD_BYTES + 65)
                throw new IllegalArgumentException("Oversized incomplete organizer manifest event");
            if (!manualInspection)
                throw new IllegalArgumentException("Organizer manifest event log has an incomplete final frame; manual review required");
        }
        return events;
    }
}
