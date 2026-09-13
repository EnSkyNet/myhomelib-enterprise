package com.myhomelibcorp.plugin.api;

import java.util.Objects;
import java.util.Set;

/** Current host state suitable for plugin-management UI/status reporting. */
public record PluginStatus(
        PluginManifest manifest,
        PluginTrustLevel trustLevel,
        PluginState state,
        Set<PluginPermission> approvedPermissions,
        String failureSummary
) {
    public PluginStatus {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(trustLevel, "trustLevel");
        Objects.requireNonNull(state, "state");
        approvedPermissions = Set.copyOf(Objects.requireNonNull(approvedPermissions, "approvedPermissions"));
        failureSummary = failureSummary == null ? "" : failureSummary;
    }
}
