package com.myhomelibcorp.plugin.api;

import java.util.Objects;
import java.util.Set;

/** Effective host-owned security context attached to an enabled plugin. */
public record PluginSecurityContext(PluginTrustLevel trustLevel, Set<PluginPermission> approvedPermissions) {
    public PluginSecurityContext {
        Objects.requireNonNull(trustLevel, "trustLevel");
        approvedPermissions = Set.copyOf(Objects.requireNonNull(approvedPermissions, "approvedPermissions"));
    }
}
