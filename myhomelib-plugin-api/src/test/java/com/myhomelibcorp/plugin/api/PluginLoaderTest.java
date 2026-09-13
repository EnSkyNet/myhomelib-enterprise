package com.myhomelibcorp.plugin.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginLoaderTest {
    @Test
    void samplePluginLoadsViaServiceLoaderOnlyAfterExplicitApproval() {
        PluginLoader loader = new PluginLoader(Set.of());
        List<LoadedPlugin> loaded = loader.loadFromServiceLoader(
                getClass().getClassLoader(), PluginApproval::trusted);
        assertThat(loaded).extracting(p -> p.manifest().pluginId()).contains("sample.dictionary");
        assertThat(loaded).allSatisfy(plugin -> assertThat(plugin.securityContext().trustLevel())
                .isEqualTo(PluginTrustLevel.TRUSTED));
    }

    @Test
    void incompatibleApiVersionIsBlocked() {
        PluginLoader loader = new PluginLoader(Set.of());
        PluginEntrypoint plugin = devicePlugin("future.device",
                new PluginApiRange(new PluginApiVersion(2, 0), new PluginApiVersion(2, 9)), false, Set.of());
        assertThatThrownBy(() -> loader.load(plugin, approval(plugin, PluginTrustLevel.TRUSTED, Set.of())))
                .isInstanceOf(PluginLoadException.class).hasMessageContaining("incompatible");
    }

    @Test
    void pluginCannotSilentlyOverrideCoreService() {
        PluginLoader loader = new PluginLoader(Set.of(PluginService.DEVICE_PROVIDER));
        PluginEntrypoint plugin = devicePlugin("device.hidden-override", PluginApiRange.currentMajor(), false, Set.of());
        assertThatThrownBy(() -> loader.load(plugin, approval(plugin, PluginTrustLevel.TRUSTED, Set.of())))
                .isInstanceOf(PluginLoadException.class).hasMessageContaining("without explicit declaration");
    }

    @Test
    void explicitlyDeclaredCoreOverrideIsAccepted() {
        PluginLoader loader = new PluginLoader(Set.of(PluginService.DEVICE_PROVIDER));
        PluginEntrypoint plugin = devicePlugin("device.explicit-override", PluginApiRange.currentMajor(), true, Set.of());
        LoadedPlugin loaded = loader.load(plugin, approval(plugin, PluginTrustLevel.TRUSTED, Set.of()));
        assertThat(loaded.manifest().overridesCoreServices()).containsExactly(PluginService.DEVICE_PROVIDER);
    }

    @Test
    void wrongServiceTypeIsRejected() {
        PluginManifest manifest = new PluginManifest("bad.binding", "Bad Binding", "1", PluginApiRange.currentMajor(),
                Set.of(PluginService.DEVICE_PROVIDER), Set.of());
        PluginEntrypoint plugin = entrypoint(manifest, Map.of(PluginService.DEVICE_PROVIDER, "not a provider"));
        assertThatThrownBy(() -> new PluginLoader(Set.of()).load(plugin, PluginApproval.trusted(manifest)))
                .isInstanceOf(PluginLoadException.class).hasMessageContaining("does not implement");
    }

    @Test
    void bindingsMustMatchManifestExactly() {
        PluginManifest manifest = new PluginManifest("bad.manifest", "Bad Manifest", "1", PluginApiRange.currentMajor(),
                Set.of(PluginService.DEVICE_PROVIDER), Set.of());
        PluginEntrypoint plugin = entrypoint(manifest, Map.of());
        assertThatThrownBy(() -> new PluginLoader(Set.of()).load(plugin, PluginApproval.trusted(manifest)))
                .isInstanceOf(PluginLoadException.class).hasMessageContaining("exactly match");
    }

    @Test
    void explicitApprovalIsRequiredBeforeEnable() {
        PluginEntrypoint plugin = devicePlugin("device.needs-approval", PluginApiRange.currentMajor(), false, Set.of());
        assertThatThrownBy(() -> new PluginLoader(Set.of()).load(plugin, null))
                .isInstanceOf(PluginLoadException.class).hasMessageContaining("Explicit host approval");
    }

    @Test
    void untrustedPluginCannotExecuteInProcess() {
        PluginEntrypoint plugin = devicePlugin("device.untrusted", PluginApiRange.currentMajor(), false, Set.of());
        assertThatThrownBy(() -> new PluginLoader(Set.of()).load(
                plugin, approval(plugin, PluginTrustLevel.UNTRUSTED, Set.of())))
                .isInstanceOf(PluginLoadException.class).hasMessageContaining("cannot execute in-process");
    }

    @Test
    void requestedNetworkAndFilesystemCapabilitiesMustExactlyMatchApproval() {
        Set<PluginPermission> requested = Set.of(PluginPermission.NETWORK_ACCESS, PluginPermission.FILESYSTEM_READ);
        PluginEntrypoint plugin = devicePlugin("device.capabilities", PluginApiRange.currentMajor(), false, requested);
        assertThatThrownBy(() -> new PluginLoader(Set.of()).load(
                plugin, approval(plugin, PluginTrustLevel.TRUSTED, Set.of(PluginPermission.NETWORK_ACCESS))))
                .isInstanceOf(PluginLoadException.class).hasMessageContaining("exactly match");

        LoadedPlugin loaded = new PluginLoader(Set.of()).load(
                plugin, approval(plugin, PluginTrustLevel.TRUSTED, requested));
        assertThat(loaded.securityContext().approvedPermissions()).containsExactlyInAnyOrderElementsOf(requested);
    }

    @Test
    void approvalIsBoundToExactPluginVersion() {
        PluginEntrypoint plugin = devicePlugin("device.versioned", PluginApiRange.currentMajor(), false, Set.of());
        PluginManifest manifest = plugin.manifest();
        PluginApproval stale = new PluginApproval(manifest.pluginId(), "0.9.0", PluginTrustLevel.TRUSTED, Set.of());
        assertThatThrownBy(() -> new PluginLoader(Set.of()).load(plugin, stale))
                .isInstanceOf(PluginLoadException.class).hasMessageContaining("id/version");
    }

    private static PluginEntrypoint devicePlugin(String id, PluginApiRange range, boolean override,
                                                  Set<PluginPermission> permissions) {
        Set<PluginService> overrides = override ? Set.of(PluginService.DEVICE_PROVIDER) : Set.of();
        PluginManifest manifest = new PluginManifest(id, id, "1.0.0", range,
                Set.of(PluginService.DEVICE_PROVIDER), overrides, permissions);
        DeviceProvider provider = new DeviceProvider() {
            public String id() { return id; }
            public Optional<DeviceProfile> detect(Path mountRoot) { return Optional.empty(); }
        };
        return entrypoint(manifest, Map.of(PluginService.DEVICE_PROVIDER, provider));
    }

    private static PluginApproval approval(PluginEntrypoint plugin, PluginTrustLevel trust,
                                           Set<PluginPermission> permissions) {
        PluginManifest manifest = plugin.manifest();
        return new PluginApproval(manifest.pluginId(), manifest.version(), trust, permissions);
    }

    private static PluginEntrypoint entrypoint(PluginManifest manifest, Map<PluginService, Object> services) {
        return new PluginEntrypoint() {
            public PluginManifest manifest() { return manifest; }
            public Map<PluginService, Object> services() { return services; }
        };
    }
}
