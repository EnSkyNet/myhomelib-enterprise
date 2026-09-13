package com.myhomelibcorp.infrastructure.textprovider;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderException;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import com.myhomelibcorp.shared.util.EncryptionUtil;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shared safe HTTP mechanics for explicit text-provider requests. */
public final class TextProviderHttpSupport {
    private TextProviderHttpSupport() {}

    public static HttpResponse<String> send(
            HttpClient client,
            HttpRequest request,
            TextProviderRequestContext context,
            String providerName) throws TextProviderException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        context.throwIfStopped();
        var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            while (true) {
                context.throwIfStopped();
                long waitMillis = Math.max(1L, Math.min(50L, context.remainingTimeout().toMillis()));
                try {
                    HttpResponse<String> response = future.get(waitMillis, TimeUnit.MILLISECONDS);
                    context.throwIfStopped();
                    return response;
                } catch (TimeoutException poll) {
                    // Poll cooperative cancellation/deadline again.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw TextProviderException.cancelled();
                } catch (CancellationException cancelled) {
                    throw TextProviderException.cancelled();
                } catch (ExecutionException failed) {
                    Throwable cause = unwrap(failed);
                    if (cause instanceof HttpTimeoutException) throw TextProviderException.timeout();
                    throw new TextProviderException(
                            TextProviderErrorKind.UNAVAILABLE,
                            clean(providerName) + " request failed",
                            cause);
                }
            }
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    public static URI requireHttpsUri(String value, String field) {
        String normalized = clean(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        URI uri = URI.create(normalized);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException(field + " must use HTTPS");
        }
        return uri;
    }

    public static String credential(
            ApplicationSettingsPort settings,
            String settingKey,
            String systemProperty,
            String environmentVariable) {
        String runtime = clean(System.getProperty(systemProperty, ""));
        if (!runtime.isBlank()) return safeSecret(runtime);
        runtime = clean(System.getenv(environmentVariable));
        if (!runtime.isBlank()) return safeSecret(runtime);
        String stored = clean(settings.get(settingKey, ""));
        if (stored.isBlank()) return "";
        if (!EncryptionUtil.isEncrypted(stored)) {
            throw new IllegalStateException(settingKey + " must be encrypted or supplied at runtime");
        }
        return safeSecret(EncryptionUtil.decrypt(stored));
    }

    public static String formParam(String key, String value) {
        return encode(key) + "=" + encode(value);
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public static Duration parseRetryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        if (value.isBlank()) return Duration.ZERO;
        try {
            long seconds = Long.parseLong(value);
            return seconds <= 0 ? Duration.ZERO : Duration.ofSeconds(Math.min(seconds, 86_400L));
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration wait = Duration.between(Instant.now(), retryAt);
                if (wait.isNegative()) return Duration.ZERO;
                return wait.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1) : wait;
            } catch (DateTimeParseException invalidDate) {
                return Duration.ZERO;
            }
        }
    }

    public static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeSecret(String value) {
        String cleaned = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        if (cleaned.length() > 2048) throw new IllegalArgumentException("Credential is unexpectedly long");
        return cleaned;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof ExecutionException && current.getCause() != null) current = current.getCause();
        return current;
    }
}
