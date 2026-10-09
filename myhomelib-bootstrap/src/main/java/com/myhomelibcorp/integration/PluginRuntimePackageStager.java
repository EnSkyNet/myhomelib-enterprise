package com.myhomelibcorp.integration;

import com.myhomelibcorp.application.util.SafeFilePublication;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Freezes verified installed plugin bytes into an application-owned private runtime workspace.
 * URLClassLoader may reopen its URL lazily, so loading directly from the mutable installed path
 * would leave a hash-check -> classload TOCTOU gap.
 */
final class PluginRuntimePackageStager {
    private final Path pluginDir;

    PluginRuntimePackageStager(Path pluginDir) {
        this.pluginDir = Objects.requireNonNull(pluginDir, "pluginDir").toAbsolutePath().normalize();
    }

    RuntimePackage stageVerified(PluginPackageDescriptor pkg) throws IOException {
        Objects.requireNonNull(pkg, "pkg");
        Path safeJar = SafeFilePublication.requireSafeRegularFile(pkg.jar(), "Plugin executable package");
        Path workspace = SafeFilePublication.createPrivateWorkspace(
                pluginDir, ".plugin-runtime-", "Plugin runtime workspace");
        Path stagedJar = workspace.resolve("plugin.jar");
        boolean success = false;
        try {
            try (InputStream in = Files.newInputStream(
                    safeJar, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                SafeFilePublication.copyFresh(in, stagedJar, "Plugin runtime package");
            }
            String stagedHash = SafeFilePublication.sha256(stagedJar, "Plugin runtime package verification");
            if (!pkg.sha256().equalsIgnoreCase(stagedHash)) {
                throw new PackageChangedException(
                        "JAR плагіна змінено після перевірки; повторно перевірте та схваліть пакет");
            }
            success = true;
            return new RuntimePackage(workspace, stagedJar);
        } finally {
            if (!success) cleanup(workspace);
        }
    }

    void cleanup(Path workspace) throws IOException {
        SafeFilePublication.cleanupPrivateWorkspace(workspace);
    }

    record RuntimePackage(Path workspace, Path jar) { }

    static final class PackageChangedException extends SecurityException {
        PackageChangedException(String message) {
            super(message);
        }
    }
}
