package com.myhomelibcorp.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myhomelibcorp.application.ai.AiConsent;
import com.myhomelibcorp.application.ai.AiExtensionService;
import com.myhomelibcorp.application.ai.AiOperation;
import com.myhomelibcorp.application.ai.AiProvider;
import com.myhomelibcorp.application.ai.AiProviderException;
import com.myhomelibcorp.application.ai.AiRequest;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.extension.DeviceProfileDetector;
import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.DeviceTargetProfile;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.service.PortableUserDataService;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewItem;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewSelection;
import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import com.myhomelibcorp.infrastructure.ai.OpenAiResponsesProvider;
import com.myhomelibcorp.infrastructure.sync.folder.SyncBundleJsonCodec;
import com.myhomelibcorp.infrastructure.sync.webdav.JdkWebDavHttpClient;
import com.myhomelibcorp.infrastructure.sync.webdav.WebDavCredentialStore;
import com.myhomelibcorp.infrastructure.sync.webdav.WebDavSyncAdapter;
import com.myhomelibcorp.infrastructure.sync.webdav.WebDavSyncSecrets;
import com.myhomelibcorp.plugin.api.PluginApiRange;
import com.myhomelibcorp.plugin.api.PluginApiVersion;
import com.myhomelibcorp.plugin.api.PluginApproval;
import com.myhomelibcorp.plugin.api.PluginEntrypoint;
import com.myhomelibcorp.plugin.api.PluginLoader;
import com.myhomelibcorp.plugin.api.LoadedPlugin;
import com.myhomelibcorp.plugin.api.PluginManager;
import com.myhomelibcorp.plugin.api.PluginManifest;
import com.myhomelibcorp.plugin.api.PluginInvocationResult;
import com.myhomelibcorp.plugin.api.PluginPermission;
import com.myhomelibcorp.plugin.api.PluginService;
import com.myhomelibcorp.plugin.api.PluginState;
import com.myhomelibcorp.plugin.api.PluginStatus;
import com.myhomelibcorp.plugin.api.PluginTrustLevel;
import com.myhomelibcorp.shared.security.SecretStore;
import com.myhomelibcorp.shared.security.SecretStoreContext;
import com.myhomelibcorp.shared.security.SecretStoreProvider;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.ui.integration.IntegrationBackend;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Production wiring for WebDAV user-data sync, plugin packages and optional AI providers. */
@Component
public final class DesktopIntegrationBackend implements IntegrationBackend {
    private static final String WEB_PREFIX = "sync.webdav.";
    private static final String WEB_ENDPOINT = WEB_PREFIX + "endpoint";
    private static final String WEB_LAST_LOCAL_HASH = WEB_PREFIX + "lastLocalHash";
    private static final String WEB_SEQUENCE = WEB_PREFIX + "sequence";
    private static final String WEB_CURSORS = WEB_PREFIX + "cursors";
    private static final String SYNC_DEVICE_ID = "sync.deviceId";
    private static final String PLUGIN_APPROVAL_PREFIX = "plugins.approved.";
    private static final String PLUGIN_MANIFEST = "META-INF/myhomelib-plugin.properties";
    private static final String SNAPSHOT_PREFIX = "portable-user-data:";
    private static final int SNAPSHOT_CHUNK_CHARS = 700_000;
    private static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    private static final int MAX_PLUGIN_MANIFEST_BYTES = 64 * 1024;
    private static final TypeReference<Map<String, Long>> CURSOR_MAP_TYPE = new TypeReference<>() { };

    private final ApplicationSettingsPort settings;
    private final PortableUserDataService portableUserData;
    private final RuntimeExtensionRegistry runtimeExtensions;
    private final ObjectMapper mapper;
    private final PortableSnapshotConflictService snapshotConflicts;
    private final List<AiProvider> builtInAiProviders;
    private final Optional<SecretStore> secretStore;
    private final PluginManager pluginManager = new PluginManager(new PluginLoader(Set.of()));
    private final Path pluginDir = AppPaths.dataDir().resolve("plugins");
    private final Map<String, PluginPackage> pluginPackages = new ConcurrentHashMap<>();
    private final Map<String, BundledPlugin> bundledPlugins = new ConcurrentHashMap<>();
    private final Map<String, URLClassLoader> pluginClassLoaders = new ConcurrentHashMap<>();
    private final Map<String, String> pluginFailures = new ConcurrentHashMap<>();
    private final Map<String, PendingSync> pendingSync = new ConcurrentHashMap<>();

