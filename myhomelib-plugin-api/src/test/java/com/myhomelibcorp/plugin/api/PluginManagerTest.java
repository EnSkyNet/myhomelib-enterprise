package com.myhomelibcorp.plugin.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PluginManagerTest {
    @Test
    void requestedPermissionsAreVisibleBeforeEnableAndPluginStartsDisabledUntrusted() {
        PluginEntrypoint plugin = devicePlugin("device.preview",
                Set.of(PluginPermission.NETWORK_ACCESS, PluginPermission.FILESYSTEM_READ), false);
        PluginManager manager = new PluginManager(new PluginLoader(Set.of()));

        PluginActivationRequest request = manager.inspect(plugin);

        assertThat(request.pluginId()).isEqualTo("device.preview");
        assertThat(request.currentTrustLevel()).isEqualTo(PluginTrustLevel.UNTRUSTED);
        assertThat(request.requestedPermissions()).containsExactlyInAnyOrder(
                PluginPermission.NETWORK_ACCESS, PluginPermission.FILESYSTEM_READ);
        assertThat(manager.status("device.preview")).get().satisfies(status -> {
            assertThat(status.state()).isEqualTo(PluginState.DISABLED);
            assertThat(status.trustLevel()).isEqualTo(PluginTrustLevel.UNTRUSTED);
        });
    }

    @Test
    void approvedPluginCanBeDisabledAndReEnabled() {
        PluginEntrypoint plugin = devicePlugin("device.toggle", Set.of(), false);
        PluginManager manager = new PluginManager(new PluginLoader(Set.of()));
        manager.enable(plugin, PluginApproval.trusted(plugin.manifest()));
        assertThat(manager.status("device.toggle")).get().extracting(PluginStatus::state).isEqualTo(PluginState.ENABLED);

        assertThat(manager.disable("device.toggle")).isTrue();
        assertThat(manager.status("device.toggle")).get().extracting(PluginStatus::state).isEqualTo(PluginState.DISABLED);

        manager.enable(plugin, PluginApproval.trusted(plugin.manifest()));
        assertThat(manager.status("device.toggle")).get().extracting(PluginStatus::state).isEqualTo(PluginState.ENABLED);
    }

    @Test
    void ordinaryPluginFailureIsContainedAndPluginIsQuarantined() {
        PluginEntrypoint plugin = devicePlugin("device.crashy", Set.of(), true);
        PluginManager manager = new PluginManager(new PluginLoader(Set.of()));
        manager.enable(plugin, PluginApproval.trusted(plugin.manifest()));

        PluginInvocationResult<Optional<DeviceProfile>> first = manager.invoke(
                "device.crashy", PluginService.DEVICE_PROVIDER, DeviceProvider.class,
                provider -> provider.detect(Path.of("/tmp/device")));

        assertThat(first.outcome()).isEqualTo(PluginInvocationResult.Outcome.FAILED);
        assertThat(first.message()).contains("IllegalStateException").contains("plugin boom");
        assertThat(manager.status("device.crashy")).get().satisfies(status -> {
            assertThat(status.state()).isEqualTo(PluginState.QUARANTINED);
            assertThat(status.failureSummary()).contains("plugin boom");
        });

        PluginInvocationResult<Optional<DeviceProfile>> second = manager.invoke(
                "device.crashy", PluginService.DEVICE_PROVIDER, DeviceProvider.class,
                provider -> provider.detect(Path.of("/tmp/device")));
        assertThat(second.outcome()).isEqualTo(PluginInvocationResult.Outcome.BLOCKED);
    }


    @Test
    void assertionFailureIsContainedAndQuarantinedInsteadOfEscapingToHost() {
        PluginManifest manifest = new PluginManifest("device.assertion", "device.assertion", "1.0.0",
                PluginApiRange.currentMajor(), Set.of(PluginService.DEVICE_PROVIDER), Set.of(), Set.of());
        DeviceProvider provider = new DeviceProvider() {
            public String id() { throw new AssertionError("broken plugin invariant"); }
            public Optional<DeviceProfile> detect(Path mountRoot) { return Optional.empty(); }
        };
        PluginEntrypoint plugin = new PluginEntrypoint() {
            public PluginManifest manifest() { return manifest; }
            public Map<PluginService, Object> services() { return Map.of(PluginService.DEVICE_PROVIDER, provider); }
        };
        PluginManager manager = new PluginManager(new PluginLoader(Set.of()));
        manager.enable(plugin, PluginApproval.trusted(manifest));

        PluginInvocationResult<String> result = manager.invoke(
                "device.assertion", PluginService.DEVICE_PROVIDER, DeviceProvider.class, DeviceProvider::id);

        assertThat(result.outcome()).isEqualTo(PluginInvocationResult.Outcome.FAILED);
        assertThat(result.message()).contains("AssertionError").contains("broken plugin invariant");
        assertThat(manager.status("device.assertion")).get()
                .extracting(PluginStatus::state).isEqualTo(PluginState.QUARANTINED);
    }

    @Test
    void managedInvocationFailsClosedWhenRequiredCapabilityWasNotApproved() {
        PluginEntrypoint plugin = devicePlugin("device.no-network", Set.of(PluginPermission.FILESYSTEM_READ), false);
        PluginManager manager = new PluginManager(new PluginLoader(Set.of()));
        manager.enable(plugin, PluginApproval.trusted(plugin.manifest()));

        PluginInvocationResult<String> result = manager.invoke(
                "device.no-network", PluginService.DEVICE_PROVIDER, DeviceProvider.class,
                Set.of(PluginPermission.NETWORK_ACCESS), DeviceProvider::id);

        assertThat(result.outcome()).isEqualTo(PluginInvocationResult.Outcome.BLOCKED);
        assertThat(result.message()).contains("required approved permissions");
        assertThat(manager.status("device.no-network")).get()
                .extracting(PluginStatus::state).isEqualTo(PluginState.ENABLED);
    }

    @Test
    void quarantinedPluginCanBeExplicitlyDisabledBeforeReapproval() {
        PluginEntrypoint plugin = devicePlugin("device.quarantine-disable", Set.of(), true);
        PluginManager manager = new PluginManager(new PluginLoader(Set.of()));
        manager.enable(plugin, PluginApproval.trusted(plugin.manifest()));
        manager.invoke("device.quarantine-disable", PluginService.DEVICE_PROVIDER, DeviceProvider.class,
                provider -> provider.detect(Path.of("/tmp/device")));

        assertThat(manager.disable("device.quarantine-disable")).isTrue();
        assertThat(manager.status("device.quarantine-disable")).get()
                .extracting(PluginStatus::state).isEqualTo(PluginState.DISABLED);
    }

    @Test
    void newPluginVersionReturnsToUntrustedPreviewState() {
        PluginEntrypoint v1 = devicePlugin("device.version-reset", Set.of(), false);
        PluginManager manager = new PluginManager(new PluginLoader(Set.of()));
        manager.enable(v1, PluginApproval.trusted(v1.manifest()));
        manager.disable("device.version-reset");

        PluginManifest v2Manifest = new PluginManifest("device.version-reset", "device.version-reset", "2.0.0",
                PluginApiRange.currentMajor(), Set.of(PluginService.DEVICE_PROVIDER), Set.of(),
                Set.of(PluginPermission.NETWORK_ACCESS));
        PluginActivationRequest request = manager.inspect(v2Manifest);

        assertThat(request.currentTrustLevel()).isEqualTo(PluginTrustLevel.UNTRUSTED);
        assertThat(request.requestedPermissions()).containsExactly(PluginPermission.NETWORK_ACCESS);
        assertThat(manager.status("device.version-reset")).get().satisfies(status -> {
            assertThat(status.state()).isEqualTo(PluginState.DISABLED);
            assertThat(status.trustLevel()).isEqualTo(PluginTrustLevel.UNTRUSTED);
            assertThat(status.approvedPermissions()).isEmpty();
        });
    }

    @Test
    void successfulInvocationReturnsValueThroughManagedBoundary() {
        PluginEntrypoint plugin = devicePlugin("device.safe", Set.of(), false);
        PluginManager manager = new PluginManager(new PluginLoader(Set.of()));
        manager.enable(plugin, PluginApproval.trusted(plugin.manifest()));

        PluginInvocationResult<String> result = manager.invoke(
                "device.safe", PluginService.DEVICE_PROVIDER, DeviceProvider.class, DeviceProvider::id);

        assertThat(result.outcome()).isEqualTo(PluginInvocationResult.Outcome.SUCCESS);
        assertThat(result.value()).isEqualTo("device.safe");
    }

    private static PluginEntrypoint devicePlugin(String id, Set<PluginPermission> permissions, boolean crash) {
        PluginManifest manifest = new PluginManifest(id, id, "1.0.0", PluginApiRange.currentMajor(),
                Set.of(PluginService.DEVICE_PROVIDER), Set.of(), permissions);
        DeviceProvider provider = new DeviceProvider() {
            public String id() { return id; }
            public Optional<DeviceProfile> detect(Path mountRoot) {
                if (crash) throw new IllegalStateException("plugin boom");
                return Optional.empty();
            }
        };
        return new PluginEntrypoint() {
            public PluginManifest manifest() { return manifest; }
            public Map<PluginService, Object> services() { return Map.of(PluginService.DEVICE_PROVIDER, provider); }
        };
    }
}
