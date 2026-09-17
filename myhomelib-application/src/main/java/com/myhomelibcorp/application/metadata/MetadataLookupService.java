package com.myhomelibcorp.application.metadata;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs metadata providers outside the caller thread, applies one timeout/error policy and aggregates partial success.
 * Provider failures never mutate the local library and never fail the aggregate future.
 */
@Service
public class MetadataLookupService {
    public static final Duration DEFAULT_PROVIDER_TIMEOUT = Duration.ofSeconds(8);

    private final List<MetadataProvider> providers;
    private final ExecutorPort executor;
    private final RuntimeExtensionRegistry runtimeExtensions;

    public MetadataLookupService(List<MetadataProvider> providers, ExecutorPort executor) {
        this(providers, executor, new RuntimeExtensionRegistry());
    }

    @Autowired
    public MetadataLookupService(List<MetadataProvider> providers, ExecutorPort executor, RuntimeExtensionRegistry runtimeExtensions) {
        this.providers = providers == null ? List.of() : providers.stream().filter(Objects::nonNull).toList();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.runtimeExtensions = Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
    }

    public CompletableFuture<MetadataLookupResult> search(MetadataQuery query) {
        return search(query, DEFAULT_PROVIDER_TIMEOUT, new AtomicBoolean(false));
    }

