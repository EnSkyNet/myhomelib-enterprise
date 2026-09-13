package com.myhomelibcorp.domain.model.sync;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncRecordModelTest {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");

    @Test
    void liveRecordCarriesConflictMetadataAndImmutablePayload() {
        SyncRecord record = SyncRecord.live("annotation:a1", SyncEntityType.ANNOTATION, "a1",
                4, 5, NOW, "device-a", Map.of("note", "hello"));

        assertThat(record.baseVersion()).isEqualTo(4);
        assertThat(record.version()).isEqualTo(5);
        assertThat(record.schemaVersion()).isEqualTo(SyncSchema.CURRENT_VERSION);
        assertThat(record.tombstone()).isFalse();
        assertThat(record.logicalKey()).isEqualTo("ANNOTATION:annotation:a1");
        assertThatThrownBy(() -> record.payload().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void tombstoneCannotCarryPayloadAndVersionMustAdvance() {
        SyncRecord tombstone = SyncRecord.tombstone("bookmark:b1", SyncEntityType.BOOKMARK, "b1",
                2, 3, NOW, "device-a");
        assertThat(tombstone.tombstone()).isTrue();
        assertThat(tombstone.payload()).isEmpty();

        assertThatThrownBy(() -> new SyncRecord("x", SyncEntityType.BOOKMARK, "x", 3, 3, NOW,
                "device-a", SyncSchema.CURRENT_VERSION, false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than baseVersion");
        assertThatThrownBy(() -> new SyncRecord("x", SyncEntityType.BOOKMARK, "x", 0, 1, NOW,
                "device-a", SyncSchema.CURRENT_VERSION, true, Map.of("x", "y")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tombstone payload");
    }

    @Test
    void changeSetRejectsDuplicateEntitiesAndMixedDevices() {
        SyncRecord first = SyncRecord.live("progress:book", SyncEntityType.READING_PROGRESS, "book",
                0, 1, NOW, "device-a", Map.of("percent", "0.5"));
        SyncRecord duplicate = SyncRecord.live("progress:book", SyncEntityType.READING_PROGRESS, "book",
                1, 2, NOW.plusSeconds(1), "device-a", Map.of("percent", "0.6"));
        SyncRecord otherDevice = SyncRecord.live("favorite:book", SyncEntityType.FAVORITE, "book",
                0, 1, NOW, "device-b", Map.of("favorite", "true"));

        assertThatThrownBy(() -> new ChangeSet("set", "device-a", 1, "", NOW,
                SyncSchema.CURRENT_VERSION, List.of(first, duplicate)))
                .hasMessageContaining("duplicate entity");
        assertThatThrownBy(() -> new ChangeSet("set", "device-a", 1, "", NOW,
                SyncSchema.CURRENT_VERSION, List.of(otherDevice)))
                .hasMessageContaining("deviceId differs");
    }

    @Test
    void schemaVersionIsExplicitAndUnsupportedVersionFailsClosed() {
        assertThat(SyncSchema.isSupported(SyncSchema.CURRENT_VERSION)).isTrue();
        assertThatThrownBy(() -> new SyncRecord("x", SyncEntityType.SETTING, "x", 0, 1, NOW,
                "device", SyncSchema.CURRENT_VERSION + 1, false, Map.of()))
                .hasMessageContaining("Unsupported sync schema version");
    }
}
