package com.myhomelibcorp.infrastructure.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloadSpaceSupportTest {
    @TempDir Path temp;

    @Test
    void skipsFileStoreProbeWhenNoReserveOrPayloadIsRequired() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        DownloadSpaceSupport.requireAvailable(null, 0L, 0L, (usable, required) -> {
            invoked.set(true);
            return new IOException("unexpected");
        });
        assertThat(invoked).isFalse();
    }

    @Test
    void saturatesOverflowedRequirementInsteadOfWrappingNegative() {
        assertThat(DownloadSpaceSupport.requiredBytes(Long.MAX_VALUE, 1L)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void reportsUsableAndSaturatedRequirementThroughCallerSpecificException() {
        assertThatThrownBy(() -> DownloadSpaceSupport.requireAvailable(temp, Long.MAX_VALUE, 1L,
                (usable, required) -> new IOException(usable + ":" + required)))
                .isInstanceOf(IOException.class)
                .hasMessageEndingWith(":" + Long.MAX_VALUE);
    }
}
