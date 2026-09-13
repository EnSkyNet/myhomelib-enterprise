package com.myhomelibcorp.plugins.samples;

import com.myhomelibcorp.plugin.api.ExportProvider;
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
import java.util.Map;
import java.util.Set;

/** File-copy export sample demonstrating explicit filesystem read/write capabilities. */
public final class SampleExportPlugin implements PluginEntrypoint {
    private static final ExportProvider PROVIDER = new ExportProvider() {
        @Override public String id() { return "sample-export"; }
        @Override public boolean supports(String targetFormat) { return "copy".equalsIgnoreCase(targetFormat); }

        @Override
        public Path export(Path source, Path destination, String targetFormat, PluginOperationContext context)
                throws IOException {
            if (!supports(targetFormat)) throw new IllegalArgumentException("Only the 'copy' target is supported");
            if (context.isCancelled()) throw new IOException("Export cancelled");
            Path parent = destination.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path result = Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            context.reportProgress(1, 1);
            return result;
        }
    };

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(
                "sample.sdk.export",
                "SDK Sample Export",
                "1.0.0",
                PluginApiRange.currentMajor(),
                Set.of(PluginService.EXPORT_PROVIDER),
                Set.of(),
                Set.of(PluginPermission.FILESYSTEM_READ, PluginPermission.FILESYSTEM_WRITE)
        );
    }

    @Override
    public Map<PluginService, Object> services() {
        return Map.of(PluginService.EXPORT_PROVIDER, PROVIDER);
    }
}
