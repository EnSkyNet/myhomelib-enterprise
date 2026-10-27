package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ClassicOnlineDownloadProfileTest {
    @Test
    void presetMatchesHistoricalIndyDefaultsWithoutTouchingTlsOrProxy() {
        Map<String, String> settings = ClassicOnlineDownloadProfile.values();
        assertThat(settings).hasSize(5)
                .containsEntry("online.connectTimeoutSeconds", "9")
                .containsEntry("online.readTimeoutSeconds", "90")
                .containsEntry("online.maxParallelDownloads", "1")
                .containsEntry("online.maxParallelDownloadsPerHost", "1")
                .containsEntry("online.userAgent",
                        "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0; MAAU)");
        assertThat(settings.keySet()).noneSatisfy(key -> {
            assertThat(key).doesNotContain("tls").doesNotContain("proxy");
        });
    }
}
