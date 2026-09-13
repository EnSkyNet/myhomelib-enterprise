package com.myhomelibcorp.application.metadata;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataLookupServiceTest {
    private final ExecutorService backingExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorPort executorPort = new ExecutorPort() {
        @Override
        public <T> CompletableFuture<T> submit(Callable<T> task) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.call();
                } catch (Exception error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            }, backingExecutor);
        }

        @Override
        public void execute(Runnable task) {
            backingExecutor.execute(task);
        }
    };

    @AfterEach
    void shutDownExecutor() {
        backingExecutor.shutdownNow();
    }

    @Test
    void ranksPartialSuccessAndDoesNotFailAggregateWhenOneProviderFails() throws Exception {
        MetadataProvider good = provider("good", "Good", 0.95, 0);
        MetadataProvider lower = provider("lower", "Lower", 0.70, 0);
        MetadataProvider failing = failingProvider("bad", MetadataProviderErrorKind.RATE_LIMITED);
        MetadataLookupService service = new MetadataLookupService(List.of(lower, failing, good), executorPort);

        MetadataLookupResult result = service.search(
                        MetadataQuery.byTitle("Clean Code").withLimit(5),
                        Duration.ofSeconds(1),
                        new AtomicBoolean(false))
                .get(2, TimeUnit.SECONDS);

        assertThat(result.cancelled()).isFalse();
        assertThat(result.candidates()).extracting(candidate -> candidate.source().providerId())
                .containsExactly("good", "lower");
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().getFirst().providerId()).isEqualTo("bad");
        assertThat(result.issues().getFirst().kind()).isEqualTo(MetadataProviderErrorKind.RATE_LIMITED);
        assertThat(result.issues().getFirst().message())
                .isEqualTo("Metadata provider rate limit reached")
                .doesNotContain("synthetic failure");
    }

    @Test
    void hardTimeoutContainsProviderThatIgnoresCooperativeDeadline() throws Exception {
        MetadataProvider slow = provider("slow", "Slow", 0.8, 1_000);
        MetadataLookupService service = new MetadataLookupService(List.of(slow), executorPort);

        long started = System.nanoTime();
        MetadataLookupResult result = service.search(
                        MetadataQuery.byAuthor("Someone"),
                        Duration.ofMillis(50),
                        new AtomicBoolean(false))
                .get(1, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).isLessThan(700);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.issues()).singleElement()
                .satisfies(issue -> assertThat(issue.kind()).isEqualTo(MetadataProviderErrorKind.TIMEOUT));
    }

    @Test
    void cooperativeCancellationReturnsCancelledWithoutApplyingPartialCandidates() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        MetadataProvider cancellable = new MetadataProvider() {
            @Override
            public String id() { return "cancellable"; }

            @Override
            public String displayName() { return "Cancellable"; }

            @Override
            public List<MetadataCandidate> search(MetadataQuery query, MetadataRequestContext context)
                    throws MetadataProviderException {
                while (!context.isCancelled() && !context.isTimedOut()) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw MetadataProviderException.cancelled();
                    }
                }
                context.throwIfStopped();
                return List.of(candidate("cancellable", "Cancellable", 0.9));
            }
        };
        MetadataLookupService service = new MetadataLookupService(List.of(cancellable), executorPort);

        CompletableFuture<MetadataLookupResult> future = service.search(
                MetadataQuery.byTitle("Book"), Duration.ofSeconds(2), cancelled);
        Thread.sleep(30);
        cancelled.set(true);
        MetadataLookupResult result = future.get(1, TimeUnit.SECONDS);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.issues()).singleElement()
                .satisfies(issue -> assertThat(issue.kind()).isEqualTo(MetadataProviderErrorKind.CANCELLED));
    }

    @Test
    void disabledProviderIsNotInvoked() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean(false);
        MetadataProvider disabled = new MetadataProvider() {
            @Override public String id() { return "disabled"; }
            @Override public String displayName() { return "Disabled"; }
            @Override public boolean isEnabled() { return false; }
            @Override
            public List<MetadataCandidate> search(MetadataQuery query, MetadataRequestContext context) {
                invoked.set(true);
                return List.of();
            }
        };
        MetadataLookupService service = new MetadataLookupService(List.of(disabled), executorPort);

        MetadataLookupResult result = service.search(MetadataQuery.byTitle("Book")).get(1, TimeUnit.SECONDS);

        assertThat(invoked).isFalse();
        assertThat(result).isEqualTo(MetadataLookupResult.empty());
    }

    @Test
    void invalidProviderAttributionIsContainedAsInvalidResponse() throws Exception {
        MetadataProvider invalid = new MetadataProvider() {
            @Override public String id() { return "expected"; }
            @Override public String displayName() { return "Expected"; }
            @Override
            public List<MetadataCandidate> search(MetadataQuery query, MetadataRequestContext context) {
                return List.of(candidate("wrong", "Wrong", 0.9));
            }
        };
        MetadataLookupService service = new MetadataLookupService(List.of(invalid), executorPort);

        MetadataLookupResult result = service.search(MetadataQuery.byTitle("Book")).get(1, TimeUnit.SECONDS);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.issues()).singleElement()
                .satisfies(issue -> assertThat(issue.kind()).isEqualTo(MetadataProviderErrorKind.INVALID_RESPONSE));
    }

    private static MetadataProvider provider(
            String id,
            String name,
            double confidence,
            long sleepMillis) {
        return new MetadataProvider() {
            @Override public String id() { return id; }
            @Override public String displayName() { return name; }

            @Override
            public List<MetadataCandidate> search(MetadataQuery query, MetadataRequestContext context)
                    throws MetadataProviderException {
                if (sleepMillis > 0) {
                    try {
                        Thread.sleep(sleepMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw MetadataProviderException.cancelled();
                    }
                }
                return List.of(candidate(id, name, confidence));
            }
        };
    }

    private static MetadataProvider failingProvider(String id, MetadataProviderErrorKind kind) {
        return new MetadataProvider() {
            @Override public String id() { return id; }
            @Override public String displayName() { return "Failing"; }
            @Override
            public List<MetadataCandidate> search(MetadataQuery query, MetadataRequestContext context)
                    throws MetadataProviderException {
                throw new MetadataProviderException(kind, "synthetic failure");
            }
        };
    }

    private static MetadataCandidate candidate(String id, String name, double confidence) {
        return new MetadataCandidate(
                new MetadataSource(id, name, id + "-record", "https://example.invalid/" + id),
                confidence,
                "Clean Code",
                List.of("Robert C. Martin"),
                "9780132350884",
                2008,
                "Prentice Hall",
                "en",
                "",
                "https://example.invalid/cover.jpg");
    }
}
