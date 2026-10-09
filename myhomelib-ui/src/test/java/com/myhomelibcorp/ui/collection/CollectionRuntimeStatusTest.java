package com.myhomelibcorp.ui.collection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionRuntimeStatusTest {
    @Test
    void nonFiniteFractionBecomesIndeterminateInsteadOfNaN() {
        assertThat(new CollectionRuntimeStatus(CollectionRuntimeState.INDEXING, Double.NaN, "").fraction()).isEqualTo(-1.0);
        assertThat(new CollectionRuntimeStatus(CollectionRuntimeState.INDEXING, Double.POSITIVE_INFINITY, "").fraction()).isEqualTo(-1.0);
    }
}
