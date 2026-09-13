package com.myhomelibcorp.infrastructure.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.myhomelibcorp.application.metadata.MetadataProviderException;
import com.myhomelibcorp.application.metadata.MetadataProviderErrorKind;
import com.myhomelibcorp.application.metadata.MetadataRequestContext;
import com.myhomelibcorp.domain.model.valueobject.Isbn;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Shared stateless mechanics for remote metadata adapters; no vendor-specific policy belongs here. */
final class MetadataProviderSupport {
    private MetadataProviderSupport() {}

    /** nanoTime has an arbitrary signed origin; only differences represent durations. */
    static long reserveRequestSlot(AtomicLong nextRequestNanos, long intervalNanos, long now) {
        while (true) {
            long current = nextRequestNanos.get();
            long target = current - now > 0 ? current : now;
            // Signed wraparound is intentional, just as with System.nanoTime().
            if (nextRequestNanos.compareAndSet(current, target + intervalNanos)) return target;
        }
    }

    /** Cancels the HTTP exchange on every early exit, including cooperative cancellation. */
    static HttpResponse<String> send(HttpClient client, HttpRequest request,
                                     MetadataRequestContext context, String providerName)
            throws MetadataProviderException {
        context.throwIfStopped();
        CompletableFuture<HttpResponse<String>> future;
        try {
            future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException unavailable) {
            throw new MetadataProviderException(MetadataProviderErrorKind.UNAVAILABLE,
                    providerName + " request could not be started", unavailable);
        }
        try {
            while (true) {
                context.throwIfStopped();
                long waitMillis = Math.max(1L, Math.min(50L, context.remainingTimeout().toMillis()));
                try {
                    HttpResponse<String> response = future.get(waitMillis, TimeUnit.MILLISECONDS);
                    context.throwIfStopped();
                    return response;
                } catch (TimeoutException pollTimeout) {
                    // Recheck the shared cancellation flag and deadline on the next iteration.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw MetadataProviderException.cancelled();
                } catch (CancellationException cancelled) {
                    throw MetadataProviderException.cancelled();
                } catch (ExecutionException failure) {
                    Throwable cause = unwrap(failure);
                    if (cause instanceof HttpTimeoutException) throw MetadataProviderException.timeout();
                    throw new MetadataProviderException(MetadataProviderErrorKind.UNAVAILABLE,
                            providerName + " request failed", cause);
                }
            }
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    static void waitUntil(long targetNanos, MetadataRequestContext context, long pollMillis)
            throws MetadataProviderException {
        while (true) {
            context.throwIfStopped();
            long remainingNanos = targetNanos - System.nanoTime();
            if (remainingNanos <= 0) return;
            long sleepMillis = Math.max(1L, Math.min(pollMillis,
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw MetadataProviderException.cancelled();
            }
        }
    }

    static String preferredIsbn(List<String> values) {
        for (String value : values) if (value.length() == 13) return value;
        return values.isEmpty() ? "" : values.get(0);
    }

    static boolean sameIsbn(String left, String right) {
        String left13 = isbn13(left);
        String right13 = isbn13(right);
        return !left13.isBlank() && left13.equals(right13);
    }

    static String isbn13(String value) {
        Optional<Isbn> parsed = Isbn.tryParse(value);
        if (parsed.isEmpty()) return "";
        String normalized = parsed.get().value();
        if (normalized.length() == 13) return normalized;
        String body = "978" + normalized.substring(0, 9);
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int digit = body.charAt(i) - '0';
            sum += (i % 2 == 0 ? 1 : 3) * digit;
        }
        int check = (10 - (sum % 10)) % 10;
        return body + check;
    }

    static List<String> textArray(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        Set<String> values = new LinkedHashSet<>();
        if (node.isArray()) {
            for (JsonNode value : node) {
                String text = clean(value.asText(""));
                if (!text.isBlank()) values.add(text);
            }
        } else {
            String text = clean(node.asText(""));
            if (!text.isBlank()) values.add(text);
        }
        return List.copyOf(values);
    }

    static Duration parseRetryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        if (value.isBlank()) return Duration.ZERO;
        try {
            long seconds = Long.parseLong(value);
            return seconds <= 0 ? Duration.ZERO : Duration.ofSeconds(Math.min(seconds, 86_400L));
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration wait = Duration.between(Instant.now(), retryAt);
                return wait.isNegative() ? Duration.ZERO : wait.compareTo(Duration.ofDays(1)) > 0
                        ? Duration.ofDays(1) : wait;
            } catch (DateTimeParseException invalidDate) {
                return Duration.ZERO;
            }
        }
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof ExecutionException && current.getCause() != null) current = current.getCause();
        return current;
    }

    static String normalizeForMatch(String value) {
        String normalized = Normalizer.normalize(clean(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    static double roundConfidence(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    static String safeHeader(String value, String fallback) {
        String cleaned = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        if (cleaned.length() > 256) cleaned = cleaned.substring(0, 256).trim();
        return cleaned.isBlank() ? fallback : cleaned;
    }

    static String param(String key, String value) {
        return encode(key) + "=" + encode(value);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static URI requireHttpUri(URI value, String field) {
        Objects.requireNonNull(value, field);
        String scheme = value.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException(field + " must use HTTP(S)");
        }
        return value;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
