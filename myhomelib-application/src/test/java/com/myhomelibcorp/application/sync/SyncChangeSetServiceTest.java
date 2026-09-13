package com.myhomelibcorp.application.sync;

import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncChangeSetServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private final SyncChangeSetService service = new SyncChangeSetService(Clock.fixed(NOW, ZoneOffset.UTC), 100);

    @Test
    void collapsesMultipleMutationsToLatestVersionAndSortsDeterministically() {
        SyncRecord oldProgress = record("progress:b", SyncEntityType.READING_PROGRESS, "b", 0, 1, "0.1");
        SyncRecord newProgress = record("progress:b", SyncEntityType.READING_PROGRESS, "b", 1, 2, "0.2");
        SyncRecord favorite = record("favorite:a", SyncEntityType.FAVORITE, "a", 0, 1, "true");

        ChangeSet set = service.create("device-a", 7, "device-a:6", List.of(oldProgress, newProgress, favorite));

        assertThat(set.sequence()).isEqualTo(7);
        assertThat(set.previousCursor()).isEqualTo("device-a:6");
        assertThat(set.cursor()).isEqualTo("device-a:7");
        assertThat(set.createdAt()).isEqualTo(NOW);
        assertThat(set.records()).extracting(SyncRecord::syncId)
                .containsExactly("favorite:a", "progress:b");
        assertThat(set.records().get(1).version()).isEqualTo(2);
    }

    @Test
    void divergentSameVersionFailsClosedAndBatchIsBounded() {
        SyncRecord a = record("setting:x", SyncEntityType.SETTING, "x", 0, 1, "a");
        SyncRecord b = SyncRecord.live("setting:x", SyncEntityType.SETTING, "x", 0, 1,
                NOW.plusSeconds(1), "device-a", Map.of("value", "b"));

        assertThatThrownBy(() -> service.create("device-a", 1, "", List.of(a, b)))
                .hasMessageContaining("divergent payload");

        SyncChangeSetService one = new SyncChangeSetService(Clock.fixed(NOW, ZoneOffset.UTC), 1);
        assertThatThrownBy(() -> one.create("device-a", 1, "", List.of(
                a, record("favorite:y", SyncEntityType.FAVORITE, "y", 0, 1, "true"))))
                .hasMessageContaining("exceeds maxRecords");
    }

    private static SyncRecord record(String id, SyncEntityType type, String localKey,
                                     long baseVersion, long version, String value) {
        return SyncRecord.live(id, type, localKey, baseVersion, version, NOW,
                "device-a", Map.of("value", value));
    }
}
