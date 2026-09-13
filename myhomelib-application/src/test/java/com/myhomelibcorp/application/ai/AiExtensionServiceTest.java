package com.myhomelibcorp.application.ai;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.security.SecretStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiExtensionServiceTest {
    @Test
    void coreWorksWithNoProvidersAndBooksAreOptedOutByDefault() {
        MemorySettings settings = new MemorySettings();
        AiExtensionService service = new AiExtensionService(Set.of(), settings, Optional.empty());

        assertThat(service.providerIds()).isEmpty();
        assertThat(service.isBookOptedIn(42)).isFalse();
        assertThatThrownBy(() -> service.execute("missing", request(42, ""), AiConsent.none(),
                Duration.ofSeconds(1), new AtomicBoolean()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown AI provider");
    }

    @Test
    void perBookOptInIsPersistentAndFailClosed() throws Exception {
        MemorySettings settings = new MemorySettings();
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = provider("local.test", AiProviderCapabilities.local(Set.of(AiOperation.SUMMARY)),
                (request, context) -> {
                    calls.incrementAndGet();
                    return new AiResponse("summary");
                });
        AiExtensionService service = new AiExtensionService(Set.of(provider), settings, Optional.empty());

        assertThatThrownBy(() -> service.execute(provider.id(), request(7, ""), AiConsent.none(),
                Duration.ofSeconds(1), new AtomicBoolean()))
                .isInstanceOf(AiProviderException.class)
                .satisfies(error -> assertThat(((AiProviderException) error).kind())
                        .isEqualTo(AiProviderErrorKind.CONSENT_REQUIRED));
        assertThat(calls).hasValue(0);

        service.setBookOptIn(7, true);
        assertThat(service.isBookOptedIn(7)).isTrue();
        assertThat(service.execute(provider.id(), request(7, ""), AiConsent.none(),
                Duration.ofSeconds(1), new AtomicBoolean()).text()).isEqualTo("summary");

        service.setBookOptIn(7, false);
        assertThat(service.isBookOptedIn(7)).isFalse();
    }

    @Test
    void contentAndNetworkConsentAreIndependentAndExplicit() {
        MemorySettings settings = new MemorySettings();
        AiProvider provider = provider("cloud.test",
                new AiProviderCapabilities(Set.of(AiOperation.QUESTION_ANSWER), true, Set.of()),
                (request, context) -> new AiResponse("answer"));
        AiExtensionService service = new AiExtensionService(Set.of(provider), settings, Optional.empty());
        service.setBookOptIn(9, true);
        AiRequest request = new AiRequest(9, AiOperation.QUESTION_ANSWER, "question", "private book text");

        assertThatThrownBy(() -> service.execute(provider.id(), request, new AiConsent(false, false),
                Duration.ofSeconds(1), new AtomicBoolean()))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("book-content sharing consent");
        assertThatThrownBy(() -> service.execute(provider.id(), request, new AiConsent(false, true),
                Duration.ofSeconds(1), new AtomicBoolean()))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("network consent");
    }

    @Test
    void providerCanReadOnlyDeclaredNamespacedSecretsFromSecretStore() throws Exception {
        MemorySettings settings = new MemorySettings();
        MemorySecretStore secrets = new MemorySecretStore();
        AiProvider provider = provider("cloud.secure",
                new AiProviderCapabilities(Set.of(AiOperation.SUMMARY), true, Set.of("api-key")),
                (request, context) -> new AiResponse(context.secret("api-key").orElseThrow()));
        AiExtensionService service = new AiExtensionService(Set.of(provider), settings, Optional.of(secrets));
        service.setBookOptIn(11, true);
        service.saveProviderSecret(provider.id(), "api-key", "secret-value");

        assertThat(secrets.values).containsEntry("myhomelib.ai.cloud.secure.api-key", "secret-value");
        assertThat(settings.values).doesNotContainValue("secret-value");
        AiResponse response = service.execute(provider.id(), request(11, ""), new AiConsent(true, false),
                Duration.ofSeconds(1), new AtomicBoolean());
        assertThat(response.text()).isEqualTo("secret-value");
        service.deleteProviderSecret(provider.id(), "api-key");
        assertThat(secrets.values).isEmpty();
    }

    @Test
    void undeclaredSecretCannotBeReadOrStored() throws Exception {
        MemorySettings settings = new MemorySettings();
        MemorySecretStore secrets = new MemorySecretStore();
        AiProvider provider = provider("local.secret",
                new AiProviderCapabilities(Set.of(AiOperation.SUMMARY), false, Set.of("allowed")),
                (request, context) -> {
                    assertThatThrownBy(() -> context.secret("other"))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("undeclared secret");
                    return new AiResponse("ok");
                });
        AiExtensionService service = new AiExtensionService(Set.of(provider), settings, Optional.of(secrets));
        service.setBookOptIn(12, true);

        assertThatThrownBy(() -> service.saveProviderSecret(provider.id(), "other", "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not declared");
        assertThat(service.execute(provider.id(), request(12, ""), AiConsent.none(),
                Duration.ofSeconds(1), new AtomicBoolean()).text()).isEqualTo("ok");
    }

    @Test
    void requiredSecretFailsClosedWhenSecureStoreIsUnavailable() {
        MemorySettings settings = new MemorySettings();
        AiProvider provider = provider("cloud.nostore",
                new AiProviderCapabilities(Set.of(AiOperation.SUMMARY), true, Set.of("token")),
                (request, context) -> new AiResponse("should not run"));
        AiExtensionService service = new AiExtensionService(Set.of(provider), settings, Optional.empty());
        service.setBookOptIn(13, true);

        assertThatThrownBy(() -> service.execute(provider.id(), request(13, ""), new AiConsent(true, false),
                Duration.ofSeconds(1), new AtomicBoolean()))
                .isInstanceOf(AiProviderException.class)
                .satisfies(error -> assertThat(((AiProviderException) error).kind())
                        .isEqualTo(AiProviderErrorKind.AUTHENTICATION));
    }

    @Test
    void cancellationIsCheckedBeforeProviderInvocation() {
        MemorySettings settings = new MemorySettings();
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = provider("local.cancel", AiProviderCapabilities.local(Set.of(AiOperation.SUMMARY)),
                (request, context) -> {
                    calls.incrementAndGet();
                    return new AiResponse("late");
                });
        AiExtensionService service = new AiExtensionService(Set.of(provider), settings, Optional.empty());
        service.setBookOptIn(14, true);
        AtomicBoolean cancelled = new AtomicBoolean(true);

        assertThatThrownBy(() -> service.execute(provider.id(), request(14, ""), AiConsent.none(),
                Duration.ofSeconds(1), cancelled))
                .isInstanceOf(AiProviderException.class)
                .satisfies(error -> assertThat(((AiProviderException) error).kind())
                        .isEqualTo(AiProviderErrorKind.CANCELLED));
        assertThat(calls).hasValue(0);
    }

    private static AiRequest request(long bookId, String content) {
        return new AiRequest(bookId, AiOperation.SUMMARY, "Summarize", content);
    }

    private static AiProvider provider(String id, AiProviderCapabilities capabilities, Executor executor) {
        return new AiProvider() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public AiProviderCapabilities capabilities() { return capabilities; }
            @Override public AiResponse execute(AiRequest request, AiProviderContext context) throws AiProviderException {
                return executor.execute(request, context);
            }
        };
    }

    @FunctionalInterface
    private interface Executor {
        AiResponse execute(AiRequest request, AiProviderContext context) throws AiProviderException;
    }

    private static final class MemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new HashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new HashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }

    private static final class MemorySecretStore implements SecretStore {
        private final Map<String, String> values = new HashMap<>();
        @Override public Optional<String> read(String key) { return Optional.ofNullable(values.get(key)); }
        @Override public void write(String key, String secret) { values.put(key, secret); }
        @Override public void delete(String key) { values.remove(key); }
        @Override public String backendId() { return "memory-test"; }
    }
}
