package com.myhomelibcorp.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.extension.LocalMetadataExtractionService;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.service.PortableUserDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DesktopPluginRuntimeBridgeTest {
    @TempDir Path temp;
    private String oldDataDir;

    @AfterEach
    void restoreDataDir() {
        if (oldDataDir == null) System.clearProperty("myhomelib.dataDir");
        else System.setProperty("myhomelib.dataDir", oldDataDir);
    }

    @Test
    void legacyPluginContractsBecomeLiveAndDisappearWithoutRestart() throws Exception {
        oldDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.resolve("app-data").toString());
        RuntimeExtensionRegistry registry = new RuntimeExtensionRegistry();
        DesktopIntegrationBackend backend = new DesktopIntegrationBackend(
                new FakeSettings(), mock(PortableUserDataService.class), registry, new ObjectMapper(), java.util.List.of());

        assertThat(backend.plugins()).extracting(info -> info.pluginId()).contains("test.runtime-bridge");
        assertThat(backend.enablePlugin("test.runtime-bridge", true).success()).isTrue();

        Path device = Files.createDirectory(temp.resolve("device"));
        Files.createDirectory(device.resolve("TestReader"));
        assertThat(registry.deviceProfileDetectors()).singleElement().satisfies(detector ->
                assertThat(detector.detect(device)).get().satisfies(profile -> {
                    assertThat(profile.displayName()).isEqualTo("Тестовий рідер");
                    assertThat(profile.destinationSubfolder()).isEqualTo("Books");
                }));

        Path demo = temp.resolve("book.demo");
        Files.writeString(demo, "abc");
        LocalMetadataExtractionService metadata = new LocalMetadataExtractionService(registry);
        assertThat(metadata.extract(demo)).singleElement().satisfies(result ->
                assertThat(result.values()).containsEntry("title", "Тестова назва"));

        assertThat(registry.bookConverters()).anySatisfy(converter ->
                assertThat(converter.capabilities()).anySatisfy(capability ->
                        assertThat(capability.targetFormat()).isEqualTo("epub")));

        assertThat(backend.disablePlugin("test.runtime-bridge").success()).isTrue();
        assertThat(registry.deviceProfileDetectors()).isEmpty();
        assertThat(registry.localMetadataExtractors()).isEmpty();
        assertThat(registry.bookConverters()).isEmpty();
    }

    private static final class FakeSettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }
}
