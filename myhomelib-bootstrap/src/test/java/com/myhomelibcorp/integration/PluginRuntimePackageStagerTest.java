package com.myhomelibcorp.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginRuntimePackageStagerTest {
    @TempDir Path temp;

    @Test
    void freezesVerifiedBytesIntoPrivateRuntimeWorkspace() throws Exception {
        Path pluginDir = Files.createDirectories(temp.resolve("plugins"));
        Path installed = createPluginJar(pluginDir.resolve("test.freeze-1.0.0.jar"), "test.freeze", "1.0.0");
        PluginPackageDescriptor descriptor = PluginPackageInspector.inspect(installed);
        byte[] expected = Files.readAllBytes(installed);
        PluginRuntimePackageStager stager = new PluginRuntimePackageStager(pluginDir);

        PluginRuntimePackageStager.RuntimePackage runtime = stager.stageVerified(descriptor);
        try {
            assertThat(runtime.workspace()).isDirectory();
            assertThat(runtime.jar()).isRegularFile();
            assertThat(runtime.jar()).isNotEqualTo(installed);
            assertThat(Files.readAllBytes(runtime.jar())).isEqualTo(expected);
        } finally {
            stager.cleanup(runtime.workspace());
        }
        assertThat(runtime.workspace()).doesNotExist();
    }

    @Test
    void rejectsChangedInstalledBytesAndCleansFailedRuntimeWorkspace() throws Exception {
        Path pluginDir = Files.createDirectories(temp.resolve("plugins"));
        Path installed = createPluginJar(pluginDir.resolve("test.changed-1.0.0.jar"), "test.changed", "1.0.0");
        PluginPackageDescriptor descriptor = PluginPackageInspector.inspect(installed);
        Files.writeString(installed, "CHANGED AFTER INSPECTION", StandardCharsets.UTF_8);
        PluginRuntimePackageStager stager = new PluginRuntimePackageStager(pluginDir);

        assertThatThrownBy(() -> stager.stageVerified(descriptor))
                .isInstanceOf(PluginRuntimePackageStager.PackageChangedException.class)
                .hasMessageContaining("змінено після перевірки");
        try (var children = Files.list(pluginDir)) {
            assertThat(children.map(path -> path.getFileName().toString()).toList())
                    .noneMatch(name -> name.startsWith(".plugin-runtime-"));
        }
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
}
