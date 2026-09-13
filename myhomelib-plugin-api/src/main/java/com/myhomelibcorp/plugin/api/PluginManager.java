package com.myhomelibcorp.plugin.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host-side plugin lifecycle and fault-containment boundary.
 *
 * <p>Untrusted plugins are never enabled in-process. This is deliberately stricter than pretending
 * that Java class loading is an OS sandbox. A future isolated host may add out-of-process execution.
 * Trusted in-process plugins must still declare every capability up front; managed invocations may
 * additionally require specific approved capabilities before the host calls into plugin code.</p>
 */
public final class PluginManager {
    private final PluginLoader loader;
    private final Map<String, LoadedPlugin> enabled = new ConcurrentHashMap<>();
    private final Map<String, PluginStatus> statuses = new ConcurrentHashMap<>();

    public PluginManager(PluginLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * Manifest-only preview used to show requested capabilities before enable without touching services.
     */
    public PluginActivationRequest inspect(PluginManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        loader.validateManifest(manifest);

        PluginStatus previous = statuses.get(manifest.pluginId());
        boolean sameVersion = previous != null
                && previous.manifest().version().equals(manifest.version());
        PluginTrustLevel trust = sameVersion ? previous.trustLevel() : PluginTrustLevel.UNTRUSTED;
        Set<PluginPermission> approved = sameVersion ? previous.approvedPermissions() : Set.of();
        PluginState state = sameVersion ? previous.state() : PluginState.DISABLED;
        String failure = sameVersion ? previous.failureSummary() : "";

        statuses.put(manifest.pluginId(), new PluginStatus(manifest, trust, state, approved, failure));
        return new PluginActivationRequest(manifest.pluginId(), manifest.displayName(), manifest.version(),
                manifest.permissions(), trust);
    }

    /** Trusted-classpath convenience path; external/untrusted discovery should inspect metadata first. */
    public PluginActivationRequest inspect(PluginEntrypoint entrypoint) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        return inspect(Objects.requireNonNull(entrypoint.manifest(), "plugin manifest"));
    }

    public LoadedPlugin enable(PluginEntrypoint entrypoint, PluginApproval approval) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        LoadedPlugin loaded = loader.load(entrypoint, approval);
        PluginManifest manifest = loaded.manifest();
        enabled.put(manifest.pluginId(), loaded);
        statuses.put(manifest.pluginId(), new PluginStatus(manifest, loaded.securityContext().trustLevel(),
                PluginState.ENABLED, loaded.securityContext().approvedPermissions(), ""));
        return loaded;
    }

    /** Disable an enabled or quarantined plugin; a fresh explicit approval is still required to re-enable it. */
    public boolean disable(String pluginId) {
        Objects.requireNonNull(pluginId, "pluginId");
        LoadedPlugin removed = enabled.remove(pluginId);
        PluginStatus current = statuses.get(pluginId);
        if (removed == null && (current == null || current.state() == PluginState.DISABLED)) return false;

        loader.unload(pluginId);
        PluginManifest manifest = removed != null ? removed.manifest() : current.manifest();
        PluginTrustLevel trust = removed != null ? removed.securityContext().trustLevel() : current.trustLevel();
        Set<PluginPermission> permissions = removed != null
                ? removed.securityContext().approvedPermissions()
                : current.approvedPermissions();
        statuses.put(pluginId, new PluginStatus(manifest, trust, PluginState.DISABLED, permissions, ""));
        return true;
    }

    public Optional<PluginStatus> status(String pluginId) {
        return Optional.ofNullable(statuses.get(pluginId));
    }

    public <S, R> PluginInvocationResult<R> invoke(
            String pluginId,
            PluginService service,
            Class<S> serviceType,
            PluginInvocation<S, R> invocation
    ) {
        return invoke(pluginId, service, serviceType, Set.of(), invocation);
    }

    /**
     * Invoke a plugin through the managed boundary and require the listed host-approved capabilities.
     * This is a fail-closed host gate, not an OS sandbox for already trusted in-process code.
     */
    public <S, R> PluginInvocationResult<R> invoke(
            String pluginId,
            PluginService service,
            Class<S> serviceType,
            Set<PluginPermission> requiredPermissions,
            PluginInvocation<S, R> invocation
    ) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(serviceType, "serviceType");
        Set<PluginPermission> required = Set.copyOf(Objects.requireNonNull(requiredPermissions, "requiredPermissions"));
        Objects.requireNonNull(invocation, "invocation");

        LoadedPlugin plugin = enabled.get(pluginId);
        if (plugin == null) return PluginInvocationResult.blocked("Plugin is not enabled: " + pluginId);
        if (!plugin.securityContext().approvedPermissions().containsAll(required)) {
            return PluginInvocationResult.blocked("Plugin lacks required approved permissions: " + required);
        }

        Object implementation = plugin.services().get(service);
        if (implementation == null) return PluginInvocationResult.blocked("Plugin does not provide service: " + service);
        if (!serviceType.isInstance(implementation) || !service.contractType().isAssignableFrom(serviceType)) {
            return PluginInvocationResult.blocked("Requested service type does not match plugin contract: " + service);
        }

        try {
            return PluginInvocationResult.success(invocation.invoke(serviceType.cast(implementation)));
        } catch (Exception failure) {
            return quarantineAndFail(plugin, failure);
        } catch (LinkageError | AssertionError failure) {
            return quarantineAndFail(plugin, failure);
        }
    }

    private <R> PluginInvocationResult<R> quarantineAndFail(LoadedPlugin plugin, Throwable failure) {
        quarantine(plugin, failure);
        return PluginInvocationResult.failed(summarize(failure));
    }

    private void quarantine(LoadedPlugin plugin, Throwable failure) {
        String pluginId = plugin.manifest().pluginId();
        enabled.remove(pluginId);
        loader.unload(pluginId);
        statuses.put(pluginId, new PluginStatus(plugin.manifest(), plugin.securityContext().trustLevel(),
                PluginState.QUARANTINED, plugin.securityContext().approvedPermissions(), summarize(failure)));
    }

    private static String summarize(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }
}
