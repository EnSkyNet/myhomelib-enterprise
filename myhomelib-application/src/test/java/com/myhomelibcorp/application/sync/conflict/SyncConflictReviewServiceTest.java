package com.myhomelibcorp.application.sync.conflict;

import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncConflictReviewServiceTest {
    @Test
    void projectsManualConflictAndAppliesOnlyExplicitSelection() {
        SyncRecord local = SyncRecord.live("setting:x", SyncEntityType.SETTING, "x", 0, 1,
                Instant.parse("2026-09-12T10:00:00Z"), "local", Map.of("value", "a"));
        SyncRecord remote = SyncRecord.live("setting:x", SyncEntityType.SETTING, "x", 0, 1,
                Instant.parse("2026-09-12T10:00:00Z"), "remote", Map.of("value", "b"));
        SyncConflictResolution conflict = new SyncConflictResolution(local, remote,
                ConflictResolutionDecision.MANUAL, null, "same timestamp");
        SyncConflictReviewService service = new SyncConflictReviewService();

        List<SyncConflictReviewItem> items = service.manualItems(List.of(conflict));
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.entityType().key()).isEqualTo("ui.sync.entity.setting");
            assertThat(item.reason().key()).isEqualTo("ui.sync.conflict.reason.manual");
            assertThat(item.localSnapshot().key()).isEqualTo("ui.sync.conflict.snapshot.record.active");
            assertThat(item.localSnapshot().arguments()).contains("local").anySatisfy(value -> assertThat(value).contains("value=a"));
            assertThat(item.remoteSnapshot().arguments()).contains("remote").anySatisfy(value -> assertThat(value).contains("value=b"));
        });
        assertThat(service.applySelections(List.of(conflict), List.of(
                new SyncConflictReviewSelection("SETTING:setting:x", SyncConflictReviewSelection.Side.REMOTE))))
                .containsExactly(remote);
        assertThatThrownBy(() -> service.applySelections(List.of(conflict), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Every manual conflict");
    }
}
