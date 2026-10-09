package com.myhomelibcorp.integration;

import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.extension.LocalMetadataExtractionService;
import com.myhomelibcorp.application.extension.RuntimeExtensionRegistry;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.service.PortableUserDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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


    @Test
    void installDoesNotOverwriteUnknownExistingPackagePath() throws Exception {
        oldDataDir = System.getProperty("myhomelib.dataDir");
        Path dataDir = temp.resolve("app-data-install");
        System.setProperty("myhomelib.dataDir", dataDir.toString());
        Path plugins = Files.createDirectories(dataDir.resolve("plugins"));
        Path foreign = plugins.resolve("test.install-1.0.0.jar");
        byte[] foreignBytes = "FOREIGN PACKAGE".getBytes(StandardCharsets.UTF_8);
        Files.write(foreign, foreignBytes);
        Path source = createPluginJar(temp.resolve("test-install.jar"), "test.install", "1.0.0");

        DesktopIntegrationBackend backend = new DesktopIntegrationBackend(
                new FakeSettings(), mock(PortableUserDataService.class), new RuntimeExtensionRegistry(),
                new ObjectMapper(), java.util.List.of());

        assertThat(backend.installPlugin(source).success()).isFalse();
        assertThat(Files.readAllBytes(foreign)).isEqualTo(foreignBytes);
    }

    @Test
    void installRefreshesLateExternalPackageBeforeReplacingSamePluginId() throws Exception {
        oldDataDir = System.getProperty("myhomelib.dataDir");
        Path dataDir = temp.resolve("app-data-late-install");
        System.setProperty("myhomelib.dataDir", dataDir.toString());
        DesktopIntegrationBackend backend = new DesktopIntegrationBackend(
                new FakeSettings(), mock(PortableUserDataService.class), new RuntimeExtensionRegistry(),
                new ObjectMapper(), java.util.List.of());

        Path plugins = Files.createDirectories(dataDir.resolve("plugins"));
        Path lateExisting = createPluginJar(plugins.resolve("test.late-install-0.9.0.jar"),
                "test.late-install", "0.9.0");
        Path source = createPluginJar(temp.resolve("test-late-install-1.0.0.jar"),
                "test.late-install", "1.0.0");

        assertThat(backend.installPlugin(source).success()).isTrue();

        var installed = backend.plugins().stream()
                .filter(info -> info.pluginId().equals("test.late-install") && info.installedPackage())
                .toList();
        assertThat(installed).singleElement().satisfies(info -> {
            assertThat(info.version()).isEqualTo("1.0.0");
            assertThat(Path.of(info.source()).getFileName().toString()).isEqualTo("test.late-install-1.0.0.jar");
        });
        assertThat(lateExisting).doesNotExist();
    }

    @Test
    void removalDoesNotDeleteInstalledPackageChangedAfterScan() throws Exception {
        oldDataDir = System.getProperty("myhomelib.dataDir");
        Path dataDir = temp.resolve("app-data-remove");
        System.setProperty("myhomelib.dataDir", dataDir.toString());
        Path source = createPluginJar(temp.resolve("test-remove.jar"), "test.remove", "1.0.0");
        DesktopIntegrationBackend backend = new DesktopIntegrationBackend(
                new FakeSettings(), mock(PortableUserDataService.class), new RuntimeExtensionRegistry(),
                new ObjectMapper(), java.util.List.of());
        assertThat(backend.installPlugin(source).success()).isTrue();
        Path installed = Path.of(backend.plugins().stream()
                .filter(info -> info.pluginId().equals("test.remove") && info.installedPackage())
                .findFirst().orElseThrow().source());
        byte[] foreignBytes = "REPLACED AFTER SCAN".getBytes(StandardCharsets.UTF_8);
        Files.write(installed, foreignBytes);

        assertThat(backend.removePlugin("test.remove").success()).isFalse();
        assertThat(Files.readAllBytes(installed)).isEqualTo(foreignBytes);
    }

    @Test
    void enablingRefusesInstalledJarChangedAfterPackageScan() throws Exception {
        oldDataDir = System.getProperty("myhomelib.dataDir");
        Path dataDir = temp.resolve("app-data-enable-tamper");
        System.setProperty("myhomelib.dataDir", dataDir.toString());
        Path source = createPluginJar(temp.resolve("test-enable-tamper.jar"), "test.enable-tamper", "1.0.0");
        FakeSettings settings = new FakeSettings();
        DesktopIntegrationBackend backend = new DesktopIntegrationBackend(
                settings, mock(PortableUserDataService.class), new RuntimeExtensionRegistry(),
                new ObjectMapper(), java.util.List.of());
        assertThat(backend.installPlugin(source).success()).isTrue();

        Path installed = Path.of(backend.plugins().stream()
                .filter(info -> info.pluginId().equals("test.enable-tamper") && info.installedPackage())
                .findFirst().orElseThrow().source());
        Files.writeString(installed, "TAMPERED AFTER SCAN", StandardCharsets.UTF_8);

        var result = backend.enablePlugin("test.enable-tamper", true);

        assertThat(result.success()).isFalse();
        assertThat(result.userMessage().technicalDetail()).contains("змінено після перевірки");
        assertThat(settings.findByPrefix("plugins.approved.test.enable-tamper.")).isEmpty();
    }

    @Test
    void failedExternalPluginEnableCleansPrivateRuntimeCopy() throws Exception {
        oldDataDir = System.getProperty("myhomelib.dataDir");
        Path dataDir = temp.resolve("app-data-runtime-cleanup");
        System.setProperty("myhomelib.dataDir", dataDir.toString());
        Path source = createPluginJar(temp.resolve("test-runtime-cleanup.jar"),
                "test.runtime-cleanup", "1.0.0");
        DesktopIntegrationBackend backend = new DesktopIntegrationBackend(
                new FakeSettings(), mock(PortableUserDataService.class), new RuntimeExtensionRegistry(),
                new ObjectMapper(), java.util.List.of());
        assertThat(backend.installPlugin(source).success()).isTrue();

        // This package deliberately has no ServiceLoader entrypoint, so enable must roll back
        // both the classloader and the private frozen runtime package.
        assertThat(backend.enablePlugin("test.runtime-cleanup", true).success()).isFalse();

        Path pluginDir = dataDir.resolve("plugins");
        try (var children = Files.list(pluginDir)) {
            assertThat(children.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.startsWith(".plugin-runtime-"));
        }
    }

    @Test
    void pluginExportNeverReceivesOrOverwritesSymlinkedFinalTarget() throws Exception {
        oldDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.resolve("app-data-export").toString());
        RuntimeExtensionRegistry registry = new RuntimeExtensionRegistry();
        DesktopIntegrationBackend backend = new DesktopIntegrationBackend(
                new FakeSettings(), mock(PortableUserDataService.class), registry, new ObjectMapper(), java.util.List.of());
        assertThat(backend.enablePlugin("test.runtime-bridge", true).success()).isTrue();
        BookConverter converter = registry.bookConverters().stream()
                .filter(value -> value.id().contains("test.runtime-bridge") && value.id().contains("test-export"))
                .findFirst().orElseThrow();
        Path foreign = temp.resolve("foreign.epub");
        Files.writeString(foreign, "FOREIGN");
        Path target = temp.resolve("plugin-target.epub");
        try {
            Files.createSymbolicLink(target, foreign);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            return;
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.convert(
                        com.myhomelibcorp.domain.model.book.Book.builder().title("Plugin export").build(),
                        new ByteArrayInputStream("BOOK".getBytes(StandardCharsets.UTF_8)), target))
                .isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(foreign)).isEqualTo("FOREIGN");
    }

    private static Path createPluginJar(Path jar, String pluginId, String version) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("pluginId", pluginId);
        properties.setProperty("displayName", "Test package");
        properties.setProperty("version", version);
        properties.setProperty("apiMin", "1.4");
        properties.setProperty("apiMax", "1.99");
        properties.setProperty("services", "");
        properties.setProperty("overrides", "");
        properties.setProperty("permissions", "");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/myhomelib-plugin.properties"));
            properties.store(zip, null);
            zip.closeEntry();
        }
        return jar;
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
