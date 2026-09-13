package com.myhomelibcorp.plugin.api;

import java.util.Objects;
import java.util.Set;

/** Explicit host approval bound to one plugin id/version and its requested capabilities. */
public record PluginApproval(
        String pluginId,
        String pluginVersion,
        PluginTrustLevel trustLevel,
        Set<PluginPermission> approvedPermissions
) {
    public PluginApproval {
        if (pluginId == null || pluginId.isBlank()) throw new IllegalArgumentException("pluginId is required");
        if (pluginVersion == null || pluginVersion.isBlank()) throw new IllegalArgumentException("pluginVersion is required");
        Objects.requireNonNull(trustLevel, "trustLevel");
        approvedPermissions = Set.copyOf(Objects.requireNonNull(approvedPermissions, "approvedPermissions"));
    }

    public static PluginApproval trusted(PluginManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return new PluginApproval(manifest.pluginId(), manifest.version(), PluginTrustLevel.TRUSTED, manifest.permissions());
    }
}
