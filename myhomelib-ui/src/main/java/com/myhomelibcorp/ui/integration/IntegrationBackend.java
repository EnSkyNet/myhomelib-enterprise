package com.myhomelibcorp.ui.integration;

import com.myhomelibcorp.application.ai.AiOperation;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewItem;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewSelection;

import java.nio.file.Path;
import java.util.List;

/**
 * Desktop-facing boundary for optional integrations that are implemented by the bootstrap layer.
 * UI deliberately does not depend on infrastructure or plugin implementation classes.
 */
public interface IntegrationBackend {

    record Result(boolean success, String message) {
        public Result {
            message = message == null ? "" : message;
        }
        public static Result ok(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }

    record WebDavConfiguration(
            String endpoint,
            String username,
            String syncKey,
            boolean configured,
            boolean secureStoreAvailable,
            String secureStoreBackend,
            String deviceId
    ) {
        public WebDavConfiguration {
            endpoint = endpoint == null ? "" : endpoint;
            username = username == null ? "" : username;
            syncKey = syncKey == null ? "" : syncKey;
            secureStoreBackend = secureStoreBackend == null ? "" : secureStoreBackend;
            deviceId = deviceId == null ? "" : deviceId;
        }
    }

    enum SyncState { COMPLETED, NO_CHANGES, CONFLICT, FAILED }

    record WebDavSyncAttempt(
            SyncState state,
            String message,
            String conflictToken,
            List<SyncConflictReviewItem> conflicts
    ) {
        public WebDavSyncAttempt {
            state = state == null ? SyncState.FAILED : state;
            message = message == null ? "" : message;
            conflictToken = conflictToken == null ? "" : conflictToken;
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }
        public boolean hasConflict() { return state == SyncState.CONFLICT && !conflictToken.isBlank(); }
    }

    record PluginInfo(
            String pluginId,
            String displayName,
            String version,
            String state,
            String trust,
            List<String> services,
            List<String> permissions,
            String source,
            String failure,
            String packageSha256,
            boolean installedPackage
    ) {
        public PluginInfo {
            pluginId = pluginId == null ? "" : pluginId;
            displayName = displayName == null ? "" : displayName;
            version = version == null ? "" : version;
            state = state == null ? "" : state;
            trust = trust == null ? "" : trust;
            services = services == null ? List.of() : List.copyOf(services);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
            source = source == null ? "" : source;
            failure = failure == null ? "" : failure;
            packageSha256 = packageSha256 == null ? "" : packageSha256;
        }
    }

    record AiConfiguration(
            String endpoint,
            String model,
            int maxOutputTokens,
            boolean apiKeyStored,
            boolean secureStoreAvailable,
            String secureStoreBackend
    ) {
        public AiConfiguration {
            endpoint = endpoint == null ? "" : endpoint;
            model = model == null ? "" : model;
            secureStoreBackend = secureStoreBackend == null ? "" : secureStoreBackend;
        }
    }

    record AiProviderInfo(String id, String displayName, List<String> operations, boolean networkRequired, List<String> requiredSecrets) {
        public AiProviderInfo {
            id = id == null ? "" : id;
            displayName = displayName == null ? "" : displayName;
            operations = operations == null ? List.of() : List.copyOf(operations);
            requiredSecrets = requiredSecrets == null ? List.of() : List.copyOf(requiredSecrets);
        }
    }

    record AiExecutionResult(boolean success, String text, String errorKind) {
        public AiExecutionResult {
            text = text == null ? "" : text;
            errorKind = errorKind == null ? "" : errorKind;
        }
    }

    WebDavConfiguration webDavConfiguration();

    Result saveWebDavConfiguration(String endpoint, String username, String password, String syncKey);

    Result clearWebDavConfiguration();

    Result testWebDav();

    WebDavSyncAttempt synchronizeWebDav();

    Result resolveWebDavConflict(String conflictToken, List<SyncConflictReviewSelection> selections);

    List<PluginInfo> plugins();

    Result installPlugin(Path jarFile);

    Result enablePlugin(String pluginId, boolean approveRequestedPermissions);

    Result disablePlugin(String pluginId);

    Result removePlugin(String pluginId);

    AiConfiguration aiConfiguration();

    List<AiProviderInfo> aiProviders();

    Result saveAiConfiguration(String endpoint, String model, int maxOutputTokens, String apiKey);

    Result clearAiApiKey();

    Result saveAiProviderSecret(String providerId, String secretName, String value);

    Result deleteAiProviderSecret(String providerId, String secretName);

    AiExecutionResult executeAi(String providerId,
                                String bookId,
                                AiOperation operation,
                                String prompt,
                                String bookContent,
                                boolean allowNetwork,
                                boolean allowBookContent);
}
