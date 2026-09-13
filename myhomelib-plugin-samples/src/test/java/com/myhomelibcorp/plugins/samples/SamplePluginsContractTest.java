package com.myhomelibcorp.plugins.samples;

import com.myhomelibcorp.plugin.api.PluginManifest;
import com.myhomelibcorp.plugin.api.PluginPermission;
import com.myhomelibcorp.plugin.api.PluginTestHarness;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SamplePluginsContractTest {
    @Test
    void everyServiceLoaderSamplePassesThePublicSdkHarness() {
        Map<String, PluginManifest> manifests = PluginTestHarness
                .verifyServiceLoader(getClass().getClassLoader())
                .stream()
                .collect(Collectors.toMap(PluginManifest::pluginId, Function.identity()));

        assertThat(manifests).containsOnlyKeys(
                "sample.sdk.dictionary",
                "sample.sdk.metadata",
                "sample.sdk.export"
        );
        assertThat(manifests.get("sample.sdk.dictionary").permissions()).isEmpty();
        assertThat(manifests.get("sample.sdk.metadata").permissions())
                .containsExactly(PluginPermission.NETWORK_ACCESS);
        assertThat(manifests.get("sample.sdk.export").permissions())
                .containsExactlyInAnyOrder(
                        PluginPermission.FILESYSTEM_READ,
                        PluginPermission.FILESYSTEM_WRITE
                );
    }

    @Test
    void samplesDeclareDistinctPluginIds() {
        Set<String> ids = Set.of(
                new SampleDictionaryPlugin().manifest().pluginId(),
                new SampleMetadataPlugin().manifest().pluginId(),
                new SampleExportPlugin().manifest().pluginId()
        );
        assertThat(ids).hasSize(3);
    }
}
