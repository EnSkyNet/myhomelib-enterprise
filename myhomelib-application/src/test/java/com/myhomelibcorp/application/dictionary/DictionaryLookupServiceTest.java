package com.myhomelibcorp.application.dictionary;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderException;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DictionaryLookupServiceTest {
    private final ExecutorService executorService = Executors.newFixedThreadPool(3);
    private final ExecutorPort executor = new ExecutorPort() {
        @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
            return CompletableFuture.supplyAsync(() -> {
                try { return task.call(); }
                catch (Exception error) { throw new CompletionException(error); }
            }, executorService);
        }
        @Override public void execute(Runnable task) { executorService.execute(task); }
    };

    @AfterEach
    void closeExecutor() { executorService.shutdownNow(); }

    @Test
    void defaultsToOfflineProviderAndDoesNotInvokeRemoteProvider() throws Exception {
        AtomicInteger localCalls = new AtomicInteger();
        AtomicInteger remoteCalls = new AtomicInteger();
        DictionaryProvider remote = provider("remote", false, remoteCalls, "remote definition");
        DictionaryProvider local = provider("local", true, localCalls, "local definition");
        DictionaryLookupService service = new DictionaryLookupService(List.of(remote, local), executor);

        DictionaryLookupResult result = service.lookup(DictionaryQuery.of("book"), "")
                .get(1, TimeUnit.SECONDS);

        assertThat(result.issue()).isNull();
        assertThat(result.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.definition()).isEqualTo("local definition"));
        assertThat(localCalls).hasValue(1);
        assertThat(remoteCalls).hasValue(0);
        assertThat(service.availableProviders()).extracting(DictionaryProviderDescriptor::id)
                .containsExactly("local", "remote");
    }

    @Test
    void explicitProviderSwitchInvokesOnlySelectedProvider() throws Exception {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        DictionaryLookupService service = new DictionaryLookupService(List.of(
                provider("first", true, firstCalls, "first"),
                provider("second", false, secondCalls, "second")), executor);

        DictionaryLookupResult result = service.lookup(DictionaryQuery.of("book"), "second")
                .get(1, TimeUnit.SECONDS);

        assertThat(result.entries()).singleElement()
                .satisfies(entry -> assertThat(entry.providerId()).isEqualTo("second"));
        assertThat(firstCalls).hasValue(0);
        assertThat(secondCalls).hasValue(1);
    }

    @Test
    void hardTimeoutContainsProviderThatIgnoresDeadline() throws Exception {
        DictionaryProvider slow = new DictionaryProvider() {
            @Override public String id() { return "slow"; }
            @Override public String displayName() { return "Slow"; }
            @Override public boolean isOffline() { return false; }
            @Override public List<DictionaryEntry> lookup(DictionaryQuery query, TextProviderRequestContext context) {
                try { Thread.sleep(1_000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return List.of(entry("slow", "Slow", "late"));
            }
        };
        DictionaryLookupService service = new DictionaryLookupService(List.of(slow), executor);

        DictionaryLookupResult result = service.lookup(
                        DictionaryQuery.of("book"), "slow", Duration.ofMillis(40), new AtomicBoolean(false))
                .get(1, TimeUnit.SECONDS);

        assertThat(result.entries()).isEmpty();
        assertThat(result.issue().kind()).isEqualTo(TextProviderErrorKind.TIMEOUT);
    }

    @Test
    void cooperativeCancellationReturnsCancelled() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        DictionaryProvider provider = new DictionaryProvider() {
            @Override public String id() { return "cancel"; }
            @Override public String displayName() { return "Cancel"; }
            @Override public boolean isOffline() { return false; }
            @Override public List<DictionaryEntry> lookup(DictionaryQuery query, TextProviderRequestContext context)
                    throws TextProviderException {
                while (!context.isCancelled()) {
                    try { Thread.sleep(5); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw TextProviderException.cancelled();
                    }
                }
                context.throwIfStopped();
                return List.of();
            }
        };
        DictionaryLookupService service = new DictionaryLookupService(List.of(provider), executor);

        CompletableFuture<DictionaryLookupResult> future = service.lookup(
                DictionaryQuery.of("book"), "cancel", Duration.ofSeconds(2), cancelled);
        Thread.sleep(25);
        cancelled.set(true);
        DictionaryLookupResult result = future.get(1, TimeUnit.SECONDS);

        assertThat(result.cancelled()).isTrue();
        assertThat(result.entries()).isEmpty();
        assertThat(result.issue().kind()).isEqualTo(TextProviderErrorKind.CANCELLED);
    }

    @Test
    void invalidProviderAttributionIsContained() throws Exception {
        DictionaryProvider invalid = new DictionaryProvider() {
            @Override public String id() { return "expected"; }
            @Override public String displayName() { return "Expected"; }
            @Override public boolean isOffline() { return false; }
            @Override public List<DictionaryEntry> lookup(DictionaryQuery query, TextProviderRequestContext context) {
                return List.of(entry("wrong", "Wrong", "definition"));
            }
        };
        DictionaryLookupResult result = new DictionaryLookupService(List.of(invalid), executor)
                .lookup(DictionaryQuery.of("book"), "expected").get(1, TimeUnit.SECONDS);

        assertThat(result.entries()).isEmpty();
        assertThat(result.issue().kind()).isEqualTo(TextProviderErrorKind.INVALID_RESPONSE);
    }

    private static DictionaryProvider provider(
            String id, boolean offline, AtomicInteger calls, String definition) {
        return new DictionaryProvider() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public boolean isOffline() { return offline; }
            @Override public List<DictionaryEntry> lookup(DictionaryQuery query, TextProviderRequestContext context) {
                calls.incrementAndGet();
                return List.of(entry(id, id, definition));
            }
        };
    }

    private static DictionaryEntry entry(String id, String name, String definition) {
        return new DictionaryEntry(id, name, "book", "en", "noun", definition, List.of(), "test");
    }
}
