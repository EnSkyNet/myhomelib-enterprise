package com.myhomelibcorp.application.translation;

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

class TranslationServiceTest {
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
    void providerDiscoveryAndConstructionDoNotSendText() {
        AtomicInteger remoteCalls = new AtomicInteger();
        TranslationService service = new TranslationService(List.of(
                provider("remote", true, remoteCalls, "hello")), executor);

        assertThat(service.availableProviders()).singleElement();
        assertThat(remoteCalls).hasValue(0);
        TranslationQuery query = TranslationQuery.autoDetect("привіт", "en");
        assertThat(query.text()).isEqualTo("привіт");
        assertThat(remoteCalls).hasValue(0);
    }

    @Test
    void offlineProviderIsDefaultAndExplicitSwitchUsesSelectedRemoteProvider() throws Exception {
        AtomicInteger localCalls = new AtomicInteger();
        AtomicInteger remoteCalls = new AtomicInteger();
        TranslationService service = new TranslationService(List.of(
                provider("remote", true, remoteCalls, "remote"),
                provider("local", false, localCalls, "local")), executor);
        TranslationQuery query = TranslationQuery.autoDetect("книга", "en");

        TranslationLookupResult local = service.translate(query, "").get(1, TimeUnit.SECONDS);
        assertThat(local.translation().providerId()).isEqualTo("local");
        assertThat(localCalls).hasValue(1);
        assertThat(remoteCalls).hasValue(0);

        TranslationLookupResult remote = service.translate(query, "remote").get(1, TimeUnit.SECONDS);
        assertThat(remote.translation().providerId()).isEqualTo("remote");
        assertThat(remoteCalls).hasValue(1);
        assertThat(service.availableProviders()).extracting(TranslationProviderDescriptor::id)
                .containsExactly("local", "remote");
    }

    @Test
    void hardTimeoutContainsProviderThatIgnoresDeadline() throws Exception {
        TranslationProvider slow = new TranslationProvider() {
            @Override public String id() { return "slow"; }
            @Override public String displayName() { return "Slow"; }
            @Override public TranslationResult translate(TranslationQuery query, TextProviderRequestContext context) {
                try { Thread.sleep(1_000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return result("slow", "Slow", "late");
            }
        };
        TranslationLookupResult response = new TranslationService(List.of(slow), executor)
                .translate(TranslationQuery.autoDetect("текст", "en"), "slow",
                        Duration.ofMillis(40), new AtomicBoolean(false))
                .get(1, TimeUnit.SECONDS);

        assertThat(response.translation()).isNull();
        assertThat(response.issue().kind()).isEqualTo(TextProviderErrorKind.TIMEOUT);
    }

    @Test
    void cooperativeCancellationReturnsCancelled() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        TranslationProvider provider = new TranslationProvider() {
            @Override public String id() { return "cancel"; }
            @Override public String displayName() { return "Cancel"; }
            @Override public TranslationResult translate(TranslationQuery query, TextProviderRequestContext context)
                    throws TextProviderException {
                while (!context.isCancelled()) {
                    try { Thread.sleep(5); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw TextProviderException.cancelled();
                    }
                }
                context.throwIfStopped();
                return result("cancel", "Cancel", "never");
            }
        };
        TranslationService service = new TranslationService(List.of(provider), executor);
        CompletableFuture<TranslationLookupResult> future = service.translate(
                TranslationQuery.autoDetect("текст", "en"), "cancel", Duration.ofSeconds(2), cancelled);
        Thread.sleep(25);
        cancelled.set(true);

        TranslationLookupResult response = future.get(1, TimeUnit.SECONDS);
        assertThat(response.cancelled()).isTrue();
        assertThat(response.translation()).isNull();
        assertThat(response.issue().kind()).isEqualTo(TextProviderErrorKind.CANCELLED);
    }

    @Test
    void invalidProviderAttributionIsContained() throws Exception {
        TranslationProvider invalid = new TranslationProvider() {
            @Override public String id() { return "expected"; }
            @Override public String displayName() { return "Expected"; }
            @Override public TranslationResult translate(TranslationQuery query, TextProviderRequestContext context) {
                return result("wrong", "Wrong", "wrong attribution");
            }
        };
        TranslationLookupResult response = new TranslationService(List.of(invalid), executor)
                .translate(TranslationQuery.autoDetect("текст", "en"), "expected")
                .get(1, TimeUnit.SECONDS);

        assertThat(response.translation()).isNull();
        assertThat(response.issue().kind()).isEqualTo(TextProviderErrorKind.INVALID_RESPONSE);
    }

    private static TranslationProvider provider(
            String id, boolean remote, AtomicInteger calls, String translatedText) {
        return new TranslationProvider() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public boolean isRemote() { return remote; }
            @Override public TranslationResult translate(TranslationQuery query, TextProviderRequestContext context) {
                calls.incrementAndGet();
                return result(id, id, translatedText);
            }
        };
    }

    private static TranslationResult result(String id, String name, String text) {
        return new TranslationResult(id, name, "uk", "en", text);
    }
}
