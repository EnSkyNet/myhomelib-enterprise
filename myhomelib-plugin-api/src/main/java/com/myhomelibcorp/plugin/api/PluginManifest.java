package com.myhomelibcorp.plugin.api;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable plugin metadata used before any service is activated. */
public record PluginManifest(
        String pluginId,
        String displayName,
        String version,
        PluginApiRange apiRange,
        Set<PluginService> services,
        Set<PluginService> overridesCoreServices,
        Set<PluginPermission> permissions
) {
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");

    public PluginManifest {
        if (pluginId == null || !ID.matcher(pluginId).matches()) {
            throw new IllegalArgumentException("pluginId must be a lowercase dotted/dashed identifier");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        Objects.requireNonNull(apiRange, "apiRange");
        services = Set.copyOf(Objects.requireNonNull(services, "services"));
        overridesCoreServices = Set.copyOf(Objects.requireNonNull(overridesCoreServices, "overridesCoreServices"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        if (!services.containsAll(overridesCoreServices)) {
            throw new IllegalArgumentException("override declarations must refer to declared services");
        }
    }

    /** Source/binary compatibility constructor for API 1.0 plugins with no declared capabilities. */
    public PluginManifest(String pluginId, String displayName, String version, PluginApiRange apiRange,
                          Set<PluginService> services, Set<PluginService> overridesCoreServices) {
        this(pluginId, displayName, version, apiRange, services, overridesCoreServices, Set.of());
    }
}
