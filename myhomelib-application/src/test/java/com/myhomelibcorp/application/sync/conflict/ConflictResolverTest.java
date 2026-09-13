package com.myhomelibcorp.application.sync.conflict;

import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConflictResolverTest {
    private final ConflictResolver resolver = new ConflictResolver();
    private final Instant now = Instant.parse("2026-09-12T14:00:00Z");

    @Test
    void keepsFurthestReadingProgressRegardlessOfTimestamp() {
        SyncRecord local = live(SyncEntityType.READING_PROGRESS, "progress:book", 2, 3,
                now.plusSeconds(30), "local", Map.of("percent", "0.40", "paragraphId", "p4"));
        SyncRecord remote = live(SyncEntityType.READING_PROGRESS, "progress:book", 2, 3,
                now, "remote", Map.of("percent", "0.80", "paragraphId", "p8"));

        SyncConflictResolution result = resolver.resolve(local, remote, "device-resolution");

        assertThat(result.decision()).isEqualTo(ConflictResolutionDecision.FURTHEST_PROGRESS);
        assertThat(result.resolvedRecord().orElseThrow().payload()).containsEntry("percent", "0.80");
        assertThat(result.resolvedRecord().orElseThrow().baseVersion()).isEqualTo(3);
        assertThat(result.resolvedRecord().orElseThrow().version()).isEqualTo(4);
    }

    @Test
    void latestScalarMutationWinsDeterministically() {
        SyncRecord local = live(SyncEntityType.RATING, "rating:book", 1, 2,
                now, "local", Map.of("rating", "3"));
        SyncRecord remote = live(SyncEntityType.RATING, "rating:book", 1, 2,
                now.plusSeconds(1), "remote", Map.of("rating", "5"));

        SyncConflictResolution result = resolver.resolve(local, remote, "resolver");

        assertThat(result.decision()).isEqualTo(ConflictResolutionDecision.LATEST);
        assertThat(result.resolvedRecord().orElseThrow().payload()).containsEntry("rating", "5");
    }

    @Test
    void mergesIndependentAnnotationChangesAndUnionsTags() {
        SyncRecord ancestor = live(SyncEntityType.ANNOTATION, "annotation:a", 0, 1, now.minusSeconds(5), "base",
                Map.of("note", "old", "color", "yellow", "tags", "a"));
        SyncRecord local = live(SyncEntityType.ANNOTATION, "annotation:a", 1, 2, now, "local",
                Map.of("note", "local note", "color", "yellow", "tags", "a\u001Fb"));
        SyncRecord remote = live(SyncEntityType.ANNOTATION, "annotation:a", 1, 2, now.plusSeconds(1), "remote",
                Map.of("note", "old", "color", "green", "tags", "a\u001Fc"));

        SyncConflictResolution result = resolver.resolve(ancestor, local, remote, "resolver");

        assertThat(result.decision()).isEqualTo(ConflictResolutionDecision.MERGED);
        assertThat(result.resolvedRecord().orElseThrow().payload())
                .containsEntry("note", "local note")
                .containsEntry("color", "green")
                .containsEntry("tags", "a\u001Fb\u001Fc");
    }

    @Test
    void escalatesSameAnnotationFieldConflictInsteadOfDiscardingOneSide() {
        SyncRecord ancestor = live(SyncEntityType.ANNOTATION, "annotation:a", 0, 1, now.minusSeconds(5), "base",
                Map.of("note", "old", "tags", ""));
        SyncRecord local = live(SyncEntityType.ANNOTATION, "annotation:a", 1, 2, now, "local",
                Map.of("note", "local", "tags", ""));
        SyncRecord remote = live(SyncEntityType.ANNOTATION, "annotation:a", 1, 2, now.plusSeconds(1), "remote",
                Map.of("note", "remote", "tags", ""));

        SyncConflictResolution result = resolver.resolve(ancestor, local, remote, "resolver");

        assertThat(result.requiresManualReview()).isTrue();
        assertThat(result.resolvedRecord()).isEmpty();
        assertThat(result.reason()).contains("note");
    }

    @Test
    void neverSilentlyDeletesLiveMutation() {
        SyncRecord live = live(SyncEntityType.BOOKMARK, "bookmark:b", 3, 4, now, "local", Map.of("position", "0.3"));
        SyncRecord deleted = SyncRecord.tombstone("bookmark:b", SyncEntityType.BOOKMARK, "bookmark:b",
                3, 4, now.plusSeconds(10), "remote");

        SyncConflictResolution result = resolver.resolve(live, deleted, "resolver");

        assertThat(result.decision()).isEqualTo(ConflictResolutionDecision.MANUAL);
        assertThat(result.resolvedRecord()).isEmpty();
    }

    @Test
    void rejectsRecordsForDifferentEntities() {
        SyncRecord a = live(SyncEntityType.RATING, "rating:a", 0, 1, now, "a", Map.of("rating", "1"));
        SyncRecord b = live(SyncEntityType.RATING, "rating:b", 0, 1, now, "b", Map.of("rating", "2"));

        assertThatThrownBy(() -> resolver.resolve(a, b, "resolver"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same logical entity");
    }

    private static SyncRecord live(SyncEntityType type, String syncId, long baseVersion, long version,
                                   Instant at, String device, Map<String, String> payload) {
        return SyncRecord.live(syncId, type, syncId, baseVersion, version, at, device, payload);
    }
}
