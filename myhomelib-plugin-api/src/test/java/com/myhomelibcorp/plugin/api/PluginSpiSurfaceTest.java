package com.myhomelibcorp.plugin.api;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PluginSpiSurfaceTest {
    @Test
    void exposesReleaseEightExtensionPointsIncludingBookConversion() {
        assertThat(Set.of(PluginService.values())).containsExactlyInAnyOrder(
                PluginService.METADATA_PROVIDER,
                PluginService.COVER_PROVIDER,
                PluginService.BOOK_IMPORTER,
                PluginService.BOOK_CONVERTER,
                PluginService.METADATA_EXTRACTOR,
                PluginService.CONTENT_EXTRACTOR,
                PluginService.EXPORT_PROVIDER,
                PluginService.TRANSLATION_PROVIDER,
                PluginService.DICTIONARY_PROVIDER,
                PluginService.DEVICE_PROVIDER,
                PluginService.AI_PROVIDER
        );
    }

    @Test
    void everyServiceIdentifierTargetsAPublicInterfaceContract() {
        for (PluginService service : PluginService.values()) {
            assertThat(service.contractType().isInterface()).as(service.name()).isTrue();
            assertThat(service.contractType().getPackageName()).isEqualTo("com.myhomelibcorp.plugin.api");
        }
    }

    @Test
    void currentMajorRangeAcceptsCurrentApiVersion() {
        assertThat(PluginApiRange.currentMajor().supports(PluginApiVersion.CURRENT)).isTrue();
        assertThat(PluginApiVersion.CURRENT).isEqualTo(new PluginApiVersion(1, 4));
        assertThat(PluginApiRange.currentMajor().min()).isEqualTo(new PluginApiVersion(1, 0));
    }

    @Test
    void permissionSurfaceCoversNetworkAndFilesystemCapabilities() {
        assertThat(Set.of(PluginPermission.values())).containsExactlyInAnyOrder(
                PluginPermission.NETWORK_ACCESS,
                PluginPermission.FILESYSTEM_READ,
                PluginPermission.FILESYSTEM_WRITE,
                PluginPermission.EXTERNAL_PROCESS_EXECUTION
        );
    }
}
