package com.myhomelibcorp.application.dictionary;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderException;
import com.myhomelibcorp.application.textprovider.TextProviderFailures;
import com.myhomelibcorp.application.textprovider.TextProviderIssue;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DictionaryLookupService {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final List<DictionaryProvider> providers;
    private final ExecutorPort executor;
    private final RuntimeExtensionRegistry runtimeExtensions;

    public DictionaryLookupService(List<DictionaryProvider> providers, ExecutorPort executor) {
        this(providers, executor, new RuntimeExtensionRegistry());
    }

    @Autowired
    public DictionaryLookupService(List<DictionaryProvider> providers, ExecutorPort executor, RuntimeExtensionRegistry runtimeExtensions) {
        this.providers = providers == null ? List.of() : providers.stream().filter(Objects::nonNull).toList();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.runtimeExtensions = Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
    }

    public List<DictionaryProviderDescriptor> availableProviders() {
        return allProviders().stream()
                .filter(DictionaryProvider::isEnabled)
                .sorted(Comparator.comparing(DictionaryProvider::isOffline).reversed()
                        .thenComparing(DictionaryProvider::id))
                .map(provider -> new DictionaryProviderDescriptor(
                        provider.id(), provider.displayName(), provider.isOffline()))
                .toList();
    }

    public CompletableFuture<DictionaryLookupResult> lookup(DictionaryQuery query, String providerId) {
        return lookup(query, providerId, DEFAULT_TIMEOUT, new AtomicBoolean(false));
    }

    public CompletableFuture<DictionaryLookupResult> lookup(
            DictionaryQuery query,
            String providerId,
            Duration timeout,
            AtomicBoolean cancelFlag) {
        Objects.requireNonNull(query, "query");
        validateTimeout(timeout);
        AtomicBoolean cancellation = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        if (cancellation.get()) return CompletableFuture.completedFuture(DictionaryLookupResult.cancelled(null));

        DictionaryProvider provider = selectProvider(providerId);
        if (provider == null) {
            return CompletableFuture.completedFuture(DictionaryLookupResult.failed(new TextProviderIssue(
                    clean(providerId), "", TextProviderErrorKind.UNAVAILABLE, "Dictionary provider is unavailable")));
        }

        TextProviderRequestContext context = TextProviderRequestContext.create(timeout, cancellation);
        CompletableFuture<DictionaryLookupResult> submitted;
        try {
            submitted = executor.submit(() -> callProvider(provider, query, context));
        } catch (RuntimeException rejected) {
            return CompletableFuture.completedFuture(failed(provider, TextProviderErrorKind.UNAVAILABLE));
        }
        if (submitted == null) return CompletableFuture.completedFuture(failed(provider, TextProviderErrorKind.UNAVAILABLE));

        long timeoutMillis = safeTimeoutMillis(timeout);
        return submitted.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .handle((result, failure) -> failure == null ? result : mapFailure(provider, failure));
    }

    private DictionaryLookupResult callProvider(
            DictionaryProvider provider,
            DictionaryQuery query,
            TextProviderRequestContext context) throws TextProviderException {
        context.throwIfStopped();
        List<DictionaryEntry> raw = provider.lookup(query, context);
        context.throwIfStopped();
        List<DictionaryEntry> normalized = raw == null ? List.of() : raw.stream()
                .filter(Objects::nonNull)
                .peek(entry -> {
                    if (!provider.id().equals(entry.providerId())) {
                        throw new InvalidProviderAttribution();
                    }
                })
                .limit(query.limit())
                .toList();
        return DictionaryLookupResult.success(normalized);
    }

    private DictionaryProvider selectProvider(String providerId) {
        String requested = clean(providerId);
        if (!requested.isBlank()) {
            return allProviders().stream()
                    .filter(DictionaryProvider::isEnabled)
                    .filter(provider -> requested.equals(provider.id()))
                    .findFirst().orElse(null);
        }
        return allProviders().stream()
                .filter(DictionaryProvider::isEnabled)
                .sorted(Comparator.comparing(DictionaryProvider::isOffline).reversed()
                        .thenComparing(DictionaryProvider::id))
                .findFirst().orElse(null);
    }

    private static DictionaryLookupResult mapFailure(DictionaryProvider provider, Throwable failure) {
        Throwable root = TextProviderFailures.unwrap(failure);
        if (root instanceof InvalidProviderAttribution) {
            return failed(provider, TextProviderErrorKind.INVALID_RESPONSE);
        }
        if (root instanceof TextProviderException providerFailure) {
            TextProviderIssue issue = issue(provider, providerFailure.kind());
            return providerFailure.kind() == TextProviderErrorKind.CANCELLED
                    ? DictionaryLookupResult.cancelled(issue)
                    : DictionaryLookupResult.failed(issue);
        }
        if (root instanceof TimeoutException) return failed(provider, TextProviderErrorKind.TIMEOUT);
        if (root instanceof CancellationException) return DictionaryLookupResult.cancelled(issue(provider, TextProviderErrorKind.CANCELLED));
        return failed(provider, TextProviderErrorKind.FAILED);
    }

    private static DictionaryLookupResult failed(DictionaryProvider provider, TextProviderErrorKind kind) {
        return DictionaryLookupResult.failed(issue(provider, kind));
    }

    private static TextProviderIssue issue(DictionaryProvider provider, TextProviderErrorKind kind) {
        return new TextProviderIssue(provider.id(), provider.displayName(), kind, issueMessage(kind));
    }

    private static String issueMessage(TextProviderErrorKind kind) {
        return switch (kind) {
            case CANCELLED -> "Dictionary lookup cancelled";
            case TIMEOUT -> "Dictionary provider timed out";
            case RATE_LIMITED -> "Dictionary provider rate limit reached";
            case UNAVAILABLE -> "Dictionary provider is unavailable";
            case AUTHENTICATION -> "Dictionary provider authentication failed";
            case NOT_FOUND -> "Dictionary entry was not found";
            case INVALID_RESPONSE -> "Dictionary provider returned an invalid response";
            case FAILED -> "Dictionary lookup failed";
        };
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private static long safeTimeoutMillis(Duration timeout) {
        try {
            return Math.max(1L, timeout.toMillis());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class InvalidProviderAttribution extends RuntimeException {}
    private List<DictionaryProvider> allProviders() {
        java.util.LinkedHashMap<String, DictionaryProvider> result = new java.util.LinkedHashMap<>();
        providers.forEach(provider -> result.putIfAbsent(provider.id(), provider));
        runtimeExtensions.dictionaryProviders().forEach(provider -> result.putIfAbsent(provider.id(), provider));
        return List.copyOf(result.values());
    }

}
