package com.myhomelibcorp.integration;

import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import com.myhomelibcorp.domain.model.sync.SyncSchema;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Encodes and validates the bounded portable-user-data payload carried by WebDAV change sets. */
final class WebDavSnapshotCodec {
    static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;

    private static final String SNAPSHOT_PREFIX = "portable-user-data:";
    private static final int SNAPSHOT_CHUNK_CHARS = 700_000;
    private static final int MAX_ENCODED_SNAPSHOT_CHARS = 12_000_000;
    private static final int MAX_SNAPSHOT_CHUNKS =
            (MAX_ENCODED_SNAPSHOT_CHARS + SNAPSHOT_CHUNK_CHARS - 1) / SNAPSHOT_CHUNK_CHARS;
    private static final String SNAPSHOT_KIND = "portable-user-data-v1";
    private static final String SNAPSHOT_ENCODING = "gzip+base64";

    private WebDavSnapshotCodec() { }

    static ChangeSet encode(byte[] bytes, String hash, long sequence, String deviceId, Instant now) throws IOException {
        String encoded = DesktopIntegrationSupport.gzipBase64(bytes);
        if (encoded.length() > MAX_ENCODED_SNAPSHOT_CHARS) {
            throw new IOException("Стиснений пакет користувацьких даних завеликий для одного безпечного WebDAV-пакета");
        }
        int count = Math.max(1, (encoded.length() + SNAPSHOT_CHUNK_CHARS - 1) / SNAPSHOT_CHUNK_CHARS);
        List<SyncRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int start = i * SNAPSHOT_CHUNK_CHARS;
            int end = Math.min(encoded.length(), start + SNAPSHOT_CHUNK_CHARS);
            String index = String.format(Locale.ROOT, "%05d", i);
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("kind", SNAPSHOT_KIND);
            payload.put("hash", hash);
            payload.put("index", Integer.toString(i));
            payload.put("count", Integer.toString(count));
            payload.put("encoding", SNAPSHOT_ENCODING);
            payload.put("chunk", encoded.substring(start, end));
            records.add(SyncRecord.live(SNAPSHOT_PREFIX + index, SyncEntityType.SETTING, SNAPSHOT_PREFIX + index,
                    Math.max(0, sequence - 1), sequence, now, deviceId, payload));
        }
        return new ChangeSet(UUID.randomUUID().toString(), deviceId, sequence,
                sequence <= 1 ? "" : deviceId + ":" + (sequence - 1), now,
                SyncSchema.CURRENT_VERSION, records);
    }

    static boolean supports(ChangeSet set) {
        if (set == null || set.records().isEmpty()) return false;
        return set.records().stream().allMatch(record -> record.entityType() == SyncEntityType.SETTING
                && record.syncId().startsWith(SNAPSHOT_PREFIX)
                && SNAPSHOT_KIND.equals(record.payload().get("kind")));
    }

    static DecodedSnapshot decode(ChangeSet set) throws IOException {
        if (!supports(set)) throw new IOException("Пакет не є підтримуваною версією синхронізації користувацьких даних");
        if (set.records().size() > MAX_SNAPSHOT_CHUNKS) {
            throw new IOException("WebDAV snapshot contains too many chunks");
        }
        List<SyncRecord> records;
        try {
            records = set.records().stream()
                    .sorted(Comparator.comparingInt(record -> parseInt(record, "index")))
                    .toList();
        } catch (IllegalArgumentException invalidIndex) {
            throw new IOException("WebDAV snapshot contains an invalid chunk index", invalidIndex);
        }
        int expectedCount;
        try {
            expectedCount = parseInt(records.getFirst(), "count");
        } catch (IllegalArgumentException invalidCount) {
            throw new IOException("WebDAV snapshot contains an invalid chunk count", invalidCount);
        }
        String expectedHash = records.getFirst().payload().getOrDefault("hash", "");
        if (expectedCount < 1 || expectedCount > MAX_SNAPSHOT_CHUNKS
                || expectedCount != records.size() || !expectedHash.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("Неповний пакет WebDAV-синхронізації");
        }
        int totalChars = 0;
        for (SyncRecord record : records) {
            String chunk = record.payload().getOrDefault("chunk", "");
            if (chunk.length() > SNAPSHOT_CHUNK_CHARS
                    || totalChars > MAX_ENCODED_SNAPSHOT_CHARS - chunk.length()) {
                throw new IOException("WebDAV snapshot exceeds the encoded safety limit");
            }
            totalChars += chunk.length();
        }
        StringBuilder encoded = new StringBuilder(totalChars);
        for (int i = 0; i < records.size(); i++) {
            SyncRecord record = records.get(i);
            if (!Integer.toString(i).equals(record.payload().get("index"))
                    || !Integer.toString(expectedCount).equals(record.payload().get("count"))
                    || !expectedHash.equals(record.payload().get("hash"))
                    || !SNAPSHOT_ENCODING.equals(record.payload().get("encoding"))) {
                throw new IOException("Пошкоджено послідовність частин WebDAV-пакета");
            }
            encoded.append(record.payload().getOrDefault("chunk", ""));
        }
        byte[] bytes = DesktopIntegrationSupport.gunzipBase64(encoded.toString(), MAX_SNAPSHOT_BYTES,
                "Розпакований пакет перевищує безпечний ліміт 64 МБ");
        String actual = DesktopIntegrationSupport.sha256(bytes);
        if (!expectedHash.equals(actual)) throw new IOException("Контрольна сума WebDAV-пакета не збігається");
        return new DecodedSnapshot(bytes, actual);
    }

    private static int parseInt(SyncRecord record, String key) {
        String value = record == null ? null : record.payload().get(key);
        if (value == null || value.isBlank() || value.length() > 8) {
            throw new IllegalArgumentException("Invalid snapshot integer field: " + key);
        }
        return Integer.parseInt(value);
    }

    record DecodedSnapshot(byte[] bytes, String hash) {
        DecodedSnapshot {
            bytes = bytes == null ? new byte[0] : bytes.clone();
            hash = hash == null ? "" : hash;
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
