package com.myhomelibcorp.application.imports.statistics;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ImportChangeAccumulatorTest {

    @Test
    void keepsExactSelectiveIdsBelowThreshold() {
        ImportChangeAccumulator changes = new ImportChangeAccumulator(10_000);
        for (int i = 0; i < 10_000; i++) changes.recordUpdated("book-" + i);

        ImportChangeSet snapshot = changes.snapshot();
        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.updated()).hasSize(10_000);
        assertThat(snapshot.updatedCount()).isEqualTo(10_000L);
        assertThat(snapshot.insertedCount()).isZero();
        assertThat(snapshot.deletedCount()).isZero();
    }

    @Test
    void crossingThresholdReleasesIdSetsButPreservesLongCounters() {
        ImportChangeAccumulator changes = new ImportChangeAccumulator(3);
        changes.recordInserted("a");
        changes.recordUpdated("b");
        changes.recordDeleted("c");
        changes.recordUpdated("d");

        ImportChangeSet snapshot = changes.snapshot();
        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.inserted()).isEmpty();
        assertThat(snapshot.updated()).isEmpty();
        assertThat(snapshot.deleted()).isEmpty();
        assertThat(snapshot.insertedCount()).isEqualTo(1L);
        assertThat(snapshot.updatedCount()).isEqualTo(2L);
        assertThat(snapshot.deletedCount()).isEqualTo(1L);
    }

    @Test
    void transitionsStayMutuallyExclusiveWhileSelectiveTrackingIsExact() {
        ImportChangeAccumulator changes = new ImportChangeAccumulator(100);
        changes.recordInserted("x");
        changes.recordUpdated("x");
        changes.recordDeleted("x");

        ImportChangeSet snapshot = changes.snapshot();
        assertThat(snapshot.inserted()).isEmpty();
        assertThat(snapshot.updated()).isEmpty();
        assertThat(snapshot.deleted()).containsExactly("x");
        assertThat(snapshot.insertedCount()).isZero();
        assertThat(snapshot.updatedCount()).isZero();
        assertThat(snapshot.deletedCount()).isEqualTo(1L);
    }

    @Test
    void oneMillionSyntheticChangesRemainBoundedAfterOverflow() {
        ImportChangeAccumulator changes = new ImportChangeAccumulator(1_000);
        for (int i = 0; i < 1_000_000; i++) changes.recordUpdated("book-" + i);

        ImportChangeSet snapshot = changes.snapshot();
        assertThat(snapshot.complete()).isFalse();
        assertThat(changes.trackedIdCount()).isZero();
        assertThat(snapshot.updated()).isEmpty();
        assertThat(snapshot.updatedCount()).isEqualTo(1_000_000L);
    }

    @Test
    void mergeDoesNotBuildAnUnboundedUnion() {
        ImportChangeAccumulator changes = new ImportChangeAccumulator(2);
        changes.merge(new ImportChangeSet(Set.of("a", "b"), Set.of(), Set.of(), true));
        changes.merge(new ImportChangeSet(Set.of("c"), Set.of(), Set.of(), true));

        ImportChangeSet snapshot = changes.snapshot();
        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.inserted()).isEmpty();
        assertThat(snapshot.insertedCount()).isEqualTo(3L);
    }
}
