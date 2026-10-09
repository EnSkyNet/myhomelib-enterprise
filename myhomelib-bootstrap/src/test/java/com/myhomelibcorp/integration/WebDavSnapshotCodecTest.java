package com.myhomelibcorp.integration;

import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebDavSnapshotCodecTest {

    @Test
    void roundTripsBoundedPayload() throws Exception {
        byte[] bytes = "portable user data Привіт".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = DesktopIntegrationSupport.sha256(bytes);

        ChangeSet encoded = WebDavSnapshotCodec.encode(bytes, hash, 3, "device-a", Instant.parse("2026-09-26T12:00:00Z"));
        WebDavSnapshotCodec.DecodedSnapshot decoded = WebDavSnapshotCodec.decode(encoded);

        assertTrue(WebDavSnapshotCodec.supports(encoded));
        assertArrayEquals(bytes, decoded.bytes());
        assertEquals(hash, decoded.hash());
        assertEquals("device-a:2", encoded.previousCursor());
    }

    @Test
    void acceptsOutOfOrderChunksButValidatesTheirIndices() throws Exception {
        byte[] bytes = new byte[900_000];
        new Random(42).nextBytes(bytes);
        String hash = DesktopIntegrationSupport.sha256(bytes);
        ChangeSet encoded = WebDavSnapshotCodec.encode(bytes, hash, 1, "device-b", Instant.parse("2026-09-26T12:00:00Z"));
        assertTrue(encoded.records().size() > 1, "test payload must exercise multiple chunks");

        List<SyncRecord> reversed = new ArrayList<>(encoded.records());
        Collections.reverse(reversed);
        ChangeSet reordered = new ChangeSet(encoded.changeSetId(), encoded.sourceDeviceId(), encoded.sequence(),
                encoded.previousCursor(), encoded.createdAt(), encoded.schemaVersion(), reversed);

        assertArrayEquals(bytes, WebDavSnapshotCodec.decode(reordered).bytes());
    }

    @Test
    void rejectsTamperedChecksumAndUnsupportedChangeSets() throws Exception {
        byte[] bytes = "integrity".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = DesktopIntegrationSupport.sha256(bytes);
        ChangeSet encoded = WebDavSnapshotCodec.encode(bytes, hash, 1, "device-c", Instant.parse("2026-09-26T12:00:00Z"));
        SyncRecord original = encoded.records().getFirst();
        Map<String, String> payload = new LinkedHashMap<>(original.payload());
        payload.put("hash", "0".repeat(64));
        SyncRecord tamperedRecord = SyncRecord.live(original.syncId(), original.entityType(), original.localKey(),
                original.baseVersion(), original.version(), original.updatedAt(), original.deviceId(), payload);
        ChangeSet tampered = new ChangeSet(encoded.changeSetId(), encoded.sourceDeviceId(), encoded.sequence(),
                encoded.previousCursor(), encoded.createdAt(), encoded.schemaVersion(), List.of(tamperedRecord));

        IOException failure = assertThrows(IOException.class, () -> WebDavSnapshotCodec.decode(tampered));
        assertTrue(failure.getMessage().contains("Контрольна сума"));
        assertFalse(WebDavSnapshotCodec.supports(new ChangeSet("empty", "device-c", 1, "", Instant.now(),
                encoded.schemaVersion(), List.of())));
    }
}
