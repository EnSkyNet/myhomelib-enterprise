package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnlineRetryPolicyTest {

    @Test
    void retryabilityIsExplicitInsteadOfAllFiveHundreds() {
        assertThat(OnlineRetryPolicy.isRetryableStatus(408)).isTrue();
        assertThat(OnlineRetryPolicy.isRetryableStatus(429)).isTrue();
        assertThat(OnlineRetryPolicy.isRetryableStatus(503)).isTrue();
        assertThat(OnlineRetryPolicy.isRetryableStatus(404)).isFalse();
        assertThat(OnlineRetryPolicy.isRetryableStatus(501)).isFalse();
        assertThat(OnlineRetryPolicy.isRetryableStatus(505)).isFalse();
    }

    @Test
    void supportsRetryAfterSecondsAndHttpDate() {
        long now = Instant.parse("2026-08-30T10:00:00Z").toEpochMilli();
        assertThat(OnlineRetryPolicy.retryAfterMillis("7", now)).isEqualTo(7_000L);
        String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(now + 12_000L).atZone(ZoneOffset.UTC));
        assertThat(OnlineRetryPolicy.retryAfterMillis(date, now)).isEqualTo(12_000L);
        assertThat(OnlineRetryPolicy.retryAfterMillis("garbage", now)).isEqualTo(-1L);
    }

    @Test
    void exponentialDelayIsJitteredWithinConfiguredBound() {
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return switch (key) {
                case "online.retryBaseDelayMs" -> 1_000;
                case "online.retryMaxDelayMs" -> 30_000;
                case "online.retryJitterPercent" -> 20;
                default -> inv.getArgument(1);
            };
        });
        long delay = OnlineRetryPolicy.delayMillis(settings, 3, null);
        assertThat(delay).isBetween(3_200L, 4_800L);
    }
}
