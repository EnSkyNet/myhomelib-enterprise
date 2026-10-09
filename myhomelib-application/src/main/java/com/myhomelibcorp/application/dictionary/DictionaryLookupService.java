package com.myhomelibcorp.application.dictionary;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderException;
import com.myhomelibcorp.application.textprovider.TextProviderExecutionSupport;
import com.myhomelibcorp.application.textprovider.TextProviderFailures;
import com.myhomelibcorp.application.textprovider.TextProviderIssue;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import com.myhomelibcorp.application.textprovider.TextProviderRegistrySupport;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DictionaryLookupService {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Comparator<DictionaryProvider> DEFAULT_PROVIDER_ORDER =
            Comparator.comparing(DictionaryProvider::isOffline).reversed()
                    .thenComparing(DictionaryProvider::id);

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
                .sorted(DEFAULT_PROVIDER_ORDER)
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
        TextProviderExecutionSupport.requirePositiveTimeout(timeout);
        AtomicBoolean cancellation = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        if (cancellation.get()) return CompletableFuture.completedFuture(DictionaryLookupResult.cancelled(null));

        DictionaryProvider provider = selectProvider(providerId);
        if (provider == null) {
            return CompletableFuture.completedFuture(DictionaryLookupResult.failed(new TextProviderIssue(
                    TextProviderRegistrySupport.normalizeId(providerId), "", TextProviderErrorKind.UNAVAILABLE, "Dictionary provider is unavailable")));
        }

        TextProviderRequestContext context = TextProviderRequestContext.create(timeout, cancellation);
        return TextProviderExecutionSupport.submitBounded(
                executor,
                timeout,
                cancellation,
                () -> callProvider(provider, query, context),
                () -> failed(provider, TextProviderErrorKind.UNAVAILABLE),
                failure -> mapFailure(provider, failure));
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
        return TextProviderRegistrySupport.select(
                allProviders(), providerId, DictionaryProvider::isEnabled,
                DEFAULT_PROVIDER_ORDER, DictionaryProvider::id);
    }

    private static DictionaryLookupResult mapFailure(DictionaryProvider provider, Throwable failure) {
        Throwable root = TextProviderFailures.unwrap(failure);
        if (root instanceof InvalidProviderAttribution) {
            return failed(provider, TextProviderErrorKind.INVALID_RESPONSE);
        }
        TextProviderErrorKind kind = TextProviderFailures.classify(root);
        TextProviderIssue issue = issue(provider, kind);
        return kind == TextProviderErrorKind.CANCELLED
                ? DictionaryLookupResult.cancelled(issue)
                : DictionaryLookupResult.failed(issue);
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


    private static final class InvalidProviderAttribution extends RuntimeException {}

    private List<DictionaryProvider> allProviders() {
        return TextProviderRegistrySupport.mergeUniqueById(
                providers, runtimeExtensions.dictionaryProviders(), DictionaryProvider::id);
    }

}
