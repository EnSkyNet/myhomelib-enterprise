package com.myhomelibcorp.infrastructure.sync.folder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import com.myhomelibcorp.domain.model.sync.SyncSchema;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncBundleJsonCodecTest {
    private final SyncBundleJsonCodec codec = new SyncBundleJsonCodec(new ObjectMapper());

    @Test
    void roundTripsVersionedChangeSetWithoutDatabaseRepresentation() {
        Instant now = Instant.parse("2026-09-12T12:00:00Z");
        SyncRecord record = SyncRecord.live("progress:book-1", SyncEntityType.READING_PROGRESS, "book-1",
                3, 4, now, "device-a", Map.of("percent", "0.75", "paragraphId", "p-7"));
        ChangeSet source = new ChangeSet("set-1", "device-a", 9, "device-a:8", now,
                SyncSchema.CURRENT_VERSION, List.of(record));

        byte[] bytes = codec.encode(source);
        ChangeSet restored = codec.decode(bytes);

        assertThat(restored).isEqualTo(source);
        String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).contains("\"records\"").doesNotContain("jdbc:sqlite").doesNotContain(".db\"");
    }

    @Test
    void rejectsUnsupportedSchemaAndOversizedBundle() {
        byte[] unsupported = "{\"schemaVersion\":99,\"records\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> codec.decode(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported sync schema version");

        byte[] tooLarge = new byte[SyncBundleJsonCodec.MAX_BUNDLE_BYTES + 1];
        assertThatThrownBy(() -> codec.decode(tooLarge))
                .hasMessageContaining("outside allowed range");
    }
}
