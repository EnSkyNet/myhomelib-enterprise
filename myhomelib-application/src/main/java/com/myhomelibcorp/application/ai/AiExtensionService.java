package com.myhomelibcorp.application.ai;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.security.SecretStore;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Host privacy/security boundary for optional AI providers.
 * No provider is enabled implicitly: callers must explicitly supply an approved provider collection.
 */
public final class AiExtensionService {
    private static final String BOOK_OPT_IN_PREFIX = "ai.bookOptIn.";
    private static final String SECRET_PREFIX = "myhomelib.ai.";
    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z0-9]+(?:[._-][a-z0-9]+)*");

    private final Map<String, AiProvider> providers;
    private final ApplicationSettingsPort settings;
    private final Optional<SecretStore> secretStore;

    public AiExtensionService(
            Collection<? extends AiProvider> providers,
            ApplicationSettingsPort settings,
            Optional<SecretStore> secretStore
    ) {
        Objects.requireNonNull(providers, "providers");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.secretStore = Objects.requireNonNull(secretStore, "secretStore");
        Map<String, AiProvider> byId = new LinkedHashMap<>();
        for (AiProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String id = validateProviderId(provider.id());
            Objects.requireNonNull(provider.displayName(), "provider displayName");
            Objects.requireNonNull(provider.capabilities(), "provider capabilities");
            if (byId.putIfAbsent(id, provider) != null) {
                throw new IllegalArgumentException("duplicate AI provider id: " + id);
            }
        }
        this.providers = Map.copyOf(byId);
    }

    public Set<String> providerIds() {
        return providers.keySet();
    }

    public boolean isBookOptedIn(String bookId) {
        requireBookId(bookId);
        return settings.getBoolean(BOOK_OPT_IN_PREFIX + bookId, false);
    }

    public void setBookOptIn(String bookId, boolean optedIn) {
        requireBookId(bookId);
        String key = BOOK_OPT_IN_PREFIX + bookId;
        if (optedIn) settings.putBoolean(key, true);
        else settings.remove(key);
    }

    public void saveProviderSecret(String providerId, String secretName, String value) {
        AiProvider provider = requireProvider(providerId);
        if (!provider.capabilities().requiredSecrets().contains(secretName)) {
            throw new IllegalArgumentException("secret is not declared by provider " + providerId);
        }
        if (value == null || value.isBlank()) throw new IllegalArgumentException("secret value is required");
        requireSecretStore().write(secretKey(providerId, secretName), value);
    }

    public void deleteProviderSecret(String providerId, String secretName) {
        AiProvider provider = requireProvider(providerId);
        if (!provider.capabilities().requiredSecrets().contains(secretName)) {
            throw new IllegalArgumentException("secret is not declared by provider " + providerId);
        }
        requireSecretStore().delete(secretKey(providerId, secretName));
    }

    /**
     * Executes one explicitly approved request without turning the lower-level per-book opt-in
     * into a hidden persistent preference. If the book was already opted in, that preference is
     * preserved; otherwise the temporary guard is removed in a finally block.
     */
    public AiResponse executeWithTransientBookOptIn(
            String providerId,
            AiRequest request,
            AiConsent consent,
            Duration timeout,
            AtomicBoolean cancelFlag
    ) throws AiProviderException {
        Objects.requireNonNull(request, "request");
        boolean alreadyOptedIn = isBookOptedIn(request.bookId());
        if (!alreadyOptedIn) setBookOptIn(request.bookId(), true);
        try {
            return execute(providerId, request, consent, timeout, cancelFlag);
        } finally {
            if (!alreadyOptedIn) setBookOptIn(request.bookId(), false);
        }
    }

    public AiResponse execute(
            String providerId,
            AiRequest request,
            AiConsent consent,
            Duration timeout,
            AtomicBoolean cancelFlag
    ) throws AiProviderException {
        AiProvider provider = requireProvider(providerId);
        Objects.requireNonNull(request, "request");
        consent = consent == null ? AiConsent.none() : consent;
        AiProviderCapabilities capabilities = provider.capabilities();

        if (!capabilities.operations().contains(request.operation())) {
            throw new AiProviderException(AiProviderErrorKind.UNAVAILABLE,
                    "AI provider does not support operation " + request.operation());
        }
        if (!isBookOptedIn(request.bookId())) {
            throw AiProviderException.consent("AI is not enabled for this book");
        }
        if (request.sharesBookContent() && !consent.allowBookContent()) {
            throw AiProviderException.consent("Explicit book-content sharing consent is required");
        }
        if (capabilities.networkRequired() && !consent.allowNetwork()) {
            throw AiProviderException.consent("Explicit network consent is required");
        }
        if (!capabilities.requiredSecrets().isEmpty() && secretStore.isEmpty()) {
            throw new AiProviderException(AiProviderErrorKind.AUTHENTICATION,
                    "Secure credential store is unavailable");
        }

        AiProviderContext context = new AiProviderContext(
                timeout,
                cancelFlag,
                capabilities.requiredSecrets(),
                name -> readProviderSecret(provider.id(), name));
        context.throwIfStopped();
        AiResponse response = provider.execute(request, context);
        context.throwIfStopped();
        if (response == null) {
            throw new AiProviderException(AiProviderErrorKind.INVALID_RESPONSE, "AI provider returned no response");
        }
        return response;
    }

    private Optional<String> readProviderSecret(String providerId, String secretName) {
        return secretStore.flatMap(store -> store.read(secretKey(providerId, secretName)));
    }

    private SecretStore requireSecretStore() {
        return secretStore.orElseThrow(() -> new IllegalStateException("Secure credential store is unavailable"));
    }

    private AiProvider requireProvider(String providerId) {
        AiProvider provider = providers.get(validateProviderId(providerId));
        if (provider == null) throw new IllegalArgumentException("unknown AI provider: " + providerId);
        return provider;
    }

    private static String secretKey(String providerId, String secretName) {
        return SECRET_PREFIX + validateProviderId(providerId) + "." + secretName;
    }

    private static String validateProviderId(String id) {
        if (id == null || !PROVIDER_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid AI provider id");
        }
        return id;
    }

    private static void requireBookId(String bookId) {
        if (bookId == null || bookId.isBlank()) throw new IllegalArgumentException("bookId is required");
        if (bookId.length() > 128) throw new IllegalArgumentException("bookId is too long");
    }
}
