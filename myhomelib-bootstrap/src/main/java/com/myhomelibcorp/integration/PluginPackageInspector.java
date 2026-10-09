package com.myhomelibcorp.integration;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.infrastructure.archive.SafeZipArchive;
import com.myhomelibcorp.plugin.api.PluginApiRange;
import com.myhomelibcorp.plugin.api.PluginApiVersion;
import com.myhomelibcorp.plugin.api.PluginManifest;
import com.myhomelibcorp.plugin.api.PluginPermission;
import com.myhomelibcorp.plugin.api.PluginService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

import static com.myhomelibcorp.integration.DesktopIntegrationSupport.parseApiVersion;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.parseEnumSet;
import static com.myhomelibcorp.integration.DesktopIntegrationSupport.requiredProperty;

/** Reads and validates external plugin package metadata from stable, bounded JAR bytes. */
final class PluginPackageInspector {
    private static final String MANIFEST = "META-INF/myhomelib-plugin.properties";
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;

    private PluginPackageInspector() { }

    static PluginPackageDescriptor inspect(Path jar) throws IOException {
        Path safeJar = SafeFilePublication.requireSafeRegularFile(jar, "Plugin JAR");
        try (SafeZipArchive archive = SafeZipArchive.open(safeJar, StandardCharsets.UTF_8, "Plugin JAR")) {
            // Hash and manifest describe the same already-open bytes. This closes the approval
            // identity gap that would exist if the pathname were opened once for each operation.
            String hash = archive.sha256("Plugin package");
            PluginManifest manifest = readManifest(archive);
            return new PluginPackageDescriptor(jar.toAbsolutePath().normalize(), manifest, hash);
        }
    }

    static PluginManifest readManifest(Path jar) throws IOException {
        Path safeJar = SafeFilePublication.requireSafeRegularFile(jar, "Plugin JAR");
        try (SafeZipArchive archive = SafeZipArchive.open(safeJar, StandardCharsets.UTF_8, "Plugin JAR")) {
            return readManifest(archive);
        }
    }

    private static PluginManifest readManifest(SafeZipArchive archive) throws IOException {
        var zip = archive.zip();
        var entry = zip.getEntry(MANIFEST);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("У JAR відсутній " + MANIFEST);
        }
        if (entry.getSize() > MAX_MANIFEST_BYTES) throw new IOException("Маніфест плагіна завеликий");

        Properties properties = new Properties();
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Маніфест плагіна завеликий");
            properties.load(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
        }

        String id = requiredProperty(properties, "pluginId");
        String name = requiredProperty(properties, "displayName");
        String version = requiredProperty(properties, "version");
        PluginApiVersion min = parseApiVersion(requiredProperty(properties, "apiMin"));
        PluginApiVersion max = parseApiVersion(requiredProperty(properties, "apiMax"));
        Set<PluginService> services = parseEnumSet(properties.getProperty("services", ""), PluginService.class);
        Set<PluginService> overrides = parseEnumSet(properties.getProperty("overrides", ""), PluginService.class);
        Set<PluginPermission> permissions = parseEnumSet(properties.getProperty("permissions", ""), PluginPermission.class);
        return new PluginManifest(id, name, version, new PluginApiRange(min, max), services, overrides, permissions);
    }
}

record PluginPackageDescriptor(Path jar, PluginManifest manifest, String sha256) { }
