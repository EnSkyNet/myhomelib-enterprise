package com.myhomelibcorp.integration;

import com.myhomelibcorp.plugin.api.DeviceProfile;
import com.myhomelibcorp.plugin.api.DeviceProvider;
import com.myhomelibcorp.plugin.api.ExportProvider;
import com.myhomelibcorp.plugin.api.MetadataExtractor;
import com.myhomelibcorp.plugin.api.PluginApiRange;
import com.myhomelibcorp.plugin.api.PluginEntrypoint;
import com.myhomelibcorp.plugin.api.PluginManifest;
import com.myhomelibcorp.plugin.api.PluginOperationContext;
import com.myhomelibcorp.plugin.api.PluginPermission;
import com.myhomelibcorp.plugin.api.PluginService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Test-only bundled plugin proving that legacy SPI contracts are bridged into live desktop services. */
public final class RuntimeBridgeTestPlugin implements PluginEntrypoint {
    private final DeviceProvider device = new DeviceProvider() {
        @Override public String id() { return "test-device"; }
        @Override public Optional<DeviceProfile> detect(Path mountRoot) {
            if (mountRoot == null || !Files.isDirectory(mountRoot.resolve("TestReader"))) return Optional.empty();
            return Optional.of(new DeviceProfile("test-reader", "Тестовий рідер", List.of("EPUB"), Path.of("Books")));
        }
    };
    private final MetadataExtractor metadata = new MetadataExtractor() {
        @Override public String id() { return "test-metadata"; }
        @Override public boolean supports(Path source) { return source != null && source.getFileName().toString().endsWith(".demo"); }
        @Override public Map<String, String> extract(Path source, PluginOperationContext context) {
            return Map.of("title", "Тестова назва", "size", Long.toString(size(source)));
        }
        private long size(Path source) {
            try { return Files.size(source); } catch (IOException e) { return -1; }
        }
    };
    private final ExportProvider exporter = new ExportProvider() {
        @Override public String id() { return "test-export"; }
        @Override public boolean supports(String targetFormat) { return "epub".equalsIgnoreCase(targetFormat); }
        @Override public Path export(Path source, Path destination, String targetFormat, PluginOperationContext context) throws IOException {
            if (context.isCancelled()) throw new java.util.concurrent.CancellationException("cancelled");
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        }
    };

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("test.runtime-bridge", "Тестовий runtime bridge", "1.0.0",
                PluginApiRange.currentMajor(),
                Set.of(PluginService.DEVICE_PROVIDER, PluginService.METADATA_EXTRACTOR, PluginService.EXPORT_PROVIDER),
                Set.of(),
                Set.of(PluginPermission.FILESYSTEM_READ, PluginPermission.FILESYSTEM_WRITE));
    }

    @Override
    public Map<PluginService, Object> services() {
        return Map.of(
                PluginService.DEVICE_PROVIDER, device,
                PluginService.METADATA_EXTRACTOR, metadata,
                PluginService.EXPORT_PROVIDER, exporter);
    }
}