    public CompletableFuture<MetadataLookupResult> search(
            MetadataQuery query,
            Duration providerTimeout,
            AtomicBoolean cancelFlag) {
        Objects.requireNonNull(query, "query");
        validateTimeout(providerTimeout);
        AtomicBoolean cancellation = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        if (cancellation.get()) {
            return CompletableFuture.completedFuture(MetadataLookupResult.cancelled(List.of()));
        }

        List<MetadataProvider> enabledProviders = allProviders().stream()
                .filter(MetadataProvider::isEnabled)
                .sorted(Comparator.comparing(MetadataProvider::id, Comparator.nullsLast(String::compareTo)))
                .toList();
        if (enabledProviders.isEmpty()) return CompletableFuture.completedFuture(MetadataLookupResult.empty());

        List<CompletableFuture<ProviderCall>> calls = enabledProviders.stream()
                .map(provider -> invoke(provider, query, providerTimeout, cancellation))
                .toList();
        CompletableFuture<Void> all = CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new));
        return all.handle((ignored, aggregateFailure) -> aggregate(query.limit(), cancellation, calls));
    }

    private CompletableFuture<ProviderCall> invoke(
            MetadataProvider provider,
            MetadataQuery query,
            Duration timeout,
            AtomicBoolean cancelFlag) {
        MetadataRequestContext context = MetadataRequestContext.create(timeout, cancelFlag);
        CompletableFuture<ProviderCall> submitted;
        try {
            submitted = executor.submit(() -> callProvider(provider, query, context));
        } catch (RuntimeException rejected) {
            return CompletableFuture.completedFuture(ProviderCall.failed(
                    provider,
                    MetadataProviderErrorKind.UNAVAILABLE,
                    issueMessage(MetadataProviderErrorKind.UNAVAILABLE)));
        }
        if (submitted == null) {
            return CompletableFuture.completedFuture(ProviderCall.failed(
                    provider,
                    MetadataProviderErrorKind.UNAVAILABLE,
                    issueMessage(MetadataProviderErrorKind.UNAVAILABLE)));
        }

        long timeoutMillis;
        try {
            timeoutMillis = Math.max(1L, timeout.toMillis());
        } catch (ArithmeticException overflow) {
            timeoutMillis = Long.MAX_VALUE;
        }
        return submitted.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .handle((value, failure) -> failure == null ? value : mapFailure(provider, failure));
    }

    private ProviderCall callProvider(
            MetadataProvider provider,
            MetadataQuery query,
            MetadataRequestContext context) throws MetadataProviderException {
        context.throwIfStopped();
        List<MetadataCandidate> raw = provider.search(query, context);
        context.throwIfStopped();

        List<MetadataCandidate> normalized = new ArrayList<>();
        if (raw != null) {
            for (MetadataCandidate candidate : raw) {
                if (candidate == null) continue;
                if (!Objects.equals(provider.id(), candidate.source().providerId())) {
                    throw new MetadataProviderException(
                            MetadataProviderErrorKind.INVALID_RESPONSE,
                            "Metadata candidate source does not match provider id");
                }
                normalized.add(candidate);
                if (normalized.size() >= query.limit()) break;
            }
        }
        return ProviderCall.success(List.copyOf(normalized));
    }

    private MetadataLookupResult aggregate(
            int limit,
            AtomicBoolean cancelFlag,
            List<CompletableFuture<ProviderCall>> calls) {
        List<MetadataCandidate> candidates = new ArrayList<>();
        List<MetadataProviderIssue> issues = new ArrayList<>();
        for (CompletableFuture<ProviderCall> future : calls) {
            ProviderCall call;
            try {
                call = future.join();
            } catch (RuntimeException unexpected) {
                // invoke() is fail-soft, but keep aggregation defensive against executor/future implementations.
                issues.add(new MetadataProviderIssue(
                        "", "", MetadataProviderErrorKind.FAILED, issueMessage(MetadataProviderErrorKind.FAILED)));
                continue;
            }
            candidates.addAll(call.candidates());
            if (call.issue() != null) issues.add(call.issue());
        }

        if (cancelFlag.get()) return MetadataLookupResult.cancelled(issues);
        List<MetadataCandidate> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble(MetadataCandidate::confidence).reversed()
                        .thenComparing(candidate -> candidate.source().providerId())
                        .thenComparing(candidate -> candidate.source().recordId()))
                .limit(limit)
                .toList();
        return new MetadataLookupResult(ranked, issues, false);
    }

    private static ProviderCall mapFailure(MetadataProvider provider, Throwable failure) {
        Throwable root = unwrap(failure);
        if (root instanceof MetadataProviderException providerFailure) {
            return ProviderCall.failed(provider, providerFailure.kind(), issueMessage(providerFailure.kind()));
        }
        if (root instanceof TimeoutException) {
            return ProviderCall.failed(provider, MetadataProviderErrorKind.TIMEOUT, issueMessage(MetadataProviderErrorKind.TIMEOUT));
        }
        if (root instanceof CancellationException) {
            return ProviderCall.failed(provider, MetadataProviderErrorKind.CANCELLED, issueMessage(MetadataProviderErrorKind.CANCELLED));
        }
        return ProviderCall.failed(provider, MetadataProviderErrorKind.FAILED, issueMessage(MetadataProviderErrorKind.FAILED));
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String issueMessage(MetadataProviderErrorKind kind) {
        return switch (kind == null ? MetadataProviderErrorKind.FAILED : kind) {
            case CANCELLED -> "Metadata lookup cancelled";
            case TIMEOUT -> "Metadata provider timed out";
            case RATE_LIMITED -> "Metadata provider rate limit reached";
            case UNAVAILABLE -> "Metadata provider unavailable";
            case AUTHENTICATION -> "Metadata provider authentication failed";
            case INVALID_RESPONSE -> "Metadata provider returned invalid response";
            case FAILED -> "Metadata provider failed";
        };
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("providerTimeout must be positive");
        }
    }

    private record ProviderCall(List<MetadataCandidate> candidates, MetadataProviderIssue issue) {
        private static ProviderCall success(List<MetadataCandidate> candidates) {
            return new ProviderCall(candidates == null ? List.of() : List.copyOf(candidates), null);
        }

        private static ProviderCall failed(
                MetadataProvider provider,
                MetadataProviderErrorKind kind,
                String message) {
            String id = provider == null || provider.id() == null ? "" : provider.id();
            String name = provider == null || provider.displayName() == null ? "" : provider.displayName();
            return new ProviderCall(
                    List.of(),
                    new MetadataProviderIssue(id, name, kind, message));
        }
    }
    private List<MetadataProvider> allProviders() {
        java.util.LinkedHashMap<String, MetadataProvider> result = new java.util.LinkedHashMap<>();
        providers.forEach(provider -> result.putIfAbsent(provider.id(), provider));
        runtimeExtensions.metadataProviders().forEach(provider -> result.putIfAbsent(provider.id(), provider));
        return List.copyOf(result.values());
    }

}
