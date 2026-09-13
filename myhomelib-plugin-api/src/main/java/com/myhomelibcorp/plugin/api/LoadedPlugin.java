package com.myhomelibcorp.plugin.api;

import java.util.Map;
import java.util.Objects;

/** Validated and explicitly approved plugin that is safe to register with the host. */
public record LoadedPlugin(
        PluginManifest manifest,
        Map<PluginService, Object> services,
        PluginSecurityContext securityContext
) {
    public LoadedPlugin {
        Objects.requireNonNull(manifest, "manifest");
        services = Map.copyOf(Objects.requireNonNull(services, "services"));
        Objects.requireNonNull(securityContext, "securityContext");
    }
}
