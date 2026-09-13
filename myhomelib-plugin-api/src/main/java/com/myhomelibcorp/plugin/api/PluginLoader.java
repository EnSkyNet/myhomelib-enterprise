package com.myhomelibcorp.plugin.api;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;

/** Validates plugin compatibility, explicit permissions/trust and core-service overrides before activation. */
public final class PluginLoader {
    private final PluginApiVersion hostApiVersion;
    private final Set<PluginService> coreServices;
    private final Set<String> loadedPluginIds = ConcurrentHashMap.newKeySet();

    public PluginLoader(Set<PluginService> coreServices) {
        this(PluginApiVersion.CURRENT, coreServices);
    }

    public PluginLoader(PluginApiVersion hostApiVersion, Set<PluginService> coreServices) {
        this.hostApiVersion = Objects.requireNonNull(hostApiVersion, "hostApiVersion");
        Set<PluginService> requestedCoreServices = Objects.requireNonNull(coreServices, "coreServices");
        this.coreServices = requestedCoreServices.isEmpty()
                ? EnumSet.noneOf(PluginService.class)
                : EnumSet.copyOf(requestedCoreServices);
    }

    /**
     * Trusted-classpath ServiceLoader path. Approval still comes from the host; discovery alone never enables a plugin.
     */
    public List<LoadedPlugin> loadFromServiceLoader(
            ClassLoader classLoader,
            Function<PluginManifest, PluginApproval> approvalResolver
    ) {
        Objects.requireNonNull(classLoader, "classLoader");
        Objects.requireNonNull(approvalResolver, "approvalResolver");
        List<LoadedPlugin> result = new ArrayList<>();
        ServiceLoader.load(PluginEntrypoint.class, classLoader).forEach(entrypoint -> {
            PluginManifest manifest = Objects.requireNonNull(entrypoint.manifest(), "plugin manifest");
            result.add(load(entrypoint, approvalResolver.apply(manifest)));
        });
        return List.copyOf(result);
    }

    /** Explicit approval is mandatory for activation. */
    public LoadedPlugin load(PluginEntrypoint entrypoint, PluginApproval approval) {
        Objects.requireNonNull(entrypoint, "entrypoint");
        PluginManifest manifest = Objects.requireNonNull(entrypoint.manifest(), "plugin manifest");
        validateManifest(manifest);
        validateApproval(manifest, approval);

        Map<PluginService, Object> services = Map.copyOf(Objects.requireNonNull(entrypoint.services(), "plugin services"));
        validateServices(manifest, services);
        validateAiProviderPermissions(manifest, services);
        if (!loadedPluginIds.add(manifest.pluginId())) {
            throw new PluginLoadException("Plugin id is already loaded: " + manifest.pluginId());
        }
        return new LoadedPlugin(manifest, services,
                new PluginSecurityContext(approval.trustLevel(), approval.approvedPermissions()));
    }

    /** Release a disabled/quarantined plugin id so an explicitly approved re-enable can occur later. */
    public boolean unload(String pluginId) {
        return loadedPluginIds.remove(Objects.requireNonNull(pluginId, "pluginId"));
    }

    void validateManifest(PluginManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        if (!manifest.apiRange().supports(hostApiVersion)) {
            throw new PluginLoadException("Plugin " + manifest.pluginId() + " is incompatible with host Plugin API " + hostApiVersion);
        }
    }

    private void validateApproval(PluginManifest manifest, PluginApproval approval) {
        if (approval == null) {
            throw new PluginLoadException("Explicit host approval is required before enabling plugin " + manifest.pluginId());
        }
        if (!manifest.pluginId().equals(approval.pluginId()) || !manifest.version().equals(approval.pluginVersion())) {
            throw new PluginLoadException("Plugin approval does not match plugin id/version: " + manifest.pluginId());
        }
        if (approval.trustLevel() != PluginTrustLevel.TRUSTED) {
            throw new PluginLoadException("Untrusted plugin cannot execute in-process: " + manifest.pluginId());
        }
        if (!approval.approvedPermissions().equals(manifest.permissions())) {
            throw new PluginLoadException("Approved permissions must exactly match requested permissions for plugin "
                    + manifest.pluginId());
        }
    }

    private void validateAiProviderPermissions(PluginManifest manifest, Map<PluginService, Object> services) {
        Object service = services.get(PluginService.AI_PROVIDER);
        if (!(service instanceof AiProvider provider)) return;
        var capabilities = Objects.requireNonNull(provider.capabilities(), "AI provider capabilities");
        if (capabilities.networkRequired() && !manifest.permissions().contains(PluginPermission.NETWORK_ACCESS)) {
            throw new PluginLoadException("Network AI provider must declare NETWORK_ACCESS: " + manifest.pluginId());
        }
    }

    private void validateServices(PluginManifest manifest, Map<PluginService, Object> services) {
        if (!services.keySet().equals(manifest.services())) {
            throw new PluginLoadException("Plugin service bindings must exactly match manifest declarations");
        }
        for (Map.Entry<PluginService, Object> binding : services.entrySet()) {
            PluginService service = binding.getKey();
            Object implementation = Objects.requireNonNull(binding.getValue(), "service implementation");
            if (!service.contractType().isInstance(implementation)) {
                throw new PluginLoadException("Service " + service + " does not implement " + service.contractType().getName());
            }
            if (coreServices.contains(service) && !manifest.overridesCoreServices().contains(service)) {
                throw new PluginLoadException("Plugin " + manifest.pluginId()
                        + " would override core service " + service + " without explicit declaration");
            }
        }
    }
}
