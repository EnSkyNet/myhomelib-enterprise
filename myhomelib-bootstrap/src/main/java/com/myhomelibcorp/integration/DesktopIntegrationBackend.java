package com.myhomelibcorp.integration;

import static com.myhomelibcorp.integration.DesktopIntegrationSupport.advanceCursors;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.gzipBase64;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.nonBlank;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.parseLong;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.readBounded;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.safeId;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.safeMessage;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.sameEndpoint;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.sanitizeFilePart;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.sha256;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.validateWebDavEndpoint;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.myhomelibcorp.application.ai.AiOperation;
import com.myhomelibcorp.application.ai.AiProvider;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.extension.DeviceProfileDetector;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.kobo.KoboSyncService;
import com.myhomelibcorp.application.kobo.KoboSyncCursor;
import com.myhomelibcorp.application.annotation.remote.ExportAnnotationsToReadwiseUseCase;
import com.myhomelibcorp.application.annotation.export.AnnotationExportSelection;
import com.myhomelibcorp.infrastructure.integration.readwise.ReadwiseAnnotationExportAdapter;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.DeviceTargetProfile;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.service.PortableUserDataService;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewItem;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewSelection;
import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import com.myhomelibcorp.infrastructure.sync.folder.SyncBundleJsonCodec;
import com.myhomelibcorp.infrastructure.sync.webdav.JdkWebDavHttpClient;
import com.myhomelibcorp.infrastructure.sync.webdav.WebDavCredentialStore;
import com.myhomelibcorp.infrastructure.sync.webdav.WebDavSyncAdapter;
import com.myhomelibcorp.infrastructure.sync.webdav.WebDavSyncSecrets;
import com.myhomelibcorp.plugin.api.PluginApproval;
import com.myhomelibcorp.plugin.api.PluginEntrypoint;
import com.myhomelibcorp.plugin.api.PluginLoader;
import com.myhomelibcorp.plugin.api.LoadedPlugin;
import com.myhomelibcorp.plugin.api.PluginManager;
import com.myhomelibcorp.plugin.api.PluginManifest;
import com.myhomelibcorp.plugin.api.PluginInvocationResult;
import com.myhomelibcorp.plugin.api.PluginService;
import com.myhomelibcorp.plugin.api.PluginState;
import com.myhomelibcorp.plugin.api.PluginStatus;
import com.myhomelibcorp.plugin.api.PluginTrustLevel;
import com.myhomelibcorp.shared.security.SecretStore;
import com.myhomelibcorp.shared.security.SecretStoreContext;
import com.myhomelibcorp.shared.security.SecretStoreProvider;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.SafeFileNameSupport;
import com.myhomelibcorp.ui.integration.IntegrationBackend;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Production wiring for WebDAV user-data sync, plugin packages and optional AI providers. */
@Component
@Slf4j
public final class DesktopIntegrationBackend implements IntegrationBackend {
    private static final String WEB_PREFIX = "sync.webdav.";
    private static final String WEB_ENDPOINT = WEB_PREFIX + "endpoint";
    private static final String WEB_SEQUENCE = WEB_PREFIX + "sequence";
    private static final String PLUGIN_APPROVAL_PREFIX = "plugins.approved.";
    private final ApplicationSettingsPort settings;
    private final Supplier<PortableUserDataService> portableUserDataSupplier;
    private final RuntimeExtensionRegistry runtimeExtensions;
    private final Supplier<ObjectMapper> mapperSupplier;
    private final WebDavSyncStateStore webDavState;
    private volatile PortableSnapshotConflictService resolvedSnapshotConflicts;
    private final DesktopAiIntegration aiIntegration;
    private final LibraryOperationCoordinator operationCoordinator;
    private volatile KoboSyncService koboSync;
    private volatile ExportAnnotationsToReadwiseUseCase readwiseExport;
    private volatile ReadwiseAnnotationExportAdapter readwiseAdapter;
    private final CollectionLifecyclePort collectionLifecycle;
    private volatile Optional<SecretStore> resolvedSecretStore;
    private final PluginManager pluginManager = new PluginManager(new PluginLoader(Set.of()));
    private final Path pluginDir = AppPaths.dataDir().resolve("plugins");
    private final PluginRuntimePackageStager pluginRuntimePackageStager = new PluginRuntimePackageStager(pluginDir);
    private final Map<String, PluginPackageDescriptor> pluginPackages = new ConcurrentHashMap<>();
    private final Map<String, BundledPlugin> bundledPlugins = new ConcurrentHashMap<>();
    private final Map<String, URLClassLoader> pluginClassLoaders = new ConcurrentHashMap<>();
    private final Map<String, Path> pluginRuntimeWorkspaces = new ConcurrentHashMap<>();
    private final Map<String, String> pluginFailures = new ConcurrentHashMap<>();
    private final Map<String, PendingSync> pendingSync = new ConcurrentHashMap<>();

    /** Compatibility constructor for isolated plugin/runtime tests that do not exercise WebDAV sync. */
    DesktopIntegrationBackend(ApplicationSettingsPort settings,
                              PortableUserDataService portableUserData,
                              RuntimeExtensionRegistry runtimeExtensions,
                              ObjectMapper mapper,
                              List<AiProvider> builtInAiProviders) {
        this(settings,
                () -> Objects.requireNonNull(portableUserData, "portableUserData"),
                runtimeExtensions,
                () -> Objects.requireNonNull(mapper, "mapper"),
                () -> builtInAiProviders,
                null, null);
    }

    @Autowired
    public DesktopIntegrationBackend(ApplicationSettingsPort settings,
                                     ObjectProvider<PortableUserDataService> portableUserData,
                                     RuntimeExtensionRegistry runtimeExtensions,
                                     ObjectProvider<ObjectMapper> mapper,
                                     ObjectProvider<AiProvider> builtInAiProviders,
                                     LibraryOperationCoordinator operationCoordinator,
                                     CollectionLifecyclePort collectionLifecycle) {
        this(settings,
                Objects.requireNonNull(portableUserData, "portableUserData")::getObject,
                runtimeExtensions,
                Objects.requireNonNull(mapper, "mapper")::getObject,
                () -> Objects.requireNonNull(builtInAiProviders, "builtInAiProviders")
                        .orderedStream().toList(),
                operationCoordinator, collectionLifecycle);
    }

