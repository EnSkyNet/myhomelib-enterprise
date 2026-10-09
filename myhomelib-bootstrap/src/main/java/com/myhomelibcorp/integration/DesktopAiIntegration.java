package com.myhomelibcorp.integration;

import com.myhomelibcorp.application.ai.AiConsent;
import com.myhomelibcorp.application.ai.AiExtensionService;
import com.myhomelibcorp.application.ai.AiOperation;
import com.myhomelibcorp.application.ai.AiProvider;
import com.myhomelibcorp.application.ai.AiProviderException;
import com.myhomelibcorp.application.ai.AiRequest;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.infrastructure.ai.OpenAiResponsesProvider;
import com.myhomelibcorp.shared.security.SecretStore;
import com.myhomelibcorp.ui.integration.IntegrationBackend.AiConfiguration;
import com.myhomelibcorp.ui.integration.IntegrationBackend.AiExecutionResult;
import com.myhomelibcorp.ui.integration.IntegrationBackend.AiProviderInfo;
import com.myhomelibcorp.ui.integration.IntegrationBackend.Result;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static com.myhomelibcorp.integration.DesktopIntegrationSupport.safeMessage;

/** Owns desktop-facing AI provider discovery, configuration, secrets and execution. */
@Slf4j
final class DesktopAiIntegration {

    private final ApplicationSettingsPort settings;
    private final RuntimeExtensionRegistry runtimeExtensions;
    private final Supplier<List<AiProvider>> builtInAiProviderSupplier;
    private final Supplier<Optional<SecretStore>> secretStoreSupplier;
    private volatile List<AiProvider> resolvedBuiltInAiProviders;

