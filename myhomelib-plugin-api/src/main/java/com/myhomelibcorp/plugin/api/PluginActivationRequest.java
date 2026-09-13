package com.myhomelibcorp.plugin.api;

import java.util.Objects;
import java.util.Set;

/** Safe preview data for permission/trust UI before a plugin is enabled. */
public record PluginActivationRequest(
        String pluginId,
        String displayName,
        String version,
        Set<PluginPermission> requestedPermissions,
        PluginTrustLevel currentTrustLevel
) {
    public PluginActivationRequest {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(version, "version");
        requestedPermissions = Set.copyOf(Objects.requireNonNull(requestedPermissions, "requestedPermissions"));
        Objects.requireNonNull(currentTrustLevel, "currentTrustLevel");
    }

    public static PluginActivationRequest untrusted(PluginManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return new PluginActivationRequest(manifest.pluginId(), manifest.displayName(), manifest.version(),
                manifest.permissions(), PluginTrustLevel.UNTRUSTED);
    }
}