    DesktopIntegrationBackend(ApplicationSettingsPort settings,
                              Supplier<PortableUserDataService> portableUserDataSupplier,
                              RuntimeExtensionRegistry runtimeExtensions,
                              Supplier<ObjectMapper> mapperSupplier,
                              Supplier<List<AiProvider>> builtInAiProviderSupplier,
                              LibraryOperationCoordinator operationCoordinator,
                              CollectionLifecyclePort collectionLifecycle) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.portableUserDataSupplier = Objects.requireNonNull(portableUserDataSupplier, "portableUserDataSupplier");
        this.runtimeExtensions = Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
        this.mapperSupplier = Objects.requireNonNull(mapperSupplier, "mapperSupplier");
        Supplier<List<AiProvider>> aiProviders = Objects.requireNonNull(builtInAiProviderSupplier, "builtInAiProviderSupplier");
        this.webDavState = new WebDavSyncStateStore(this.settings, this.mapperSupplier);
        this.aiIntegration = new DesktopAiIntegration(this.settings, this.runtimeExtensions, aiProviders, this::secretStore);
        this.operationCoordinator = operationCoordinator;
        this.collectionLifecycle = collectionLifecycle;
        synchronizeCalibreRuntimeProperties();
        try {
            SafeFilePublication.createDirectoriesNoLinks(pluginDir, "Plugin directory");
            scanBundledPlugins();
            scanPluginPackages();
            autoEnableApprovedPlugins();
        } catch (Exception e) {
            pluginFailures.put("__plugin_directory__", safeMessage(e));
        }
    }

    @Autowired
    void setKoboSync(ObjectProvider<KoboSyncService> provider) {
        this.koboSync = provider == null ? null : provider.getIfAvailable();
    }

    @Autowired
    void setReadwise(ObjectProvider<ExportAnnotationsToReadwiseUseCase> exportProvider,
                     ObjectProvider<ReadwiseAnnotationExportAdapter> adapterProvider) {
        this.readwiseExport = exportProvider == null ? null : exportProvider.getIfAvailable();
        this.readwiseAdapter = adapterProvider == null ? null : adapterProvider.getIfAvailable();
    }

    @Override
    public ReadwiseStatus readwiseStatus() {
        ReadwiseAnnotationExportAdapter adapter = readwiseAdapter;
        return adapter == null ? new ReadwiseStatus(false, false, "")
                : new ReadwiseStatus(adapter.configured(), adapter.secureStoreAvailable(), adapter.secureStoreBackend());
    }

    @Override
    public Result saveReadwiseToken(String token) {
        try { requireReadwiseAdapter().configureToken(token); return Result.ok("ui.integration.readwise.saved"); }
        catch (RuntimeException e) { return Result.failTechnical("ui.integration.readwise.save_failed", safeMessage(e)); }
    }

    @Override
    public Result clearReadwiseToken() {
        try { requireReadwiseAdapter().clearToken(); return Result.ok("ui.integration.readwise.cleared"); }
        catch (RuntimeException e) { return Result.failTechnical("ui.integration.readwise.clear_failed", safeMessage(e)); }
    }

    @Override
    public Result testReadwise() {
        try { requireReadwiseExport().testConnection(); return Result.ok("ui.integration.readwise.test_ok"); }
        catch (RuntimeException e) { return Result.failTechnical("ui.integration.readwise.test_failed", safeMessage(e)); }
    }

    @Override
    public Result exportAnnotationsToReadwise() {
        try {
            var result = requireReadwiseExport().export(AnnotationExportSelection.all());
            return Result.ok("ui.integration.readwise.export_ok", result.exported(), result.unchanged());
        } catch (RuntimeException e) { return Result.failTechnical("ui.integration.readwise.export_failed", safeMessage(e)); }
    }

    private ReadwiseAnnotationExportAdapter requireReadwiseAdapter() {
        if (readwiseAdapter == null) throw new IllegalStateException("Readwise integration is unavailable");
        return readwiseAdapter;
    }
    private ExportAnnotationsToReadwiseUseCase requireReadwiseExport() {
        if (readwiseExport == null) throw new IllegalStateException("Readwise integration is unavailable");
        return readwiseExport;
    }

    @Override
    public KoboStatus koboStatus() {
        KoboSyncService service = koboSync;
        if (service == null) return new KoboStatus("", false, false, "", "", "unavailable");
        var status = service.status();
        return new KoboStatus(status.deviceId(), status.enabled(), status.secureStoreAvailable(),
                status.secureStoreBackend(), status.lastSyncAt() == null ? "" : status.lastSyncAt().toString(), status.lastError());
    }

    @Override
    public KoboCredential enableOrRotateKobo() {
        KoboSyncService service = koboSync;
        if (service == null) return new KoboCredential(Result.fail("ui.integration.kobo.unavailable"), "", "");
        try {
            var credential = service.enableOrRotate();
            return new KoboCredential(Result.ok("ui.integration.kobo.credential_ready"), credential.deviceId(), credential.token());
        } catch (RuntimeException failure) {
            return new KoboCredential(Result.failTechnical("ui.integration.kobo.enable_failed", safeMessage(failure)), "", "");
        }
    }

    @Override
    public Result revokeKobo() {
        KoboSyncService service = koboSync;
        if (service == null) return Result.fail("ui.integration.kobo.unavailable");
        try {
            service.revoke();
            return Result.ok("ui.integration.kobo.revoked");
        } catch (RuntimeException failure) {
            return Result.failTechnical("ui.integration.kobo.revoke_failed", safeMessage(failure));
        }
    }

    @Override
    public Result synchronizeKoboNow() {
        KoboSyncService service = koboSync;
        if (service == null) return Result.fail("ui.integration.kobo.unavailable");
        try {
            var batch = service.synchronize(KoboSyncCursor.initial(), 1);
            return Result.ok("ui.integration.kobo.sync_ready", batch.changes().size());
        } catch (RuntimeException failure) {
            return Result.failTechnical("ui.integration.kobo.sync_failed", safeMessage(failure));
        }
    }

    // ==================== WebDAV ====================

    @Override
    public WebDavConfiguration webDavConfiguration() {
        String endpoint = settings.get(WEB_ENDPOINT, "").trim();
        String deviceId = webDavState.ensureDeviceId();
        if (endpoint.isBlank() || secretStore().isEmpty()) {
            return new WebDavConfiguration(endpoint, "", "", false, secretStore().isPresent(),
                    secretStore().map(SecretStore::backendId).orElse(""), deviceId);
        }
        try {
            URI uri = validateWebDavEndpoint(endpoint);
            Optional<WebDavSyncSecrets> loaded = new WebDavCredentialStore(secretStore().orElseThrow(), uri).load();
            return loaded.map(value -> new WebDavConfiguration(endpoint, value.username(), value.syncKeyBase64(),
                            true, true, secretStore().orElseThrow().backendId(), deviceId))
                    .orElseGet(() -> new WebDavConfiguration(endpoint, "", "", false, true,
                            secretStore().orElseThrow().backendId(), deviceId));
        } catch (Exception e) {
            return new WebDavConfiguration(endpoint, "", "", false, true,
                    secretStore().orElseThrow().backendId(), deviceId);
        }
    }

    @Override
    public synchronized Result saveWebDavConfiguration(String endpoint, String username, String password, String syncKey) {
        try {
            SecretStore secretsBackend = secretStore().orElseThrow(() -> new IllegalStateException(
                    "Захищене сховище облікових даних недоступне. WebDAV-синхронізація не зберігатиме пароль у відкритому вигляді."));
            URI uri = validateWebDavEndpoint(endpoint);
            String oldEndpoint = settings.get(WEB_ENDPOINT, "").trim();
            WebDavCredentialStore credentialStore = new WebDavCredentialStore(secretsBackend, uri);
            Optional<WebDavSyncSecrets> existing = credentialStore.load();

            String effectiveUser = nonBlank(username).orElseGet(() -> existing.map(WebDavSyncSecrets::username).orElse(""));
            String effectivePassword = nonBlank(password).orElseGet(() -> existing.map(WebDavSyncSecrets::password).orElse(""));
            if (effectiveUser.isBlank()) throw new IllegalArgumentException("Вкажіть ім'я користувача WebDAV");
            if (effectivePassword.isBlank()) throw new IllegalArgumentException("Вкажіть пароль WebDAV");

            WebDavSyncSecrets value;
            if (nonBlank(syncKey).isPresent()) {
                value = new WebDavSyncSecrets(effectiveUser, effectivePassword, syncKey.trim());
                credentialStore.save(value);
            } else if (existing.isPresent()) {
                WebDavSyncSecrets previous = existing.orElseThrow();
                value = new WebDavSyncSecrets(effectiveUser, effectivePassword,
                        previous.syncKeyBase64(), previous.previousSyncKeyBase64());
                credentialStore.save(value);
            } else {
                value = credentialStore.createAndSave(effectiveUser, effectivePassword);
            }

            settings.put(WEB_ENDPOINT, uri.toString());
            webDavState.ensureDeviceId();
            if (!oldEndpoint.isBlank() && !sameEndpoint(oldEndpoint, uri.toString())) {
                try {
                    new WebDavCredentialStore(secretsBackend, validateWebDavEndpoint(oldEndpoint)).delete();
                } catch (Exception cleanupFailure) {
                    log.warn("WebDAV endpoint changed but credentials for the previous endpoint could not be removed: {}",
                            cleanupFailure.toString());
                }
                webDavState.reset();
                settings.remove(WEB_SEQUENCE);
            }
            return Result.ok("ui.integration.webdav.saved");
        } catch (Exception e) {
            return Result.failTechnical("ui.integration.webdav.save_failed", safeMessage(e));
        }
    }

    @Override
    public synchronized Result clearWebDavConfiguration() {
        String endpoint = settings.get(WEB_ENDPOINT, "").trim();
        try {
            if (!endpoint.isBlank()) {
                SecretStore backend = secretStore().orElseThrow(() -> new IllegalStateException(
                        "Secure secret store is unavailable; WebDAV credentials cannot be removed safely"));
                new WebDavCredentialStore(backend, validateWebDavEndpoint(endpoint)).delete();
            }
            settings.remove(WEB_ENDPOINT);
            webDavState.reset();
            settings.remove(WEB_SEQUENCE);
            pendingSync.clear();
            return Result.ok("ui.integration.webdav.cleared");
        } catch (Exception failure) {
            // Keep endpoint/state intact so the user can retry. Dropping the endpoint after a native
            // secret-store failure would orphan credentials that the application can no longer address.
            log.warn("Cannot clear WebDAV configuration safely; configuration was preserved: {}",
                    failure.toString());
            return Result.failTechnical("ui.integration.webdav.clear_failed", safeMessage(failure));
        }
    }

    @Override
    public Result testWebDav() {
        try {
            WebDavSyncAdapter adapter = webDavAdapter();
            List<ChangeSet> bundles = adapter.pull(Map.of());
            long snapshots = bundles.stream().filter(this::isSnapshotChangeSet).count();
            return Result.ok("ui.integration.webdav.test_success", snapshots);
        } catch (Exception e) {
            return Result.failTechnical("ui.integration.webdav.test_failed", safeMessage(e));
        }
    }

    @Override
    public synchronized WebDavSyncAttempt synchronizeWebDav() {
        LibraryOperationCoordinator.Lease lease = null;
        try {
            lease = acquireWebDavSyncLease();
            String originCollectionId = activeCollectionIdForSync();
            WebDavSyncAdapter adapter = webDavAdapter();
            String deviceId = webDavState.ensureDeviceId();
            Snapshot local = exportSnapshot();
            String baseline = webDavState.lastLocalHash();
            Map<String, Long> cursors = webDavState.loadCursors();
            List<ChangeSet> pulled = adapter.pull(cursors);
            Map<String, Long> nextCursors = advanceCursors(cursors, pulled);

            List<RemoteSnapshot> external = latestExternalSnapshots(pulled, deviceId);
            if (external.isEmpty()) {
                if (baseline.isBlank()) {
                    long seq = pushSnapshot(adapter, local, deviceId);
                    nextCursors.put(deviceId, seq);
                    webDavState.commit(local.hash(), nextCursors);
                    return new WebDavSyncAttempt(SyncState.COMPLETED,
                            UserMessage.of("ui.integration.webdav.sync.initial_uploaded"), "", List.of());
                }
                if (baseline.equals(local.hash())) {
                    webDavState.saveCursors(nextCursors);
                    return new WebDavSyncAttempt(SyncState.NO_CHANGES, UserMessage.of("ui.integration.webdav.sync.no_changes"), "", List.of());
                }
                long seq = pushSnapshot(adapter, local, deviceId);
                nextCursors.put(deviceId, seq);
                webDavState.commit(local.hash(), nextCursors);
                return new WebDavSyncAttempt(SyncState.COMPLETED,
                        UserMessage.of("ui.integration.webdav.sync.local_uploaded"), "", List.of());
            }

            Set<String> changedRemoteHashes = new LinkedHashSet<>();
            for (RemoteSnapshot remote : external) {
                if (baseline.isBlank() || !baseline.equals(remote.snapshot().hash())) changedRemoteHashes.add(remote.snapshot().hash());
            }
            if (changedRemoteHashes.size() > 1) {
                return new WebDavSyncAttempt(SyncState.FAILED,
                        UserMessage.of("ui.integration.webdav.sync.multiple_remote_versions"),
                        "", List.of());
            }

            RemoteSnapshot remote = external.stream().max(Comparator.comparing(value -> value.changeSet().createdAt())).orElseThrow();
            if (local.hash().equals(remote.snapshot().hash())) {
                webDavState.commit(local.hash(), nextCursors);
                return new WebDavSyncAttempt(SyncState.NO_CHANGES,
                        UserMessage.of("ui.integration.webdav.sync.same"), "", List.of());
            }

            boolean localChanged = baseline.isBlank() || !baseline.equals(local.hash());
            boolean remoteChanged = baseline.isBlank() || !baseline.equals(remote.snapshot().hash());
            if (!localChanged && remoteChanged) {
                Snapshot restored = restoreRemoteSnapshot(remote.snapshot());
                webDavState.commit(restored.hash(), nextCursors);
                return new WebDavSyncAttempt(SyncState.COMPLETED,
                        UserMessage.of("ui.integration.webdav.sync.remote_applied"), "", List.of());
            }
            if (localChanged && !remoteChanged) {
                long seq = pushSnapshot(adapter, local, deviceId);
                nextCursors.put(deviceId, seq);
                webDavState.commit(local.hash(), nextCursors);
                return new WebDavSyncAttempt(SyncState.COMPLETED,
                        UserMessage.of("ui.integration.webdav.sync.local_uploaded"), "", List.of());
            }

            List<SyncConflictReviewItem> conflicts = snapshotConflicts().conflicts(local.bytes(), remote.snapshot().bytes());
            if (conflicts.isEmpty()) {
                return new WebDavSyncAttempt(SyncState.FAILED,
                        UserMessage.of("ui.integration.webdav.sync.conflict_sections_unknown"),
                        "", List.of());
            }
            String token = UUID.randomUUID().toString();
            pendingSync.clear();
            pendingSync.put(token, new PendingSync(originCollectionId, local, remote, Map.copyOf(nextCursors)));
            return new WebDavSyncAttempt(SyncState.CONFLICT,
                    UserMessage.of("ui.integration.webdav.sync.conflicts_detected", conflicts.size()),
                    token, conflicts);
        } catch (Exception e) {
            if (lease != null) lease.markFailed(e);
            return new WebDavSyncAttempt(SyncState.FAILED,
                    UserMessage.technical("ui.integration.webdav.sync.failed", safeMessage(e)), "", List.of());
        } finally {
            if (lease != null) lease.close();
        }
    }

    @Override
    public synchronized Result resolveWebDavConflict(String conflictToken, List<SyncConflictReviewSelection> selections) {
        if (conflictToken == null || conflictToken.isBlank()) return Result.fail("ui.integration.webdav.conflict.missing");
        PendingSync pending = pendingSync.get(conflictToken);
        if (pending == null) return Result.fail("ui.integration.webdav.conflict.stale");
        LibraryOperationCoordinator.Lease lease = null;
        try {
            lease = acquireWebDavSyncLease();
            if (!sameActiveCollection(pending.collectionId())) {
                pendingSync.remove(conflictToken, pending);
                return Result.fail("ui.integration.webdav.conflict.collection_changed");
            }
            if (!pendingSync.remove(conflictToken, pending)) {
                return Result.fail("ui.integration.webdav.conflict.stale");
            }
            WebDavSyncAdapter adapter = webDavAdapter();
            String deviceId = webDavState.ensureDeviceId();
            Snapshot currentLocal = exportSnapshot();
            if (!currentLocal.hash().equals(pending.local().hash())) {
                return Result.fail("ui.integration.webdav.conflict.local_changed");
            }
            Map<String, Long> currentCursors = webDavState.loadCursors();
            List<ChangeSet> check = adapter.pull(currentCursors);
            Optional<ChangeSet> originalRemote = check.stream()
                    .filter(set -> set.changeSetId().equals(pending.remote().changeSet().changeSetId()))
                    .findFirst();
            // If the bundle is no longer returned because cursors changed elsewhere, verify by pulling from zero.
            if (originalRemote.isEmpty()) {
                originalRemote = adapter.pull(Map.of()).stream()
                        .filter(set -> set.changeSetId().equals(pending.remote().changeSet().changeSetId()))
                        .findFirst();
            }
            if (originalRemote.isEmpty()) {
                return Result.fail("ui.integration.webdav.conflict.remote_changed");
            }
            List<RemoteSnapshot> latest = latestExternalSnapshots(adapter.pull(Map.of()), deviceId);
            boolean remoteStillCurrent = latest.stream().anyMatch(value ->
                    value.changeSet().changeSetId().equals(pending.remote().changeSet().changeSetId()));
            if (!remoteStillCurrent || latest.stream().anyMatch(value ->
                    value.changeSet().createdAt().isAfter(pending.remote().changeSet().createdAt())
                            && !value.snapshot().hash().equals(pending.remote().snapshot().hash()))) {
                return Result.fail("ui.integration.webdav.conflict.newer_remote");
            }

            byte[] mergedBytes = snapshotConflicts().merge(
                    pending.local().bytes(), pending.remote().snapshot().bytes(), selections);
            Snapshot requested = new Snapshot(mergedBytes, sha256(mergedBytes));
            Snapshot resolved = currentLocal.hash().equals(requested.hash())
                    ? currentLocal
                    : restoreRemoteSnapshot(requested);

            // Publish the resolved state even when the user selected the remote value for every section.
            // This creates a new common head so the remaining devices converge on the reviewed result.
            long seq = pushSnapshot(adapter, resolved, deviceId);
            Map<String, Long> nextCursors = new HashMap<>(pending.nextCursors());
            nextCursors.put(deviceId, seq);
            webDavState.commit(resolved.hash(), nextCursors);
            return Result.ok("ui.integration.webdav.conflict.resolved");
        } catch (Exception e) {
            if (lease != null) lease.markFailed(e);
            return Result.failTechnical("ui.integration.webdav.conflict.resolve_failed", safeMessage(e));
        } finally {
            if (lease != null) lease.close();
        }
    }

    // ==================== Plugins ====================

    @Override
    public synchronized List<PluginInfo> plugins() {
        try {
            scanPluginPackages();
        } catch (Exception e) {
            pluginFailures.put("__scan__", safeMessage(e));
        }
        List<PluginInfo> result = new ArrayList<>();
        for (BundledPlugin bundled : bundledPlugins.values()) {
            PluginManifest manifest = bundled.entrypoint().manifest();
            // An explicitly installed external package with the same id takes precedence.
            if (pluginPackages.containsKey(manifest.pluginId())) continue;
            PluginStatus status = pluginManager.status(manifest.pluginId()).orElse(null);
            String state = status == null ? PluginState.DISABLED.name() : status.state().name();
            String trust = status == null ? PluginTrustLevel.UNTRUSTED.name() : status.trustLevel().name();
            String failure = pluginFailures.getOrDefault(manifest.pluginId(), status == null ? "" : status.failureSummary());
            result.add(new PluginInfo(manifest.pluginId(), manifest.displayName(), manifest.version(),
                    state, trust,
                    manifest.services().stream().map(Enum::name).sorted().toList(),
                    manifest.permissions().stream().map(Enum::name).sorted().toList(),
                    "BUNDLED", failure, bundled.fingerprint(), false));
        }
        for (PluginPackageDescriptor pkg : pluginPackages.values()) {
            PluginStatus status = pluginManager.status(pkg.manifest().pluginId()).orElse(null);
            String state = status == null ? PluginState.DISABLED.name() : status.state().name();
            String trust = status == null ? PluginTrustLevel.UNTRUSTED.name() : status.trustLevel().name();
            String failure = pluginFailures.getOrDefault(pkg.manifest().pluginId(), status == null ? "" : status.failureSummary());
            result.add(new PluginInfo(pkg.manifest().pluginId(), pkg.manifest().displayName(), pkg.manifest().version(),
                    state, trust,
                    pkg.manifest().services().stream().map(Enum::name).sorted().toList(),
                    pkg.manifest().permissions().stream().map(Enum::name).sorted().toList(),
                    pkg.jar().toString(), failure, pkg.sha256(), true));
        }
        result.sort(Comparator.comparing(PluginInfo::displayName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    @Override
    public synchronized Result installPlugin(Path jarFile) {
        Path workspace = null;
        try {
            if (jarFile == null) throw new IllegalArgumentException("Файл плагіна не знайдено");
            Path safeSource = SafeFilePublication.requireSafeRegularFile(jarFile, "Plugin installation source");
            if (!safeSource.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new IllegalArgumentException("Плагін має бути JAR-файлом");
            }
            SafeFilePublication.createDirectoriesNoLinks(pluginDir, "Plugin directory");
            scanPluginPackages();
            workspace = SafeFilePublication.createPrivateWorkspace(pluginDir, ".plugin-install-", "Plugin installation workspace");
            Path staged = workspace.resolve("package.jar");
            try (InputStream in = Files.newInputStream(safeSource, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.copyFresh(in, staged, "Plugin staged package");
            }

            // Inspect only stable private bytes. The chooser path may change immediately after selection.
            PluginManifest manifest = PluginPackageInspector.readManifest(staged);
            pluginManager.inspect(manifest); // API compatibility and metadata validation only.
            String targetName = manifest.pluginId() + "-" + sanitizeFilePart(manifest.version()) + ".jar";
            Path target = pluginDir.resolve(targetName).toAbsolutePath().normalize();
            PluginPackageDescriptor existing = pluginPackages.get(manifest.pluginId());
            if (existing != null && pluginManager.status(manifest.pluginId())
                    .map(PluginStatus::state).orElse(PluginState.DISABLED) == PluginState.ENABLED) {
                throw new IllegalStateException("Спочатку вимкніть поточну версію плагіна " + manifest.displayName());
            }

            String expectedTargetHash = null;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (existing == null || !existing.jar().equals(target)) {
                    throw new IOException("Цільовий JAR плагіна вже існує, але не належить відомому пакету: " + target);
                }
                expectedTargetHash = existing.sha256();
            }
            SafeFilePublication.publishReplacingIfUnchanged(
                    staged, target, expectedTargetHash, "Plugin package installation");
            String publishedHash = SafeFilePublication.sha256(target, "Installed plugin package");

            if (existing != null && !existing.jar().equals(target)) {
                try {
                    SafeFilePublication.removeIfOwned(existing.jar(), existing.sha256(), "Previous plugin package cleanup");
                } catch (IOException cleanupFailure) {
                    try {
                        SafeFilePublication.removeIfOwned(target, publishedHash, "Failed plugin installation rollback");
                    } catch (IOException rollbackFailure) {
                        cleanupFailure.addSuppressed(rollbackFailure);
                    }
                    throw cleanupFailure;
                }
            }
            clearPluginApprovals(manifest.pluginId()); // New package bytes require fresh explicit approval.
            pluginManager.resetApproval(manifest);
            scanPluginPackages();
            return Result.ok("ui.integration.plugin.install_success");
        } catch (Exception e) {
            return Result.failTechnical("ui.integration.plugin.install_failed", safeMessage(e));
        } finally {
            if (workspace != null) {
                try { SafeFilePublication.cleanupPrivateWorkspace(workspace); }
                catch (IOException ignored) { /* preserve unexpected replacement as evidence */ }
            }
        }
    }

    @Override
    public synchronized Result enablePlugin(String pluginId, boolean approveRequestedPermissions) {
        if (!approveRequestedPermissions) {
            return Result.fail("ui.integration.plugin.approval_required");
        }
        try {
            if (!pluginPackages.containsKey(pluginId) && bundledPlugins.containsKey(pluginId)) {
                return enableBundledPlugin(pluginId);
            }
            PluginPackageDescriptor pkg = requirePluginPackage(pluginId);
            disablePluginInternal(pluginId);
            PluginRuntimePackageStager.RuntimePackage runtimePackage;
            try {
                runtimePackage = pluginRuntimePackageStager.stageVerified(pkg);
            } catch (PluginRuntimePackageStager.PackageChangedException changed) {
                clearPluginApprovals(pkg.manifest().pluginId());
                pluginManager.resetApproval(pkg.manifest());
                throw changed;
            }
            URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{runtimePackage.jar().toUri().toURL()}, getClass().getClassLoader());
            boolean success = false;
            try {
                List<PluginEntrypoint> entrypoints = ServiceLoader.load(PluginEntrypoint.class, classLoader).stream()
                        .map(ServiceLoader.Provider::get)
                        .toList();
                PluginEntrypoint entrypoint = entrypoints.stream()
                        .filter(value -> value.manifest().pluginId().equals(pluginId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("JAR не містить ServiceLoader entrypoint для " + pluginId));
                if (!pkg.manifest().equals(entrypoint.manifest())) {
                    throw new SecurityException("Маніфест усередині коду плагіна не збігається з попередньо перевіреним маніфестом пакета");
                }
                pluginManager.inspect(pkg.manifest());
                LoadedPlugin loaded = pluginManager.enable(entrypoint, PluginApproval.trusted(pkg.manifest()));
                registerRuntimeExtensions(loaded);
                settings.putBoolean(approvalKey(pkg), true);
                pluginClassLoaders.put(pluginId, classLoader);
                pluginRuntimeWorkspaces.put(pluginId, runtimePackage.workspace());
                pluginFailures.remove(pluginId);
                success = true;
                return Result.ok("ui.integration.plugin.enabled", pkg.manifest().displayName());
            } finally {
                if (!success) {
                    runtimeExtensions.removePlugin(pluginId);
                    pluginManager.disable(pluginId);
                    try { classLoader.close(); } catch (IOException ignored) { }
                    cleanupPluginRuntimeWorkspace(pluginId, runtimePackage.workspace());
                }
            }
        } catch (Exception | LinkageError e) {
            pluginFailures.put(pluginId == null ? "" : pluginId, safeMessage(e));
            return Result.failTechnical("ui.integration.plugin.enable_failed", safeMessage(e));
        }
    }

    @Override
    public synchronized Result disablePlugin(String pluginId) {
        try {
            PluginManifest manifest = requireAnyPluginManifest(pluginId);
            boolean changed = disablePluginInternal(pluginId);
            return changed
                    ? Result.ok("ui.integration.plugin.disabled", manifest.displayName())
                    : Result.ok("ui.integration.plugin.already_disabled");
        } catch (Exception e) {
            return Result.failTechnical("ui.integration.plugin.disable_failed", safeMessage(e));
        }
    }

    @Override
    public synchronized Result removePlugin(String pluginId) {
        try {
            if (!pluginPackages.containsKey(pluginId) && bundledPlugins.containsKey(pluginId)) {
                return Result.fail("ui.integration.plugin.bundled_remove_forbidden");
            }
            PluginPackageDescriptor pkg = requirePluginPackage(pluginId);
            disablePluginInternal(pluginId);
            clearPluginApprovals(pkg.manifest().pluginId());
            SafeFilePublication.removeIfOwned(pkg.jar(), pkg.sha256(), "Plugin package removal");
            pluginPackages.remove(pluginId);
            pluginFailures.remove(pluginId);
            return Result.ok("ui.integration.plugin.removed");
        } catch (Exception e) {
            return Result.failTechnical("ui.integration.plugin.remove_failed", safeMessage(e));
        }
    }

    // ==================== AI ====================

    @Override
    public AiConfiguration aiConfiguration() {
        return aiIntegration.configuration();
    }

    @Override
    public synchronized List<AiProviderInfo> aiProviders() {
        return aiIntegration.providers();
    }

    @Override
    public synchronized Result saveAiConfiguration(String endpoint, String model, int maxOutputTokens, String apiKey) {
        return aiIntegration.saveConfiguration(endpoint, model, maxOutputTokens, apiKey);
    }

    @Override
    public synchronized Result clearAiApiKey() {
        return aiIntegration.clearApiKey();
    }

    @Override
    public synchronized Result saveAiProviderSecret(String providerId, String secretName, String value) {
        return aiIntegration.saveProviderSecret(providerId, secretName, value);
    }

    @Override
    public synchronized Result deleteAiProviderSecret(String providerId, String secretName) {
        return aiIntegration.deleteProviderSecret(providerId, secretName);
    }

    @Override
    public AiExecutionResult executeAi(String providerId, String bookId, AiOperation operation, String prompt,
                                       String bookContent, boolean allowNetwork, boolean allowBookContent) {
        return aiIntegration.execute(providerId, bookId, operation, prompt, bookContent, allowNetwork, allowBookContent);
    }

    private void synchronizeCalibreRuntimeProperties() {
        String executable = settings.get("converter.calibre.executable", "").trim();
        String timeout = settings.get("converter.calibre.timeoutSeconds", "1800").trim();
        if (executable.isBlank()) System.clearProperty("myhomelib.calibre.executable");
        else System.setProperty("myhomelib.calibre.executable", executable);
        System.setProperty("myhomelib.calibre.timeoutSeconds", timeout.isBlank() ? "1800" : timeout);
    }

    // ==================== lifecycle/helpers ====================

    @PreDestroy
    public synchronized void close() {
        for (String pluginId : new ArrayList<>(pluginClassLoaders.keySet())) disablePluginInternal(pluginId);
        pendingSync.clear();
    }

    private WebDavSyncAdapter webDavAdapter() {
        SecretStore backend = secretStore().orElseThrow(() -> new IllegalStateException(
                "Захищене сховище облікових даних недоступне на цій системі"));
        String endpoint = settings.get(WEB_ENDPOINT, "").trim();
        if (endpoint.isBlank()) throw new IllegalStateException("WebDAV не налаштовано");
        URI uri = validateWebDavEndpoint(endpoint);
        WebDavCredentialStore credentials = new WebDavCredentialStore(backend, uri);
        if (credentials.load().isEmpty()) throw new IllegalStateException("Облікові дані WebDAV не збережено");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        return new WebDavSyncAdapter(uri,
                new JdkWebDavHttpClient(client, Duration.ofSeconds(60), SyncBundleJsonCodec.MAX_BUNDLE_BYTES + 1024 * 1024),
                new SyncBundleJsonCodec(mapper()),
                () -> credentials.load().orElseThrow(() -> new IllegalStateException("Облікові дані WebDAV недоступні")),
                2, Duration.ofMillis(350));
    }

    private Snapshot exportSnapshot() throws IOException {
        Path workspace = SafeFilePublication.createPrivateWorkspace(
                AppPaths.cacheDir(), ".mhl-webdav-export-", "WebDAV snapshot export workspace");
        Path temp = workspace.resolve("user-data.json");
        try {
            portableUserData().exportTo(temp);
            SafeFilePublication.requireSafeRegularFile(temp, "WebDAV exported user-data snapshot");
            byte[] bytes = readBounded(temp, WebDavSnapshotCodec.MAX_SNAPSHOT_BYTES,
                    "Користувацькі дані перевищують безпечний ліміт синхронізації 64 МБ");
            JsonNode root = mapper().readTree(bytes);
            if (!(root instanceof ObjectNode object)) throw new IOException("Експорт користувацьких даних має некоректний формат");
            object.remove("exportedAt"); // Timestamp must not create a false local change on every sync.
            byte[] canonical = mapper().writeValueAsBytes(object);
            return new Snapshot(canonical, sha256(canonical));
        } finally {
            cleanupWorkspace(workspace, "WebDAV snapshot export workspace");
        }
    }

    private Snapshot restoreRemoteSnapshot(Snapshot remote) throws IOException {
        Snapshot before = exportSnapshot();
        Path workspace = SafeFilePublication.createPrivateWorkspace(
                AppPaths.cacheDir(), ".mhl-webdav-restore-", "WebDAV snapshot restore workspace");
        Path remoteFile = workspace.resolve("remote-user-data.json");
        Path rollbackFile = workspace.resolve("local-rollback.json");
        try {
            SafeFilePublication.copyFresh(new ByteArrayInputStream(remote.bytes()), remoteFile,
                    "WebDAV remote snapshot");
            SafeFilePublication.copyFresh(new ByteArrayInputStream(before.bytes()), rollbackFile,
                    "WebDAV rollback snapshot");
            try {
                portableUserData().restoreFrom(remoteFile);
            } catch (Exception failure) {
                try {
                    portableUserData().restoreFrom(rollbackFile);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                if (failure instanceof IOException io) throw io;
                throw new IOException("Не вдалося застосувати віддалені користувацькі дані", failure);
            }
            return exportSnapshot();
        } finally {
            cleanupWorkspace(workspace, "WebDAV snapshot restore workspace");
        }
    }

    private LibraryOperationCoordinator.Lease acquireWebDavSyncLease() {
        return operationCoordinator == null
                ? null
                : operationCoordinator.acquireDetachedShared(LibraryOperationType.SYNC);
    }

    private String activeCollectionIdForSync() {
        if (collectionLifecycle == null) return "";
        var active = collectionLifecycle.getCurrentCollection();
        if (active == null || active.getId() == null || active.getId().isBlank()) {
            throw new IllegalStateException("Активну колекцію для WebDAV-синхронізації не вибрано");
        }
        return active.getId();
    }

    private boolean sameActiveCollection(String expectedCollectionId) {
        if (collectionLifecycle == null || expectedCollectionId == null || expectedCollectionId.isBlank()) return true;
        var active = collectionLifecycle.getCurrentCollection();
        return active != null && expectedCollectionId.equals(active.getId());
    }

    private void cleanupWorkspace(Path workspace, String role) throws IOException {
        try {
            SafeFilePublication.cleanupPrivateWorkspace(workspace);
        } catch (IOException failure) {
            throw new IOException("Не вдалося безпечно очистити " + role, failure);
        }
    }

    private long pushSnapshot(WebDavSyncAdapter adapter, Snapshot snapshot, String deviceId) throws IOException {
        long sequence = Math.max(0L, parseLong(settings.get(WEB_SEQUENCE, "0"), 0L)) + 1L;
        ChangeSet set = WebDavSnapshotCodec.encode(snapshot.bytes(), snapshot.hash(), sequence, deviceId, Instant.now());
        adapter.push(set);
        settings.put(WEB_SEQUENCE, Long.toString(sequence));
        return sequence;
    }

    private boolean isSnapshotChangeSet(ChangeSet set) {
        return WebDavSnapshotCodec.supports(set);
    }

    private Snapshot decodeSnapshot(ChangeSet set) throws IOException {
        WebDavSnapshotCodec.DecodedSnapshot decoded = WebDavSnapshotCodec.decode(set);
        return new Snapshot(decoded.bytes(), decoded.hash());
    }

    private List<RemoteSnapshot> latestExternalSnapshots(List<ChangeSet> pulled, String localDeviceId) throws IOException {
        Map<String, ChangeSet> latest = new LinkedHashMap<>();
        for (ChangeSet set : pulled) {
            if (set == null || set.sourceDeviceId().equals(localDeviceId) || !isSnapshotChangeSet(set)) continue;
            ChangeSet current = latest.get(set.sourceDeviceId());
            if (current == null || set.sequence() > current.sequence()) latest.put(set.sourceDeviceId(), set);
        }
        List<RemoteSnapshot> result = new ArrayList<>();
        for (ChangeSet set : latest.values()) result.add(new RemoteSnapshot(set, decodeSnapshot(set)));
        return List.copyOf(result);
    }

    private void scanPluginPackages() throws IOException {
        SafeFilePublication.createDirectoriesNoLinks(pluginDir, "Plugin directory");
        Map<String, PluginPackageDescriptor> found = new LinkedHashMap<>();
        try (var stream = Files.list(pluginDir)) {
            for (Path jar : stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList()) {
                try {
                    PluginPackageDescriptor pkg = PluginPackageInspector.inspect(jar);
                    PluginManifest manifest = pkg.manifest();
                    PluginPackageDescriptor previous = found.putIfAbsent(manifest.pluginId(), pkg);
                    if (previous != null) {
                        pluginFailures.put(manifest.pluginId(), "Знайдено кілька JAR з однаковим pluginId; використовується " + previous.jar().getFileName());
                        continue;
                    }
                    PluginPackageDescriptor known = pluginPackages.get(manifest.pluginId());
                    if (known != null && !known.sha256().equals(pkg.sha256())) {
                        disablePluginInternal(manifest.pluginId());
                        clearPluginApprovals(manifest.pluginId());
                        pluginManager.resetApproval(manifest);
                        pluginFailures.put(manifest.pluginId(),
                                "Файл плагіна змінився (новий SHA-256). Плагін вимкнено; перевірте файл і схваліть його повторно.");
                    } else {
                        pluginManager.inspect(manifest);
                    }
                } catch (Exception e) {
                    pluginFailures.put("jar:" + jar.getFileName(), safeMessage(e));
                }
            }
        }
        pluginPackages.keySet().removeIf(id -> !found.containsKey(id) && !pluginClassLoaders.containsKey(id));
        found.forEach(pluginPackages::put);
    }

    private void autoEnableApprovedPlugins() {
        for (BundledPlugin bundled : new ArrayList<>(bundledPlugins.values())) {
            PluginManifest manifest = bundled.entrypoint().manifest();
            if (pluginPackages.containsKey(manifest.pluginId())) continue;
            if (!settings.getBoolean(approvalKey(manifest, bundled.fingerprint()), false)) continue;
            Result result = enablePlugin(manifest.pluginId(), true);
            if (!result.success()) pluginFailures.put(manifest.pluginId(), technicalResultDetail(result));
        }
        for (PluginPackageDescriptor pkg : new ArrayList<>(pluginPackages.values())) {
            if (!settings.getBoolean(approvalKey(pkg), false)) continue;
            Result result = enablePlugin(pkg.manifest().pluginId(), true);
            if (!result.success()) pluginFailures.put(pkg.manifest().pluginId(), technicalResultDetail(result));
        }
    }

    private static String technicalResultDetail(Result result) {
        if (result == null || result.userMessage() == null) return "integration operation failed";
        String technical = result.userMessage().technicalDetail();
        return technical == null || technical.isBlank() ? result.userMessage().key() : technical;
    }

    private void scanBundledPlugins() {
        bundledPlugins.clear();
        ServiceLoader.load(PluginEntrypoint.class, getClass().getClassLoader()).stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparing(entry -> entry.manifest().pluginId()))
                .forEach(entry -> {
                    PluginManifest manifest = Objects.requireNonNull(entry.manifest(), "plugin manifest");
                    pluginManager.inspect(manifest);
                    bundledPlugins.putIfAbsent(manifest.pluginId(),
                            new BundledPlugin(entry, bundledFingerprint(manifest)));
                });
    }

    private Result enableBundledPlugin(String pluginId) {
        BundledPlugin bundled = bundledPlugins.get(pluginId);
        if (bundled == null) return Result.fail("ui.integration.plugin.bundled_not_found", pluginId);
        PluginManifest manifest = bundled.entrypoint().manifest();
        try {
            disablePluginInternal(pluginId);
            pluginManager.inspect(manifest);
            LoadedPlugin loaded = pluginManager.enable(bundled.entrypoint(), PluginApproval.trusted(manifest));
            try {
                registerRuntimeExtensions(loaded);
            } catch (Exception | LinkageError registrationFailure) {
                runtimeExtensions.removePlugin(pluginId);
                pluginManager.disable(pluginId);
                throw registrationFailure;
            }
            settings.putBoolean(approvalKey(manifest, bundled.fingerprint()), true);
            pluginFailures.remove(pluginId);
            return Result.ok("ui.integration.plugin.enabled", manifest.displayName());
        } catch (Exception | LinkageError e) {
            pluginFailures.put(pluginId, safeMessage(e));
            return Result.failTechnical("ui.integration.plugin.enable_failed", safeMessage(e));
        }
    }

    private PluginManifest requireAnyPluginManifest(String pluginId) {
        PluginPackageDescriptor external = pluginPackages.get(pluginId);
        if (external != null) return external.manifest();
        BundledPlugin bundled = bundledPlugins.get(pluginId);
        if (bundled != null) return bundled.entrypoint().manifest();
        throw new IllegalArgumentException("Плагін не встановлено: " + pluginId);
    }

    private static String bundledFingerprint(PluginManifest manifest) {
        String material = manifest.pluginId() + "\n" + manifest.version() + "\n"
                + manifest.apiRange() + "\n" + manifest.services() + "\n" + manifest.permissions();
        return sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    private boolean disablePluginInternal(String pluginId) {
        boolean changed = runtimeExtensions.removePlugin(pluginId);
        changed = pluginManager.disable(pluginId) || changed;
        URLClassLoader loader = pluginClassLoaders.remove(pluginId);
        if (loader != null) {
            try { loader.close(); } catch (IOException ignored) { }
            changed = true;
        }
        Path runtimeWorkspace = pluginRuntimeWorkspaces.remove(pluginId);
        if (runtimeWorkspace != null) {
            cleanupPluginRuntimeWorkspace(pluginId, runtimeWorkspace);
            changed = true;
        }
        return changed;
    }

    private void registerRuntimeExtensions(LoadedPlugin loaded) {
        String pluginId = loaded.manifest().pluginId();
        runtimeExtensions.replacePlugin(pluginId, new RuntimeExtensionRegistry.ExtensionBundle(
                managedServiceList(loaded, PluginService.METADATA_PROVIDER, com.myhomelibcorp.application.metadata.MetadataProvider.class),
                managedServiceList(loaded, PluginService.DICTIONARY_PROVIDER, com.myhomelibcorp.application.dictionary.DictionaryProvider.class),
                managedServiceList(loaded, PluginService.TRANSLATION_PROVIDER, com.myhomelibcorp.application.translation.TranslationProvider.class),
                managedServiceList(loaded, PluginService.CONTENT_EXTRACTOR, com.myhomelibcorp.application.port.out.content.ContentExtractor.class),
                managedServiceList(loaded, PluginService.COVER_PROVIDER, com.myhomelibcorp.application.port.out.cover.CoverExtractor.class),
                managedServiceList(loaded, PluginService.BOOK_IMPORTER, com.myhomelibcorp.application.port.out.importer.BookImporterPort.class),
                managedBookConverters(loaded),
                managedServiceList(loaded, PluginService.AI_PROVIDER, com.myhomelibcorp.application.ai.AiProvider.class),
                managedDeviceProfileDetectors(loaded),
                managedLocalMetadataExtractors(loaded)
        ));
    }




    private List<com.myhomelibcorp.application.extension.LocalMetadataExtractor> managedLocalMetadataExtractors(LoadedPlugin loaded) {
        List<com.myhomelibcorp.plugin.api.MetadataExtractor> providers = managedServiceList(
                loaded, PluginService.METADATA_EXTRACTOR, com.myhomelibcorp.plugin.api.MetadataExtractor.class);
        if (providers.isEmpty()) return List.of();
        List<com.myhomelibcorp.application.extension.LocalMetadataExtractor> result = new ArrayList<>(providers.size());
        for (com.myhomelibcorp.plugin.api.MetadataExtractor provider : providers) {
            result.add(new com.myhomelibcorp.application.extension.LocalMetadataExtractor() {
                @Override public String id() { return provider.id(); }
                @Override public boolean supports(Path source) { return provider.supports(source); }
                @Override public Map<String, String> extract(Path source) throws IOException {
                    return provider.extract(source, new com.myhomelibcorp.plugin.api.PluginOperationContext() {
                        @Override public boolean isCancelled() { return false; }
                        @Override public void reportProgress(long completed, long total) { }
                    });
                }
            });
        }
        return List.copyOf(result);
    }

    private List<com.myhomelibcorp.application.port.out.exporter.BookConverter> managedBookConverters(LoadedPlugin loaded) {
        List<com.myhomelibcorp.application.port.out.exporter.BookConverter> result = new ArrayList<>(
                managedServiceList(loaded, PluginService.BOOK_CONVERTER,
                        com.myhomelibcorp.application.port.out.exporter.BookConverter.class));
        List<com.myhomelibcorp.plugin.api.ExportProvider> exportProviders = managedServiceList(
                loaded, PluginService.EXPORT_PROVIDER, com.myhomelibcorp.plugin.api.ExportProvider.class);
        if (exportProviders.isEmpty()) return List.copyOf(result);

        String pluginId = loaded.manifest().pluginId();
        for (com.myhomelibcorp.plugin.api.ExportProvider provider : exportProviders) {
            String providerId;
            try { providerId = safeId(provider.id(), "export"); }
            catch (RuntimeException failure) { continue; }
            for (ExportRequest.ExportFormat format : ExportRequest.ExportFormat.values()) {
                String target = format.name().toLowerCase(Locale.ROOT);
                boolean supported;
                try { supported = provider.supports(target) || provider.supports(format.name()); }
                catch (RuntimeException failure) { supported = false; }
                if (!supported) continue;
                result.add(exportProviderConverter(pluginId, providerId, provider, target));
            }
        }
        return List.copyOf(result);
    }

    private com.myhomelibcorp.application.port.out.exporter.BookConverter exportProviderConverter(
            String pluginId, String providerId, com.myhomelibcorp.plugin.api.ExportProvider provider, String targetFormat) {
        String extension = switch (targetFormat) {
            case "fb2_zip" -> ".fb2.zip";
            default -> "." + targetFormat.replace('_', '.');
        };
        return new com.myhomelibcorp.application.port.out.exporter.BookConverter() {
            @Override public String id() { return "plugin-export:" + pluginId + ":" + providerId + ":" + targetFormat; }
            @Override public boolean isAvailable() { return true; }
            @Override public boolean supports(com.myhomelibcorp.domain.model.book.Book book) { return true; }
            @Override public boolean supports(com.myhomelibcorp.domain.model.book.Book book, String sourceFormat) { return true; }
            @Override public String getTargetExtension() { return extension; }
            @Override public String getFormatName() { return targetFormat; }
            @Override public Set<com.myhomelibcorp.application.conversion.BookConversionCapability> capabilities() {
                return Set.of(com.myhomelibcorp.application.conversion.BookConversionCapability.anySource(targetFormat, extension));
            }
            @Override
            public void convert(com.myhomelibcorp.application.conversion.BookConversionContext context) throws Exception {
                context.checkCancelled();
                Path target = SafeFilePublication.prepareTarget(context.targetFile(), "Plugin export target");
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Plugin export target must be absent before conversion: " + target);
                }
                String safeSourceFormat = SafeFileNameSupport.sanitizeComponent(
                        context.sourceFormat() == null ? "" : context.sourceFormat().replace('_', '.'), 32);
                String sourceExtension = safeSourceFormat.isBlank() ? ".book" : "." + safeSourceFormat;
                Path workspace = SafeFilePublication.createPrivateWorkspace(
                        target.getParent(), ".mhl-plugin-export-", "Plugin export workspace");
                try {
                    Path source = workspace.resolve("source" + sourceExtension);
                    SafeFilePublication.copyFresh(context.sourceStream(), source, "Plugin export source");
                    Path pluginTarget = workspace.resolve("output" + extension);
                    com.myhomelibcorp.plugin.api.PluginOperationContext pluginContext = new com.myhomelibcorp.plugin.api.PluginOperationContext() {
                        @Override public boolean isCancelled() { return context.cancelled().getAsBoolean(); }
                        @Override public void reportProgress(long completed, long total) { /* host operation owns visible progress */ }
                    };
                    Path produced = provider.export(source, pluginTarget, targetFormat, pluginContext);
                    context.checkCancelled();
                    Path candidate = produced == null ? pluginTarget : produced.toAbsolutePath().normalize();
                    Path safeCandidate = SafeFilePublication.requireSafeRegularFile(candidate, "Plugin export result");
                    Path safeWorkspace = workspace.toAbsolutePath().normalize();
                    if (!safeCandidate.startsWith(safeWorkspace)) {
                        throw new IOException("Плагін експорту повернув файл поза приватним workspace: " + safeCandidate);
                    }
                    long size = Files.size(safeCandidate);
                    if (size <= 0 || size > context.maxOutputBytes()) {
                        throw new IOException("Плагін експорту створив файл неприпустимого розміру: " + size + " bytes");
                    }
                    String candidateHashBefore = SafeFilePublication.sha256(safeCandidate, "Plugin export result snapshot");
                    try (InputStream in = Files.newInputStream(safeCandidate, LinkOption.NOFOLLOW_LINKS)) {
                        SafeFilePublication.copyFresh(in, target, "Plugin export publication");
                    }
                    String candidateHashAfter = SafeFilePublication.sha256(safeCandidate, "Plugin export result after publication");
                    String publishedHash = SafeFilePublication.sha256(target, "Plugin export published target");
                    if (!candidateHashBefore.equalsIgnoreCase(candidateHashAfter)
                            || !candidateHashBefore.equalsIgnoreCase(publishedHash)) {
                        try { SafeFilePublication.removeIfOwned(target, publishedHash, "Plugin export inconsistent publication cleanup"); }
                        catch (IOException cleanupFailure) { /* preserve changed target as evidence */ }
                        throw new IOException("Файл результату плагіна змінився під час publication");
                    }
                } finally {
                    try { SafeFilePublication.cleanupPrivateWorkspace(workspace); }
                    catch (IOException ignored) { /* preserve unexpected replacement as evidence */ }
                }
            }
            @Override
            public void convert(com.myhomelibcorp.domain.model.book.Book book, InputStream sourceStream, Path targetFile) throws Exception {
                var context = new com.myhomelibcorp.application.conversion.BookConversionContext(
                        book, "", targetFormat, sourceStream, targetFile, () -> false,
                        com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase.DEFAULT_MAX_OUTPUT_BYTES);
                convert(context);
            }
        };
    }

    private List<DeviceProfileDetector> managedDeviceProfileDetectors(LoadedPlugin loaded) {
        List<com.myhomelibcorp.plugin.api.DeviceProvider> providers = managedServiceList(
                loaded, PluginService.DEVICE_PROVIDER, com.myhomelibcorp.plugin.api.DeviceProvider.class);
        if (providers.isEmpty()) return List.of();
        String pluginId = loaded.manifest().pluginId();
        List<DeviceProfileDetector> result = new ArrayList<>(providers.size());
        for (com.myhomelibcorp.plugin.api.DeviceProvider provider : providers) {
            result.add(new DeviceProfileDetector() {
                @Override
                public String id() {
                    try { return "plugin:" + pluginId + ":" + provider.id(); }
                    catch (RuntimeException failure) { return "plugin:" + pluginId; }
                }

                @Override
                public Optional<DeviceTargetProfile> detect(Path mountRoot) {
                    if (mountRoot == null) return Optional.empty();
                    Path root = mountRoot.toAbsolutePath().normalize();
                    Optional<com.myhomelibcorp.plugin.api.DeviceProfile> detected = provider.detect(root);
                    if (detected == null || detected.isEmpty()) return Optional.empty();
                    return toApplicationDeviceProfile(pluginId, root, detected.get());
                }
            });
        }
        return List.copyOf(result);
    }

    private static Optional<DeviceTargetProfile> toApplicationDeviceProfile(
            String pluginId, Path mountRoot, com.myhomelibcorp.plugin.api.DeviceProfile profile) {
        Path destination = profile.destinationRoot();
        if (!destination.isAbsolute()) destination = mountRoot.resolve(destination);
        destination = destination.toAbsolutePath().normalize();
        if (!destination.startsWith(mountRoot)) {
            throw new IllegalArgumentException("Плагін пристрою повернув папку за межами вибраного носія");
        }
        List<ExportRequest.ExportFormat> formats = profile.preferredFormats().stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_'))
                .map(value -> {
                    try { return ExportRequest.ExportFormat.valueOf(value); }
                    catch (IllegalArgumentException invalid) { return null; }
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (formats.isEmpty()) return Optional.empty();
        String relative = mountRoot.relativize(destination).toString().replace('\\', '/');
        String stableId = "plugin-" + sha256((pluginId + "\n" + profile.deviceId()).getBytes(StandardCharsets.UTF_8)).substring(0, 24);
        return Optional.of(new DeviceTargetProfile(stableId, profile.displayName(), formats, relative, true));
    }

    /**
     * Wrap plugin services so every runtime call crosses PluginManager's fault-containment gate.
     * A failing plugin is quarantined and detached from the live registry before the error reaches the caller.
     */
    private <T> List<T> managedServiceList(LoadedPlugin loaded, PluginService service, Class<T> applicationType) {
        Object raw = loaded.services().get(service);
        if (raw == null) return List.of();
        Class<?> contract = service.contractType();
        if (!applicationType.isAssignableFrom(contract) || !contract.isInstance(raw)) {
            throw new IllegalStateException("Несумісний контракт плагіна " + loaded.manifest().pluginId() + ": " + service);
        }
        String pluginId = loaded.manifest().pluginId();
        Object proxy = Proxy.newProxyInstance(
                contract.getClassLoader() == null ? getClass().getClassLoader() : contract.getClassLoader(),
                new Class<?>[]{contract},
                (instance, method, args) -> invokeManagedPluginService(pluginId, service, contract, instance, method, args));
        return List.of(applicationType.cast(proxy));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object invokeManagedPluginService(String pluginId, PluginService service, Class<?> contract,
                                               Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "ManagedPluginService[" + pluginId + "/" + service + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                default -> throw new IllegalStateException("Unsupported Object method on managed plugin proxy: " + method.getName());
            };
        }

        PluginInvocationResult<Object> result = pluginManager.invoke(pluginId, service, (Class) contract, delegate -> {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof Exception exception) throw exception;
                if (cause instanceof VirtualMachineError fatal) throw fatal;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("Помилка виконання плагіна", cause);
            }
        });
        if (result.outcome() == PluginInvocationResult.Outcome.SUCCESS) return result.value();
        if (result.outcome() == PluginInvocationResult.Outcome.OPERATION_ERROR) {
            Throwable failure = result.cause();
            if (failure != null) throw failure;
            throw new IllegalStateException("Плагін «" + pluginId + "»: " + result.message());
        }
        detachUnavailablePlugin(pluginId, result.message(), result.outcome() == PluginInvocationResult.Outcome.FAILED);
        throw new IllegalStateException("Плагін «" + pluginId + "»: " + result.message());
    }

    private synchronized void detachUnavailablePlugin(String pluginId, String message, boolean quarantined) {
        runtimeExtensions.removePlugin(pluginId);
        URLClassLoader loader = pluginClassLoaders.remove(pluginId);
        if (loader != null) {
            try { loader.close(); } catch (IOException ignored) { }
        }
        Path runtimeWorkspace = pluginRuntimeWorkspaces.remove(pluginId);
        if (runtimeWorkspace != null) cleanupPluginRuntimeWorkspace(pluginId, runtimeWorkspace);
        if (quarantined) pluginFailures.put(pluginId, message == null ? "Помилка виконання плагіна" : message);
    }

    private void cleanupPluginRuntimeWorkspace(String pluginId, Path workspace) {
        try {
            pluginRuntimePackageStager.cleanup(workspace);
        } catch (IOException cleanupFailure) {
            pluginFailures.put(pluginId == null ? "" : pluginId,
                    "Не вдалося очистити runtime-копію плагіна: " + safeMessage(cleanupFailure));
        }
    }

    private PluginPackageDescriptor requirePluginPackage(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) throw new IllegalArgumentException("Не вибрано плагін");
        PluginPackageDescriptor pkg = pluginPackages.get(pluginId);
        if (pkg == null) throw new IllegalArgumentException("Плагін не встановлено: " + pluginId);
        return pkg;
    }

    private PortableUserDataService portableUserData() {
        return Objects.requireNonNull(portableUserDataSupplier.get(), "portableUserData");
    }

    private ObjectMapper mapper() {
        return Objects.requireNonNull(mapperSupplier.get(), "mapper");
    }

    private PortableSnapshotConflictService snapshotConflicts() {
        PortableSnapshotConflictService current = resolvedSnapshotConflicts;
        if (current != null) return current;
        synchronized (this) {
            current = resolvedSnapshotConflicts;
            if (current == null) {
                current = new PortableSnapshotConflictService(mapper());
                resolvedSnapshotConflicts = current;
            }
            return current;
        }
    }

    private Optional<SecretStore> secretStore() {
        Optional<SecretStore> current = resolvedSecretStore;
        if (current != null) return current;
        synchronized (this) {
            current = resolvedSecretStore;
            if (current == null) {
                current = openSecretStore();
                resolvedSecretStore = current;
            }
            return current;
        }
    }

    private Optional<SecretStore> openSecretStore() {
        SecretStoreContext context = new SecretStoreContext(AppPaths.configDir(), AppPaths.portableMode(),
                System.getProperty("os.name", ""), System.getProperty("user.name", "unknown"));
        return ServiceLoader.load(SecretStoreProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(SecretStoreProvider::priority).reversed())
                .map(provider -> {
                    try { return provider.open(context); }
                    catch (RuntimeException failure) {
                        log.warn("Secret-store provider {} failed to initialize: {}",
                                provider.getClass().getName(), failure.toString());
                        return Optional.<SecretStore>empty();
                    }
                })
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static String approvalKey(PluginPackageDescriptor pkg) {
        return approvalKey(pkg.manifest(), pkg.sha256());
    }

    private static String approvalKey(PluginManifest manifest, String fingerprint) {
        return PLUGIN_APPROVAL_PREFIX + manifest.pluginId() + "."
                + sanitizeFilePart(manifest.version()) + "." + fingerprint;
    }

    private void clearPluginApprovals(String pluginId) {
        String prefix = PLUGIN_APPROVAL_PREFIX + pluginId + ".";
        settings.findByPrefix(prefix).keySet().forEach(settings::remove);
    }

    private record Snapshot(byte[] bytes, String hash) {
        private Snapshot {
            bytes = bytes == null ? new byte[0] : bytes.clone();
            hash = hash == null ? "" : hash;
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private record RemoteSnapshot(ChangeSet changeSet, Snapshot snapshot) { }
    private record PendingSync(String collectionId, Snapshot local, RemoteSnapshot remote, Map<String, Long> nextCursors) { }
    private record BundledPlugin(PluginEntrypoint entrypoint, String fingerprint) { }
}