    DesktopAiIntegration(ApplicationSettingsPort settings,
                         RuntimeExtensionRegistry runtimeExtensions,
                         Supplier<List<AiProvider>> builtInAiProviderSupplier,
                         Supplier<Optional<SecretStore>> secretStoreSupplier) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.runtimeExtensions = Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
        this.builtInAiProviderSupplier = Objects.requireNonNull(builtInAiProviderSupplier, "builtInAiProviderSupplier");
        this.secretStoreSupplier = Objects.requireNonNull(secretStoreSupplier, "secretStoreSupplier");
    }

    AiConfiguration configuration() {
        Optional<SecretStore> secrets = secretStore();
        boolean apiKey = false;
        if (secrets.isPresent()) {
            try {
                apiKey = secrets.orElseThrow()
                        .read("myhomelib.ai." + OpenAiResponsesProvider.ID + ".api-key")
                        .isPresent();
            } catch (Exception failure) {
                log.warn("Cannot read AI API-key presence from the secret store; reporting it as unavailable: {}",
                        safeMessage(failure));
            }
        }
        return new AiConfiguration(
                settings.get(OpenAiResponsesProvider.ENDPOINT_KEY, OpenAiResponsesProvider.DEFAULT_ENDPOINT),
                settings.get(OpenAiResponsesProvider.MODEL_KEY, OpenAiResponsesProvider.DEFAULT_MODEL),
                Math.max(1, settings.getInt(OpenAiResponsesProvider.MAX_OUTPUT_TOKENS_KEY, 2048)),
                apiKey,
                secrets.isPresent(),
                secrets.map(SecretStore::backendId).orElse(""));
    }

    synchronized List<AiProviderInfo> providers() {
        return currentProviders().stream()
                .map(provider -> new AiProviderInfo(provider.id(), provider.displayName(),
                        provider.capabilities().operations().stream().map(Enum::name).sorted().toList(),
                        provider.capabilities().networkRequired(),
                        provider.capabilities().requiredSecrets().stream().sorted().toList()))
                .sorted(Comparator.comparing(AiProviderInfo::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    synchronized Result saveConfiguration(String endpoint, String model, int maxOutputTokens, String apiKey) {
        try {
            OpenAiResponsesProvider.validateEndpoint(endpoint);
            if (model == null || model.isBlank()) throw new IllegalArgumentException("Вкажіть модель ШІ");
            if (maxOutputTokens < 1 || maxOutputTokens > 32768) {
                throw new IllegalArgumentException("Максимальна кількість токенів відповіді має бути від 1 до 32768");
            }
            settings.put(OpenAiResponsesProvider.ENDPOINT_KEY, endpoint.trim());
            settings.put(OpenAiResponsesProvider.MODEL_KEY, model.trim());
            settings.putInt(OpenAiResponsesProvider.MAX_OUTPUT_TOKENS_KEY, maxOutputTokens);
            if (apiKey != null && !apiKey.isBlank()) {
                return saveProviderSecret(OpenAiResponsesProvider.ID, "api-key", apiKey);
            }
            return Result.ok("ui.integration.ai.settings_saved");
        } catch (Exception e) {
            return Result.failTechnical("ui.integration.ai.settings_save_failed", safeMessage(e));
        }
    }

    synchronized Result clearApiKey() {
        return deleteProviderSecret(OpenAiResponsesProvider.ID, "api-key");
    }

    synchronized Result saveProviderSecret(String providerId, String secretName, String value) {
        try {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Секретне значення порожнє");
            AiProvider provider = requireProvider(providerId);
            AiExtensionService service = service();
            service.saveProviderSecret(provider.id(), secretName, value);
            return Result.ok("ui.integration.ai.secret_saved", secretStore().map(SecretStore::backendId).orElse(""));
        } catch (Exception e) {
            return Result.failTechnical("ui.integration.ai.secret_save_failed", safeMessage(e));
        }
    }

    synchronized Result deleteProviderSecret(String providerId, String secretName) {
        try {
            AiProvider provider = requireProvider(providerId);
            service().deleteProviderSecret(provider.id(), secretName);
            return Result.ok("ui.integration.ai.secret_deleted");
        } catch (Exception e) {
            return Result.failTechnical("ui.integration.ai.secret_delete_failed", safeMessage(e));
        }
    }

    AiExecutionResult execute(String providerId, String bookId, AiOperation operation, String prompt,
                              String bookContent, boolean allowNetwork, boolean allowBookContent) {
        try {
            AiProvider provider = requireProvider(providerId);
            AiExtensionService service = service();
            AiRequest request = new AiRequest(bookId, operation, prompt, bookContent);
            var response = service.executeWithTransientBookOptIn(provider.id(), request,
                    new AiConsent(allowNetwork, allowBookContent), Duration.ofSeconds(120), new AtomicBoolean(false));
            return new AiExecutionResult(true, response.text(), "");
        } catch (AiProviderException e) {
            return new AiExecutionResult(false, safeMessage(e), e.kind().name());
        } catch (Exception e) {
            return new AiExecutionResult(false, safeMessage(e), "FAILED");
        }
    }

    private List<AiProvider> currentProviders() {
        Map<String, AiProvider> providers = new LinkedHashMap<>();
        for (AiProvider provider : builtInProviders()) providers.put(provider.id(), provider);
        for (AiProvider provider : runtimeExtensions.aiProviders()) providers.putIfAbsent(provider.id(), provider);
        return List.copyOf(providers.values());
    }

    private AiProvider requireProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("Не вибрано провайдера ШІ");
        return currentProviders().stream().filter(provider -> provider.id().equals(providerId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Провайдер ШІ недоступний: " + providerId));
    }

    private AiExtensionService service() {
        return new AiExtensionService(currentProviders(), settings, secretStore());
    }

    private List<AiProvider> builtInProviders() {
        List<AiProvider> current = resolvedBuiltInAiProviders;
        if (current != null) return current;
        synchronized (this) {
            current = resolvedBuiltInAiProviders;
            if (current == null) {
                current = sanitizeProviders(builtInAiProviderSupplier.get());
                resolvedBuiltInAiProviders = current;
            }
            return current;
        }
    }

    private Optional<SecretStore> secretStore() {
        Optional<SecretStore> value = secretStoreSupplier.get();
        return value == null ? Optional.empty() : value;
    }

    static List<AiProvider> sanitizeProviders(List<AiProvider> providers) {
        if (providers == null || providers.isEmpty()) return List.of();
        return providers.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(AiProvider::id))
                .toList();
    }
}
