package com.myhomelibcorp.plugin.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginTestHarnessTest {
    @Test
    void verifiesAValidPluginWithoutCreatingRuntimeTrustState() {
        PluginEntrypoint plugin = devicePlugin("sdk.valid", PluginApiRange.currentMajor(), false);

        PluginManifest manifest = PluginTestHarness.verify(plugin);

        assertThat(manifest.pluginId()).isEqualTo("sdk.valid");
        assertThat(manifest.permissions()).containsExactly(PluginPermission.FILESYSTEM_READ);
    }

    @Test
    void appliesTheSameCoreOverrideRuleAsTheHostLoader() {
        PluginEntrypoint plugin = devicePlugin("sdk.hidden-override", PluginApiRange.currentMajor(), false);

        assertThatThrownBy(() -> PluginTestHarness.verify(plugin, Set.of(PluginService.DEVICE_PROVIDER)))
                .isInstanceOf(PluginLoadException.class)
                .hasMessageContaining("without explicit declaration");
    }

    @Test
    void serviceLoaderHarnessValidatesTheSampleClasspath() {
        assertThat(PluginTestHarness.verifyServiceLoader(getClass().getClassLoader()))
                .extracting(PluginManifest::pluginId)
                .contains("sample.dictionary");
    }

    private static PluginEntrypoint devicePlugin(String id, PluginApiRange range, boolean override) {
        Set<PluginService> overrides = override ? Set.of(PluginService.DEVICE_PROVIDER) : Set.of();
        PluginManifest manifest = new PluginManifest(
                id,
                id,
                "1.0.0",
                range,
                Set.of(PluginService.DEVICE_PROVIDER),
                overrides,
                Set.of(PluginPermission.FILESYSTEM_READ)
        );
        DeviceProvider provider = new DeviceProvider() {
            @Override public String id() { return id; }
            @Override public Optional<DeviceProfile> detect(Path mountRoot) { return Optional.empty(); }
        };
        return new PluginEntrypoint() {
            @Override public PluginManifest manifest() { return manifest; }
            @Override public Map<PluginService, Object> services() {
                return Map.of(PluginService.DEVICE_PROVIDER, provider);
            }
        };
    }
}
