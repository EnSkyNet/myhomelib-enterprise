package com.myhomelibcorp.application.port.out.download;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteDownloadProgressTest {
    @Test
    void nonFiniteFractionNeverEscapesTelemetryBoundary() {
        assertThat(RemoteDownloadProgress.of(10, 100, "x", Double.NaN).fraction()).isZero();
        assertThat(RemoteDownloadProgress.of(10, 100, "x", Double.POSITIVE_INFINITY).fraction()).isZero();
    }
}
