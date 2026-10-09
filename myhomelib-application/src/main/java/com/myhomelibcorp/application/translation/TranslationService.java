package com.myhomelibcorp.application.translation;

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
public class TranslationService {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final Comparator<TranslationProvider> DEFAULT_PROVIDER_ORDER =
            Comparator.comparing(TranslationProvider::isRemote)
                    .thenComparing(TranslationProvider::id);

    private final List<TranslationProvider> providers;
    private final ExecutorPort executor;
    private final RuntimeExtensionRegistry runtimeExtensions;

    public TranslationService(List<TranslationProvider> providers, ExecutorPort executor) {
        this(providers, executor, new RuntimeExtensionRegistry());
    }

    @Autowired
    public TranslationService(List<TranslationProvider> providers, ExecutorPort executor, RuntimeExtensionRegistry runtimeExtensions) {
        this.providers = providers == null ? List.of() : providers.stream().filter(Objects::nonNull).toList();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.runtimeExtensions = Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
    }

    public List<TranslationProviderDescriptor> availableProviders() {
        return allProviders().stream()
                .filter(TranslationProvider::isEnabled)
                .sorted(DEFAULT_PROVIDER_ORDER)
                .map(provider -> new TranslationProviderDescriptor(
                        provider.id(), provider.displayName(), provider.isRemote()))
                .toList();
    }

    public CompletableFuture<TranslationLookupResult> translate(TranslationQuery query, String providerId) {
        return translate(query, providerId, DEFAULT_TIMEOUT, new AtomicBoolean(false));
    }

    /**
     * Invokes exactly one explicitly selected (or offline-preferred) provider. Nothing in this service
     * observes reader selections or sends text automatically.
     */
    public CompletableFuture<TranslationLookupResult> translate(
            TranslationQuery query,
            String providerId,
            Duration timeout,
            AtomicBoolean cancelFlag) {
        Objects.requireNonNull(query, "query");
        TextProviderExecutionSupport.requirePositiveTimeout(timeout);
        AtomicBoolean cancellation = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        if (cancellation.get()) return CompletableFuture.completedFuture(TranslationLookupResult.cancelled(null));

        TranslationProvider provider = selectProvider(providerId);
        if (provider == null) {
            return CompletableFuture.completedFuture(TranslationLookupResult.failed(new TextProviderIssue(
                    TextProviderRegistrySupport.normalizeId(providerId), "", TextProviderErrorKind.UNAVAILABLE, "Translation provider is unavailable")));
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

    private TranslationLookupResult callProvider(
            TranslationProvider provider,
            TranslationQuery query,
            TextProviderRequestContext context) throws TextProviderException {
        context.throwIfStopped();
        TranslationResult result = provider.translate(query, context);
        context.throwIfStopped();
        if (result == null || !provider.id().equals(result.providerId())) {
            return failed(provider, TextProviderErrorKind.INVALID_RESPONSE);
        }
        return TranslationLookupResult.success(result);
    }

    private TranslationProvider selectProvider(String providerId) {
        return TextProviderRegistrySupport.select(
                allProviders(), providerId, TranslationProvider::isEnabled,
                DEFAULT_PROVIDER_ORDER, TranslationProvider::id);
    }

    private static TranslationLookupResult mapFailure(TranslationProvider provider, Throwable failure) {
        TextProviderErrorKind kind = TextProviderFailures.classify(failure);
        TextProviderIssue issue = issue(provider, kind);
        return kind == TextProviderErrorKind.CANCELLED
                ? TranslationLookupResult.cancelled(issue)
                : TranslationLookupResult.failed(issue);
    }

    private static TranslationLookupResult failed(TranslationProvider provider, TextProviderErrorKind kind) {
        return TranslationLookupResult.failed(issue(provider, kind));
    }

    private static TextProviderIssue issue(TranslationProvider provider, TextProviderErrorKind kind) {
        return new TextProviderIssue(provider.id(), provider.displayName(), kind, issueMessage(kind));
    }

    private static String issueMessage(TextProviderErrorKind kind) {
        return switch (kind) {
            case CANCELLED -> "Translation cancelled";
            case TIMEOUT -> "Translation provider timed out";
            case RATE_LIMITED -> "Translation provider rate limit reached";
            case UNAVAILABLE -> "Translation provider is unavailable";
            case AUTHENTICATION -> "Translation provider authentication failed";
            case NOT_FOUND -> "Translation was not found";
            case INVALID_RESPONSE -> "Translation provider returned an invalid response";
            case FAILED -> "Translation failed";
        };
    }


    private List<TranslationProvider> allProviders() {
        return TextProviderRegistrySupport.mergeUniqueById(
                providers, runtimeExtensions.translationProviders(), TranslationProvider::id);
    }

}
