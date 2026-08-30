package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;

import java.io.IOException;
import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;

/** Shared retry classification/backoff policy for online book and catalog HTTP traffic. */
public final class OnlineRetryPolicy {
    private static final long DEFAULT_MAX_DELAY_MS = 30_000L;

    private OnlineRetryPolicy() { }

    public static boolean isRetryableStatus(int status) {
        return status == 408
                || status == 421
                || status == 425
                || status == 429
                || status == 500
                || status == 502
                || status == 503
                || status == 504;
    }


    public static IOException safeNetworkFailure(String prefix, URI uri, Exception failure) {
        String kind = failure == null ? "" : " (" + failure.getClass().getSimpleName() + ")";
        // Do not retain the original network exception as a cause: HttpClient diagnostics may expose the full URI.
        return new IOException(prefix + ": " + SensitiveDataSanitizer.sanitizeUri(uri) + kind);
    }

    public static long delayMillis(ApplicationSettingsPort settings, int attempt, String retryAfterHeader) {
        long maxDelay = clamp(settings.getInt("online.retryMaxDelayMs", (int) DEFAULT_MAX_DELAY_MS), 1_000, 120_000);
        long serverDelay = retryAfterMillis(retryAfterHeader, System.currentTimeMillis());
        if (serverDelay >= 0) return Math.min(maxDelay, serverDelay);

        long base = clamp(settings.getInt("online.retryBaseDelayMs", 750), 100, 10_000);
        long exponential = Math.min(maxDelay, base * (1L << Math.min(6, Math.max(0, attempt - 1))));
        int jitterPercent = clamp(settings.getInt("online.retryJitterPercent", 20), 0, 50);
        if (jitterPercent == 0 || exponential <= 1) return exponential;
        long spread = Math.max(1L, exponential * jitterPercent / 100L);
        long jitter = ThreadLocalRandom.current().nextLong(-spread, spread + 1L);
        return Math.max(0L, Math.min(maxDelay, exponential + jitter));
    }

    public static long retryAfterMillis(String value, long nowMillis) {
        if (value == null || value.isBlank()) return -1L;
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds < 0) return -1L;
            return Math.multiplyExact(seconds, 1_000L);
        } catch (NumberFormatException | ArithmeticException ignored) {
            // RFC 9110 also allows an HTTP-date.
        }
        try {
            long target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            return Math.max(0L, target - nowMillis);
        } catch (DateTimeParseException ignored) {
            return -1L;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