    public DesktopIntegrationBackend(ApplicationSettingsPort settings,
                                     PortableUserDataService portableUserData,
                                     RuntimeExtensionRegistry runtimeExtensions,
                                     ObjectMapper mapper,
                                     List<AiProvider> builtInAiProviders) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.portableUserData = Objects.requireNonNull(portableUserData, "portableUserData");
        this.runtimeExtensions = Objects.requireNonNull(runtimeExtensions, "runtimeExtensions");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.snapshotConflicts = new PortableSnapshotConflictService(this.mapper);
        this.builtInAiProviders = builtInAiProviders == null ? List.of() : builtInAiProviders.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(AiProvider::id))
                .toList();
        this.secretStore = openSecretStore();
        synchronizeCalibreRuntimeProperties();
        try {
            Files.createDirectories(pluginDir);
            scanBundledPlugins();
            scanPluginPackages();
            autoEnableApprovedPlugins();
        } catch (Exception e) {
            pluginFailures.put("__plugin_directory__", safeMessage(e));
        }
        ensureDeviceId();
    }

    // ==================== WebDAV ====================

    @Override
    public WebDavConfiguration webDavConfiguration() {
        String endpoint = settings.get(WEB_ENDPOINT, "").trim();
        String deviceId = ensureDeviceId();
        if (endpoint.isBlank() || secretStore.isEmpty()) {
            return new WebDavConfiguration(endpoint, "", "", false, secretStore.isPresent(),
                    secretStore.map(SecretStore::backendId).orElse(""), deviceId);
        }
        try {
            URI uri = validateWebDavEndpoint(endpoint);
            Optional<WebDavSyncSecrets> loaded = new WebDavCredentialStore(secretStore.orElseThrow(), uri).load();
            return loaded.map(value -> new WebDavConfiguration(endpoint, value.username(), value.syncKeyBase64(),
                            true, true, secretStore.orElseThrow().backendId(), deviceId))
                    .orElseGet(() -> new WebDavConfiguration(endpoint, "", "", false, true,
                            secretStore.orElseThrow().backendId(), deviceId));
        } catch (Exception e) {
            return new WebDavConfiguration(endpoint, "", "", false, true,
                    secretStore.orElseThrow().backendId(), deviceId);
        }
    }

    @Override
    public synchronized Result saveWebDavConfiguration(String endpoint, String username, String password, String syncKey) {
        try {
            SecretStore secretsBackend = secretStore.orElseThrow(() -> new IllegalStateException(
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
            ensureDeviceId();
            if (!oldEndpoint.isBlank() && !sameEndpoint(oldEndpoint, uri.toString())) {
                try {
                    new WebDavCredentialStore(secretsBackend, validateWebDavEndpoint(oldEndpoint)).delete();
                } catch (Exception ignored) { }
                resetWebDavState();
            }
            return Result.ok("Налаштування WebDAV збережено. Ключ синхронізації потрібен на всіх пристроях, які мають читати ці дані.");
        } catch (Exception e) {
            return Result.fail(safeMessage(e));
        }
    }

    @Override
    public synchronized Result clearWebDavConfiguration() {
        String endpoint = settings.get(WEB_ENDPOINT, "").trim();
        try {
            if (!endpoint.isBlank() && secretStore.isPresent()) {
                new WebDavCredentialStore(secretStore.orElseThrow(), validateWebDavEndpoint(endpoint)).delete();
            }
        } catch (Exception ignored) { }
        settings.remove(WEB_ENDPOINT);
        resetWebDavState();
        pendingSync.clear();
        return Result.ok("Налаштування WebDAV очищено на цьому пристрої. Віддалені файли не видалялися.");
    }

    @Override
    public Result testWebDav() {
        try {
            WebDavSyncAdapter adapter = webDavAdapter();
            List<ChangeSet> bundles = adapter.pull(Map.of());
            long snapshots = bundles.stream().filter(this::isSnapshotChangeSet).count();
            return Result.ok("З'єднання успішне. Доступних зашифрованих пакетів синхронізації: " + snapshots + ".");
        } catch (Exception e) {
            return Result.fail("Не вдалося перевірити WebDAV: " + safeMessage(e));
        }
    }

    @Override
    public synchronized WebDavSyncAttempt synchronizeWebDav() {
        try {
            WebDavSyncAdapter adapter = webDavAdapter();
            String deviceId = ensureDeviceId();
            Snapshot local = exportSnapshot();
            String baseline = settings.get(WEB_LAST_LOCAL_HASH, "").trim();
            Map<String, Long> cursors = loadCursorMap();
            List<ChangeSet> pulled = adapter.pull(cursors);
            Map<String, Long> nextCursors = advanceCursors(cursors, pulled);

            List<RemoteSnapshot> external = latestExternalSnapshots(pulled, deviceId);
            if (external.isEmpty()) {
                if (baseline.isBlank()) {
                    long seq = pushSnapshot(adapter, local, deviceId);
                    nextCursors.put(deviceId, seq);
                    commitSyncState(local.hash(), nextCursors);
                    return new WebDavSyncAttempt(SyncState.COMPLETED,
                            "Першу зашифровану версію користувацьких даних завантажено на WebDAV.", "", List.of());
                }
                if (baseline.equals(local.hash())) {
                    saveCursorMap(nextCursors);
                    return new WebDavSyncAttempt(SyncState.NO_CHANGES, "Змін для синхронізації немає.", "", List.of());
                }
                long seq = pushSnapshot(adapter, local, deviceId);
                nextCursors.put(deviceId, seq);
                commitSyncState(local.hash(), nextCursors);
                return new WebDavSyncAttempt(SyncState.COMPLETED,
                        "Локальні зміни користувацьких даних завантажено на WebDAV.", "", List.of());
            }

            Set<String> changedRemoteHashes = new LinkedHashSet<>();
            for (RemoteSnapshot remote : external) {
                if (baseline.isBlank() || !baseline.equals(remote.snapshot().hash())) changedRemoteHashes.add(remote.snapshot().hash());
            }
            if (changedRemoteHashes.size() > 1) {
                return new WebDavSyncAttempt(SyncState.FAILED,
                        "Одночасно виявлено різні нові версії на кількох віддалених пристроях. "
                                + "Щоб не втратити дані, синхронізацію зупинено; спочатку синхронізуйте один із цих пристроїв повторно.",
                        "", List.of());
            }

            RemoteSnapshot remote = external.stream().max(Comparator.comparing(value -> value.changeSet().createdAt())).orElseThrow();
            if (local.hash().equals(remote.snapshot().hash())) {
                commitSyncState(local.hash(), nextCursors);
                return new WebDavSyncAttempt(SyncState.NO_CHANGES,
                        "Локальні та віддалені користувацькі дані вже однакові.", "", List.of());
            }

            boolean localChanged = baseline.isBlank() || !baseline.equals(local.hash());
            boolean remoteChanged = baseline.isBlank() || !baseline.equals(remote.snapshot().hash());
            if (!localChanged && remoteChanged) {
                Snapshot restored = restoreRemoteSnapshot(remote.snapshot());
                commitSyncState(restored.hash(), nextCursors);
                return new WebDavSyncAttempt(SyncState.COMPLETED,
                        "Віддалені користувацькі дані застосовано до локальної бібліотеки.", "", List.of());
            }
            if (localChanged && !remoteChanged) {
                long seq = pushSnapshot(adapter, local, deviceId);
                nextCursors.put(deviceId, seq);
                commitSyncState(local.hash(), nextCursors);
                return new WebDavSyncAttempt(SyncState.COMPLETED,
                        "Локальні зміни користувацьких даних завантажено на WebDAV.", "", List.of());
            }

            List<SyncConflictReviewItem> conflicts = snapshotConflicts.conflicts(local.bytes(), remote.snapshot().bytes());
            if (conflicts.isEmpty()) {
                return new WebDavSyncAttempt(SyncState.FAILED,
                        "Локальна та віддалена версії відрізняються, але конфліктні розділи не визначено. Дані не змінено.",
                        "", List.of());
            }
            String token = UUID.randomUUID().toString();
            pendingSync.clear();
            pendingSync.put(token, new PendingSync(local, remote, Map.copyOf(nextCursors)));
            return new WebDavSyncAttempt(SyncState.CONFLICT,
                    "Виявлено конфлікти у " + conflicts.size() + " розділ(ах). Виберіть версію для кожного розділу.",
                    token, conflicts);
        } catch (Exception e) {
            return new WebDavSyncAttempt(SyncState.FAILED, "Синхронізація WebDAV не виконана: " + safeMessage(e), "", List.of());
        }
    }

    @Override
    public synchronized Result resolveWebDavConflict(String conflictToken, List<SyncConflictReviewSelection> selections) {
        if (conflictToken == null || conflictToken.isBlank()) return Result.fail("Не вказано конфлікт синхронізації");
        PendingSync pending = pendingSync.remove(conflictToken);
        if (pending == null) return Result.fail("Конфлікт уже застарів. Запустіть синхронізацію повторно.");
        try {
            WebDavSyncAdapter adapter = webDavAdapter();
            String deviceId = ensureDeviceId();
            Snapshot currentLocal = exportSnapshot();
            if (!currentLocal.hash().equals(pending.local().hash())) {
                return Result.fail("Локальні дані змінилися після відкриття конфлікту. Запустіть синхронізацію повторно, щоб не втратити нові зміни.");
            }
            Map<String, Long> currentCursors = loadCursorMap();
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
                return Result.fail("Віддалена версія змінилася. Запустіть синхронізацію повторно перед розв'язанням конфлікту.");
            }
            List<RemoteSnapshot> latest = latestExternalSnapshots(adapter.pull(Map.of()), deviceId);
            boolean remoteStillCurrent = latest.stream().anyMatch(value ->
                    value.changeSet().changeSetId().equals(pending.remote().changeSet().changeSetId()));
            if (!remoteStillCurrent || latest.stream().anyMatch(value ->
                    value.changeSet().createdAt().isAfter(pending.remote().changeSet().createdAt())
                            && !value.snapshot().hash().equals(pending.remote().snapshot().hash()))) {
                return Result.fail("Після відкриття конфлікту на WebDAV з'явилася новіша версія. Запустіть синхронізацію повторно.");
            }

            byte[] mergedBytes = snapshotConflicts.merge(
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
            commitSyncState(resolved.hash(), nextCursors);
            return Result.ok("Конфлікт розв'язано по розділах. Об'єднану версію застосовано локально та завантажено на WebDAV.");
        } catch (Exception e) {
            return Result.fail("Не вдалося розв'язати конфлікт: " + safeMessage(e));
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
                    "Входить до складу MyHomeLib", failure, bundled.fingerprint(), false));
        }
        for (PluginPackage pkg : pluginPackages.values()) {
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
        try {
            if (jarFile == null || !Files.isRegularFile(jarFile)) throw new IllegalArgumentException("Файл плагіна не знайдено");
            if (!jarFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new IllegalArgumentException("Плагін має бути JAR-файлом");
            }
            PluginManifest manifest = readPluginManifest(jarFile);
            pluginManager.inspect(manifest); // API compatibility and metadata validation only.
            Files.createDirectories(pluginDir);
            String targetName = manifest.pluginId() + "-" + sanitizeFilePart(manifest.version()) + ".jar";
            Path target = pluginDir.resolve(targetName);
            PluginPackage existing = pluginPackages.get(manifest.pluginId());
            if (existing != null && pluginManager.status(manifest.pluginId())
                    .map(PluginStatus::state).orElse(PluginState.DISABLED) == PluginState.ENABLED) {
                throw new IllegalStateException("Спочатку вимкніть поточну версію плагіна " + manifest.displayName());
            }
            Path temp = Files.createTempFile(pluginDir, ".plugin-", ".jar.tmp");
            try {
                Files.copy(jarFile, temp, StandardCopyOption.REPLACE_EXISTING);
                // Verify copied bytes, not the source path that may change after chooser closes.
                PluginManifest copied = readPluginManifest(temp);
                if (!manifest.equals(copied)) throw new IllegalStateException("Маніфест плагіна змінився під час копіювання");
                moveReplacing(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
            if (existing != null && !existing.jar().equals(target)) Files.deleteIfExists(existing.jar());
            clearPluginApprovals(manifest.pluginId()); // New package bytes require fresh explicit approval.
            pluginManager.resetApproval(manifest);
            scanPluginPackages();
            return Result.ok("Плагін встановлено, але ще не активовано. Перевірте дозволи й натисніть «Увімкнути».");
        } catch (Exception e) {
            return Result.fail("Не вдалося встановити плагін: " + safeMessage(e));
        }
    }

    @Override
    public synchronized Result enablePlugin(String pluginId, boolean approveRequestedPermissions) {
        if (!approveRequestedPermissions) {
            return Result.fail("Для запуску плагіна потрібно явно підтвердити довіру до цієї версії та всі запитані дозволи.");
        }
        try {
            if (!pluginPackages.containsKey(pluginId) && bundledPlugins.containsKey(pluginId)) {
                return enableBundledPlugin(pluginId);
            }
            PluginPackage pkg = requirePluginPackage(pluginId);
            disablePluginInternal(pluginId);
            URLClassLoader classLoader = new URLClassLoader(new URL[]{pkg.jar().toUri().toURL()}, getClass().getClassLoader());
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
                try {
                    registerRuntimeExtensions(loaded);
                } catch (Exception | LinkageError registrationFailure) {
                    runtimeExtensions.removePlugin(pluginId);
                    pluginManager.disable(pluginId);
                    throw registrationFailure;
                }
                pluginClassLoaders.put(pluginId, classLoader);
                settings.putBoolean(approvalKey(pkg), true);
                pluginFailures.remove(pluginId);
                success = true;
                return Result.ok("Плагін «" + pkg.manifest().displayName() + "» увімкнено.");
            } finally {
                if (!success) classLoader.close();
            }
        } catch (Exception | LinkageError e) {
            pluginFailures.put(pluginId == null ? "" : pluginId, safeMessage(e));
            return Result.fail("Не вдалося увімкнути плагін: " + safeMessage(e));
        }
    }

    @Override
    public synchronized Result disablePlugin(String pluginId) {
        try {
            PluginManifest manifest = requireAnyPluginManifest(pluginId);
            boolean changed = disablePluginInternal(pluginId);
            return Result.ok(changed
                    ? "Плагін «" + manifest.displayName() + "» вимкнено."
                    : "Плагін уже вимкнений.");
        } catch (Exception e) {
            return Result.fail("Не вдалося вимкнути плагін: " + safeMessage(e));
        }
    }

    @Override
    public synchronized Result removePlugin(String pluginId) {
        try {
            if (!pluginPackages.containsKey(pluginId) && bundledPlugins.containsKey(pluginId)) {
                return Result.fail("Плагін входить до складу MyHomeLib і не видаляється окремо. Його можна вимкнути.");
            }
            PluginPackage pkg = requirePluginPackage(pluginId);
            disablePluginInternal(pluginId);
            clearPluginApprovals(pkg.manifest().pluginId());
            Files.deleteIfExists(pkg.jar());
            pluginPackages.remove(pluginId);
            pluginFailures.remove(pluginId);
            return Result.ok("Файл плагіна видалено. Дані бібліотеки не змінювалися.");
        } catch (Exception e) {
            return Result.fail("Не вдалося видалити плагін: " + safeMessage(e));
        }
    }

    // ==================== AI ====================

    @Override
    public AiConfiguration aiConfiguration() {
        boolean apiKey = false;
        if (secretStore.isPresent()) {
            try {
                apiKey = secretStore.orElseThrow().read("myhomelib.ai." + OpenAiResponsesProvider.ID + ".api-key").isPresent();
            } catch (Exception ignored) { }
        }
        return new AiConfiguration(
                settings.get(OpenAiResponsesProvider.ENDPOINT_KEY, OpenAiResponsesProvider.DEFAULT_ENDPOINT),
                settings.get(OpenAiResponsesProvider.MODEL_KEY, OpenAiResponsesProvider.DEFAULT_MODEL),
                Math.max(1, settings.getInt(OpenAiResponsesProvider.MAX_OUTPUT_TOKENS_KEY, 2048)),
                apiKey,
                secretStore.isPresent(),
                secretStore.map(SecretStore::backendId).orElse(""));
    }

    @Override
    public synchronized List<AiProviderInfo> aiProviders() {
        return currentAiProviders().stream()
                .map(provider -> new AiProviderInfo(provider.id(), provider.displayName(),
                        provider.capabilities().operations().stream().map(Enum::name).sorted().toList(),
                        provider.capabilities().networkRequired(),
                        provider.capabilities().requiredSecrets().stream().sorted().toList()))
                .sorted(Comparator.comparing(AiProviderInfo::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public synchronized Result saveAiConfiguration(String endpoint, String model, int maxOutputTokens, String apiKey) {
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
                return saveAiProviderSecret(OpenAiResponsesProvider.ID, "api-key", apiKey);
            }
            return Result.ok("Налаштування ШІ збережено.");
        } catch (Exception e) {
            return Result.fail("Не вдалося зберегти налаштування ШІ: " + safeMessage(e));
        }
    }

    @Override
    public synchronized Result clearAiApiKey() {
        return deleteAiProviderSecret(OpenAiResponsesProvider.ID, "api-key");
    }

    @Override
    public synchronized Result saveAiProviderSecret(String providerId, String secretName, String value) {
        try {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Секретне значення порожнє");
            AiProvider provider = requireAiProvider(providerId);
            AiExtensionService service = aiService();
            service.saveProviderSecret(provider.id(), secretName, value);
            return Result.ok("Секрет збережено у захищеному сховищі "
                    + secretStore.map(SecretStore::backendId).orElse("") + ".");
        } catch (Exception e) {
            return Result.fail("Не вдалося зберегти секрет провайдера: " + safeMessage(e));
        }
    }

    @Override
    public synchronized Result deleteAiProviderSecret(String providerId, String secretName) {
        try {
            AiProvider provider = requireAiProvider(providerId);
            aiService().deleteProviderSecret(provider.id(), secretName);
            return Result.ok("Секрет видалено із захищеного сховища.");
        } catch (Exception e) {
            return Result.fail("Не вдалося видалити секрет провайдера: " + safeMessage(e));
        }
    }

    @Override
    public AiExecutionResult executeAi(String providerId, String bookId, AiOperation operation, String prompt,
                                       String bookContent, boolean allowNetwork, boolean allowBookContent) {
        try {
            AiProvider provider = requireAiProvider(providerId);
            AiExtensionService service = aiService();
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

    private void synchronizeCalibreRuntimeProperties() {
        String executable = settings.get("converter.calibre.executable", "").trim();
        String timeout = settings.get("converter.calibre.timeoutSeconds", "300").trim();
        if (executable.isBlank()) System.clearProperty("myhomelib.calibre.executable");
        else System.setProperty("myhomelib.calibre.executable", executable);
        System.setProperty("myhomelib.calibre.timeoutSeconds", timeout.isBlank() ? "300" : timeout);
    }

    // ==================== lifecycle/helpers ====================

    @PreDestroy
    public synchronized void close() {
        for (String pluginId : new ArrayList<>(pluginClassLoaders.keySet())) disablePluginInternal(pluginId);
        pendingSync.clear();
    }

    private WebDavSyncAdapter webDavAdapter() {
        SecretStore backend = secretStore.orElseThrow(() -> new IllegalStateException(
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
                new SyncBundleJsonCodec(mapper),
                () -> credentials.load().orElseThrow(() -> new IllegalStateException("Облікові дані WebDAV недоступні")),
                2, Duration.ofMillis(350));
    }

    private Snapshot exportSnapshot() throws IOException {
        Files.createDirectories(AppPaths.cacheDir());
        Path temp = Files.createTempFile(AppPaths.cacheDir(), "user-data-sync-", ".json");
        try {
            portableUserData.exportTo(temp);
            byte[] bytes = Files.readAllBytes(temp);
            if (bytes.length > MAX_SNAPSHOT_BYTES) throw new IOException("Користувацькі дані перевищують безпечний ліміт синхронізації 64 МБ");
            JsonNode root = mapper.readTree(bytes);
            if (!(root instanceof ObjectNode object)) throw new IOException("Експорт користувацьких даних має некоректний формат");
            object.remove("exportedAt"); // Timestamp must not create a false local change on every sync.
            byte[] canonical = mapper.writeValueAsBytes(object);
            return new Snapshot(canonical, sha256(canonical));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Snapshot restoreRemoteSnapshot(Snapshot remote) throws IOException {
        Snapshot before = exportSnapshot();
        Files.createDirectories(AppPaths.cacheDir());
        Path remoteFile = Files.createTempFile(AppPaths.cacheDir(), "webdav-remote-user-data-", ".json");
        Path rollbackFile = Files.createTempFile(AppPaths.cacheDir(), "webdav-local-rollback-", ".json");
        try {
            Files.write(remoteFile, remote.bytes());
            Files.write(rollbackFile, before.bytes());
            try {
                portableUserData.restoreFrom(remoteFile);
            } catch (Exception failure) {
                try {
                    portableUserData.restoreFrom(rollbackFile);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                if (failure instanceof IOException io) throw io;
                throw new IOException("Не вдалося застосувати віддалені користувацькі дані", failure);
            }
            return exportSnapshot();
        } finally {
            Files.deleteIfExists(remoteFile);
            Files.deleteIfExists(rollbackFile);
        }
    }

    private long pushSnapshot(WebDavSyncAdapter adapter, Snapshot snapshot, String deviceId) throws IOException {
        long sequence = Math.max(0L, parseLong(settings.get(WEB_SEQUENCE, "0"), 0L)) + 1L;
        Instant now = Instant.now();
        String encoded = gzipBase64(snapshot.bytes());
        if (encoded.length() > 12_000_000) {
            throw new IOException("Стиснений пакет користувацьких даних завеликий для одного безпечного WebDAV-пакета");
        }
        int count = Math.max(1, (encoded.length() + SNAPSHOT_CHUNK_CHARS - 1) / SNAPSHOT_CHUNK_CHARS);
        List<SyncRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int start = i * SNAPSHOT_CHUNK_CHARS;
            int end = Math.min(encoded.length(), start + SNAPSHOT_CHUNK_CHARS);
            String index = String.format(Locale.ROOT, "%05d", i);
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("kind", "portable-user-data-v1");
            payload.put("hash", snapshot.hash());
            payload.put("index", Integer.toString(i));
            payload.put("count", Integer.toString(count));
            payload.put("encoding", "gzip+base64");
            payload.put("chunk", encoded.substring(start, end));
            records.add(SyncRecord.live(SNAPSHOT_PREFIX + index, SyncEntityType.SETTING, SNAPSHOT_PREFIX + index,
                    Math.max(0, sequence - 1), sequence, now, deviceId, payload));
        }
        ChangeSet set = new ChangeSet(UUID.randomUUID().toString(), deviceId, sequence,
                sequence <= 1 ? "" : deviceId + ":" + (sequence - 1), now,
                com.myhomelibcorp.domain.model.sync.SyncSchema.CURRENT_VERSION, records);
        adapter.push(set);
        settings.put(WEB_SEQUENCE, Long.toString(sequence));
        return sequence;
    }

    private boolean isSnapshotChangeSet(ChangeSet set) {
        if (set == null || set.records().isEmpty()) return false;
        return set.records().stream().allMatch(record -> record.entityType() == SyncEntityType.SETTING
                && record.syncId().startsWith(SNAPSHOT_PREFIX)
                && "portable-user-data-v1".equals(record.payload().get("kind")));
    }

    private Snapshot decodeSnapshot(ChangeSet set) throws IOException {
        if (!isSnapshotChangeSet(set)) throw new IOException("Пакет не є підтримуваною версією синхронізації користувацьких даних");
        List<SyncRecord> records = set.records().stream()
                .sorted(Comparator.comparingInt(record -> Integer.parseInt(record.payload().getOrDefault("index", "-1"))))
                .toList();
        int expectedCount = Integer.parseInt(records.getFirst().payload().getOrDefault("count", "0"));
        String expectedHash = records.getFirst().payload().getOrDefault("hash", "");
        if (expectedCount < 1 || expectedCount != records.size() || expectedHash.isBlank()) {
            throw new IOException("Неповний пакет WebDAV-синхронізації");
        }
        StringBuilder encoded = new StringBuilder(records.size() * SNAPSHOT_CHUNK_CHARS);
        for (int i = 0; i < records.size(); i++) {
            SyncRecord record = records.get(i);
            if (!Integer.toString(i).equals(record.payload().get("index"))
                    || !Integer.toString(expectedCount).equals(record.payload().get("count"))
                    || !expectedHash.equals(record.payload().get("hash"))
                    || !"gzip+base64".equals(record.payload().get("encoding"))) {
                throw new IOException("Пошкоджено послідовність частин WebDAV-пакета");
            }
            encoded.append(record.payload().getOrDefault("chunk", ""));
        }
        byte[] bytes = gunzipBase64(encoded.toString());
        String actual = sha256(bytes);
        if (!expectedHash.equals(actual)) throw new IOException("Контрольна сума WebDAV-пакета не збігається");
        return new Snapshot(bytes, actual);
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

    private Map<String, Long> loadCursorMap() {
        String raw = settings.get(WEB_CURSORS, "").trim();
        if (raw.isBlank()) return new HashMap<>();
        try {
            Map<String, Long> parsed = mapper.readValue(raw, CURSOR_MAP_TYPE);
            Map<String, Long> clean = new HashMap<>();
            parsed.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && value >= 0) clean.put(key, value);
            });
            return clean;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void saveCursorMap(Map<String, Long> cursors) {
        try {
            settings.put(WEB_CURSORS, mapper.writeValueAsString(cursors == null ? Map.of() : cursors));
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося зберегти курсор WebDAV-синхронізації", e);
        }
    }

    private static Map<String, Long> advanceCursors(Map<String, Long> original, List<ChangeSet> pulled) {
        Map<String, Long> result = new HashMap<>(original == null ? Map.of() : original);
        if (pulled != null) {
            for (ChangeSet set : pulled) {
                if (set == null) continue;
                result.merge(set.sourceDeviceId(), set.sequence(), Math::max);
            }
        }
        return result;
    }

    private void commitSyncState(String localHash, Map<String, Long> cursors) {
        settings.put(WEB_LAST_LOCAL_HASH, localHash);
        saveCursorMap(cursors);
    }

    private void resetWebDavState() {
        settings.remove(WEB_LAST_LOCAL_HASH);
        settings.remove(WEB_SEQUENCE);
        settings.remove(WEB_CURSORS);
    }

    private String ensureDeviceId() {
        String current = settings.get(SYNC_DEVICE_ID, "").trim();
        if (!current.isBlank()) return current;
        String generated = "device-" + UUID.randomUUID();
        settings.put(SYNC_DEVICE_ID, generated);
        return generated;
    }

    private static URI validateWebDavEndpoint(String value) {
        URI uri;
        try {
            uri = URI.create(Objects.requireNonNullElse(value, "").trim()).normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Некоректна URL-адреса WebDAV", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("https".equalsIgnoreCase(scheme) || isLoopbackHttp(uri))) {
            throw new IllegalArgumentException("WebDAV має використовувати HTTPS; HTTP дозволено лише для localhost");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("Не додавайте логін або пароль у URL WebDAV");
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("У URL WebDAV відсутній сервер");
        String text = uri.toString();
        if (!text.endsWith("/")) text += "/";
        return URI.create(text);
    }

    private static boolean isLoopbackHttp(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static boolean sameEndpoint(String left, String right) {
        try { return validateWebDavEndpoint(left).equals(validateWebDavEndpoint(right)); }
        catch (Exception ignored) { return Objects.equals(left, right); }
    }

    private void scanPluginPackages() throws IOException {
        Files.createDirectories(pluginDir);
        Map<String, PluginPackage> found = new LinkedHashMap<>();
        try (var stream = Files.list(pluginDir)) {
            for (Path jar : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList()) {
                try {
                    PluginManifest manifest = readPluginManifest(jar);
                    PluginPackage pkg = new PluginPackage(jar.toAbsolutePath().normalize(), manifest, sha256(Files.readAllBytes(jar)));
                    PluginPackage previous = found.putIfAbsent(manifest.pluginId(), pkg);
                    if (previous != null) {
                        pluginFailures.put(manifest.pluginId(), "Знайдено кілька JAR з однаковим pluginId; використовується " + previous.jar().getFileName());
                        continue;
                    }
                    PluginPackage known = pluginPackages.get(manifest.pluginId());
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

    private PluginManifest readPluginManifest(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(PLUGIN_MANIFEST);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("У JAR відсутній " + PLUGIN_MANIFEST);
            }
            if (entry.getSize() > MAX_PLUGIN_MANIFEST_BYTES) throw new IOException("Маніфест плагіна завеликий");
            Properties properties = new Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(MAX_PLUGIN_MANIFEST_BYTES + 1);
                if (bytes.length > MAX_PLUGIN_MANIFEST_BYTES) throw new IOException("Маніфест плагіна завеликий");
                properties.load(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
            }
            String id = requiredProperty(properties, "pluginId");
            String name = requiredProperty(properties, "displayName");
            String version = requiredProperty(properties, "version");
            PluginApiVersion min = parseApiVersion(requiredProperty(properties, "apiMin"));
            PluginApiVersion max = parseApiVersion(requiredProperty(properties, "apiMax"));
            Set<PluginService> services = parseEnumSet(properties.getProperty("services", ""), PluginService.class);
            Set<PluginService> overrides = parseEnumSet(properties.getProperty("overrides", ""), PluginService.class);
            Set<PluginPermission> permissions = parseEnumSet(properties.getProperty("permissions", ""), PluginPermission.class);
            return new PluginManifest(id, name, version, new PluginApiRange(min, max), services, overrides, permissions);
        }
    }

    private void autoEnableApprovedPlugins() {
        for (BundledPlugin bundled : new ArrayList<>(bundledPlugins.values())) {
            PluginManifest manifest = bundled.entrypoint().manifest();
            if (pluginPackages.containsKey(manifest.pluginId())) continue;
            if (!settings.getBoolean(approvalKey(manifest, bundled.fingerprint()), false)) continue;
            Result result = enablePlugin(manifest.pluginId(), true);
            if (!result.success()) pluginFailures.put(manifest.pluginId(), result.message());
        }
        for (PluginPackage pkg : new ArrayList<>(pluginPackages.values())) {
            if (!settings.getBoolean(approvalKey(pkg), false)) continue;
            Result result = enablePlugin(pkg.manifest().pluginId(), true);
            if (!result.success()) pluginFailures.put(pkg.manifest().pluginId(), result.message());
        }
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
        if (bundled == null) return Result.fail("Вбудований плагін не знайдено: " + pluginId);
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
            return Result.ok("Плагін «" + manifest.displayName() + "» увімкнено.");
        } catch (Exception | LinkageError e) {
            pluginFailures.put(pluginId, safeMessage(e));
            return Result.fail("Не вдалося увімкнути плагін: " + safeMessage(e));
        }
    }

    private PluginManifest requireAnyPluginManifest(String pluginId) {
        PluginPackage external = pluginPackages.get(pluginId);
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
                String sourceExtension = context.sourceFormat() == null || context.sourceFormat().isBlank()
                        ? ".book" : "." + context.sourceFormat().replace('_', '.');
                Path source = Files.createTempFile("mhl-plugin-export-", sourceExtension);
                try {
                    Files.copy(context.sourceStream(), source, StandardCopyOption.REPLACE_EXISTING);
                    com.myhomelibcorp.plugin.api.PluginOperationContext pluginContext = new com.myhomelibcorp.plugin.api.PluginOperationContext() {
                        @Override public boolean isCancelled() { return context.cancelled().getAsBoolean(); }
                        @Override public void reportProgress(long completed, long total) { /* host operation owns visible progress */ }
                    };
                    Path produced = provider.export(source, context.targetFile(), targetFormat, pluginContext);
                    context.checkCancelled();
                    if (produced != null) {
                        Path normalizedProduced = produced.toAbsolutePath().normalize();
                        Path target = context.targetFile().toAbsolutePath().normalize();
                        if (!normalizedProduced.equals(target)) {
                            if (!Files.isRegularFile(normalizedProduced)) {
                                throw new IOException("Плагін експорту не створив заявлений файл: " + normalizedProduced);
                            }
                            Files.copy(normalizedProduced, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    if (!Files.isRegularFile(context.targetFile()) || Files.size(context.targetFile()) <= 0) {
                        throw new IOException("Плагін експорту не створив коректний файл результату");
                    }
                } finally {
                    Files.deleteIfExists(source);
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

    private static String safeId(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        return normalized.isBlank() ? fallback : normalized.substring(0, Math.min(80, normalized.length()));
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
                default -> throw new UnsupportedOperationException(method.getName());
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
        if (quarantined) pluginFailures.put(pluginId, message == null ? "Помилка виконання плагіна" : message);
    }

    private PluginPackage requirePluginPackage(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) throw new IllegalArgumentException("Не вибрано плагін");
        PluginPackage pkg = pluginPackages.get(pluginId);
        if (pkg == null) throw new IllegalArgumentException("Плагін не встановлено: " + pluginId);
        return pkg;
    }

    private List<AiProvider> currentAiProviders() {
        Map<String, AiProvider> providers = new LinkedHashMap<>();
        for (AiProvider provider : builtInAiProviders) providers.put(provider.id(), provider);
        for (AiProvider provider : runtimeExtensions.aiProviders()) providers.putIfAbsent(provider.id(), provider);
        return List.copyOf(providers.values());
    }

    private AiProvider requireAiProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("Не вибрано провайдера ШІ");
        return currentAiProviders().stream().filter(provider -> provider.id().equals(providerId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Провайдер ШІ недоступний: " + providerId));
    }

    private AiExtensionService aiService() {
        return new AiExtensionService(currentAiProviders(), settings, secretStore);
    }

    private Optional<SecretStore> openSecretStore() {
        SecretStoreContext context = new SecretStoreContext(AppPaths.configDir(), AppPaths.portableMode(),
                System.getProperty("os.name", ""), System.getProperty("user.name", "unknown"));
        return ServiceLoader.load(SecretStoreProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(SecretStoreProvider::priority).reversed())
                .map(provider -> {
                    try { return provider.open(context); }
                    catch (RuntimeException ignored) { return Optional.<SecretStore>empty(); }
                })
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static String approvalKey(PluginPackage pkg) {
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

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key, "").trim();
        if (value.isBlank()) throw new IllegalArgumentException("У маніфесті плагіна відсутнє поле " + key);
        return value;
    }

    private static PluginApiVersion parseApiVersion(String value) {
        String[] parts = value.trim().split("\\.");
        if (parts.length != 2) throw new IllegalArgumentException("Версія Plugin API має формат major.minor: " + value);
        return new PluginApiVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    private static <E extends Enum<E>> Set<E> parseEnumSet(String raw, Class<E> type) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return Set.of();
        EnumSet<E> result = EnumSet.noneOf(type);
        for (String token : value.split(",")) {
            String normalized = token.trim();
            if (!normalized.isBlank()) result.add(Enum.valueOf(type, normalized));
        }
        return Set.copyOf(result);
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sanitizeFilePart(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private static String gzipBase64(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(bytes); }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static byte[] gunzipBase64(String encoded) throws IOException {
        byte[] compressed;
        try { compressed = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new IOException("Некоректне Base64-кодування пакета", e); }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(MAX_SNAPSHOT_BYTES, Math.max(8192, compressed.length * 2)))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = gzip.read(buffer)) >= 0;) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_SNAPSHOT_BYTES) throw new IOException("Розпакований пакет перевищує безпечний ліміт 64 МБ");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 недоступний", impossible);
        }
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (Exception ignored) { return fallback; }
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null) return "Невідома помилка";
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) message = current.getClass().getSimpleName();
        String clean = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.length() <= 600 ? clean : clean.substring(0, 600);
    }

    private record Snapshot(byte[] bytes, String hash) {
        private Snapshot {
            bytes = bytes == null ? new byte[0] : bytes.clone();
            hash = hash == null ? "" : hash;
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private record RemoteSnapshot(ChangeSet changeSet, Snapshot snapshot) { }
    private record PendingSync(Snapshot local, RemoteSnapshot remote, Map<String, Long> nextCursors) { }
    private record PluginPackage(Path jar, PluginManifest manifest, String sha256) { }
    private record BundledPlugin(PluginEntrypoint entrypoint, String fingerprint) { }
}
