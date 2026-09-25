package com.myhomelibcorp.converter.toolchain.discovery;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConverterToolRegistry {
    private final ConverterToolLocator locator;
    private final ConverterToolProbe probe;
    private final Map<String, ToolInstallation> cache = new ConcurrentHashMap<>();

    public ConverterToolRegistry(ConverterToolLocator locator, ConverterToolProbe probe) {
        this.locator = locator;
        this.probe = probe;
    }

    public ToolInstallation fbc() { return get("fbc", "fbc", List.of("--version")); }
    public ToolInstallation pandoc() { return get("pandoc", "pandoc", List.of("--version")); }
    public ToolInstallation libmobi() { return get("libmobi", "mobitool", List.of("-v")); }
    public ToolInstallation calibre() { return get("calibre", "ebook-convert", List.of("--version")); }

    public ToolInstallation probeFbc(String explicit) { return probeExplicit("fbc", explicit, "fbc", List.of("--version")); }
    public ToolInstallation probePandoc(String explicit) { return probeExplicit("pandoc", explicit, "pandoc", List.of("--version")); }
    public ToolInstallation probeLibmobi(String explicit) { return probeExplicit("libmobi", explicit, "mobitool", List.of("-v")); }
    public ToolInstallation probeCalibre(String explicit) { return probeExplicit("calibre", explicit, "ebook-convert", List.of("--version")); }

    public void refresh() { cache.clear(); }

    private ToolInstallation get(String id, String executable, List<String> versionArgs) {
        Path path = locator.locate(id, executable).orElse(null);
        ToolInstallation cached = cache.get(id);
        if (cached != null && Objects.equals(normalize(cached.executable()), normalize(path))) return cached;
        ToolInstallation installed = probe.probe(id, path, versionArgs);
        cache.put(id, installed);
        return installed;
    }

    private ToolInstallation probeExplicit(String id, String explicit, String executable, List<String> versionArgs) {
        if (explicit == null || explicit.isBlank()) {
            // The user-facing "Check" action must perform a fresh probe. Reusing the discovery
            // cache here can keep a stale AVAILABLE/UNAVAILABLE status after tools are added,
            // removed or replaced while the settings dialog is open.
            Path path = locator.locate(id, executable).orElse(null);
            ToolInstallation installed = probe.probe(id, path, versionArgs);
            cache.put(id, installed);
            return installed;
        }
        try {
            Path path = Path.of(stripQuotes(explicit.trim())).toAbsolutePath().normalize();
            Path safe = locator.validateExplicit(path, executable).orElse(null);
            if (safe == null) return ToolInstallation.unavailable(id, "Executable path is missing, unsafe, or has an unexpected filename");
            return probe.probe(id, safe, versionArgs);
        } catch (RuntimeException invalidPath) {
            return ToolInstallation.unavailable(id, "Invalid executable path");
        }
    }

    private String stripQuotes(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private Path normalize(Path value) { return value == null ? null : value.toAbsolutePath().normalize(); }
}
