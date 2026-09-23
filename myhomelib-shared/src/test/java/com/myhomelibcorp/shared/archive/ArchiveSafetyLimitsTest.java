package com.myhomelibcorp.shared.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveSafetyLimitsTest {
    @Test
    void compressionRatioBoundaryIsExactWithoutIntegerTruncation() {
        long compressed = 10L;
        assertThat(ArchiveSafetyLimits.exceedsCompressionRatio(1_000L, compressed)).isFalse();
        assertThat(ArchiveSafetyLimits.exceedsCompressionRatio(1_001L, compressed)).isTrue();
    }

    @Test
    void compressionRatioCheckIsOverflowSafe() {
        long compressed = Long.MAX_VALUE / ArchiveSafetyLimits.MAX_COMPRESSION_RATIO + 1L;
        assertThat(ArchiveSafetyLimits.exceedsCompressionRatio(Long.MAX_VALUE, compressed)).isFalse();
    }

    @Test
    void unknownOrNonPositiveSizesAreNotClassifiedAsBombs() {
        assertThat(ArchiveSafetyLimits.exceedsCompressionRatio(-1, 10)).isFalse();
        assertThat(ArchiveSafetyLimits.exceedsCompressionRatio(100, -1)).isFalse();
        assertThat(ArchiveSafetyLimits.exceedsCompressionRatio(0, 10)).isFalse();
        assertThat(ArchiveSafetyLimits.exceedsCompressionRatio(100, 0)).isFalse();
    }
}
