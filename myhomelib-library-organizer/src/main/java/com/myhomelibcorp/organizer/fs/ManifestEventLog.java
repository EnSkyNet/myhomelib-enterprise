package com.myhomelibcorp.organizer.fs;

import com.myhomelibcorp.application.util.SafeFilePublication;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > (long) MAX_LOG_BYTES - additionalBytes)
                throw new IllegalStateException("Organizer manifest event log exceeds safety limit");
            for (byte[] record : records) {
                ByteBuffer bytes = ByteBuffer.wrap(record);
                while (bytes.hasRemaining()) channel.write(bytes);
            }
            channel.force(true);
        }
    }

    static List<String> read(Path manifest) throws Exception {
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
        // An incomplete tail (power loss mid-write) is not applied. The authoritative SQLite
        // journal is checked against the last complete state before any automatic recovery.
        if (bytes.length - start > MAX_RECORD_BYTES + 65)
            throw new IllegalArgumentException("Oversized incomplete organizer manifest event");
        return events;
    }
}
