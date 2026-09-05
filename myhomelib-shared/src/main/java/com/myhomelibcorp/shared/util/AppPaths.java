package com.myhomelibcorp.shared.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Centralized application paths with MyHomeLib-compatible portable mode.
 * Portable mode is enabled when myhomelib2.ini exists next to the launcher,
 * or with -Dmyhomelib.portable=true.
 */
public final class AppPaths {
    private static final String PORTABLE_MARKER = "myhomelib2.ini";

    private AppPaths() {}

    public static Path launchDir() {
        String explicit = System.getProperty("myhomelib.launchDir");
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }

        // jpackage native launchers do not guarantee that user.dir points at the app image.
        // Portable-mode semantics are defined relative to the launcher, so when jpackage's
        // built-in version marker is present prefer the actual process executable directory.
        String packagedVersion = System.getProperty("jpackage.app-version");
        if (packagedVersion != null && !packagedVersion.isBlank()) {
            try {
                String command = ProcessHandle.current().info().command().orElse(null);
                if (command != null && !command.isBlank()) {
                    Path executable = Paths.get(command).toAbsolutePath().normalize();
                    Path parent = executable.getParent();
                    if (parent != null) return parent;
                }
            } catch (RuntimeException ignored) {
                // Fall through to the long-standing user.dir behavior when the host cannot
                // expose a usable executable path (restricted process metadata, invalid path).
            }
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    public static boolean portableMode() {
        if (Boolean.parseBoolean(System.getProperty("myhomelib.portable", "false"))) return true;
        return Files.isRegularFile(launchDir().resolve(PORTABLE_MARKER));
    }

    public static Path dataDir() {
        String explicit = System.getProperty("myhomelib.dataDir");
        if (explicit != null && !explicit.isBlank()) return Paths.get(explicit).toAbsolutePath().normalize();
        return portableMode()
                ? launchDir().resolve("data").toAbsolutePath().normalize()
                : Paths.get(System.getProperty("user.home"), ".myhomelibcorp").toAbsolutePath().normalize();
    }

    public static Path metadataDb() { return dataDir().resolve("meta.db"); }
    public static Path librariesDir() { return dataDir().resolve("libraries"); }
    public static Path searchIndexDir() { return dataDir().resolve("search-index"); }

    /** Per-collection Lucene index directory. Collection ids are restricted to filesystem-safe characters. */
    public static Path collectionSearchIndexDir(String collectionId) {
        return searchIndexDir().resolve(safePathSegment(collectionId));
    }

    /** Freshness marker written only after a successful Lucene commit. */
    public static Path collectionSearchIndexStateFile(String collectionId) {
        return searchIndexDir().resolve(safePathSegment(collectionId) + ".state");
    }
    public static Path configDir() { return dataDir().resolve("config"); }
    public static Path downloadsDir() { return dataDir().resolve("downloads"); }
    public static Path cacheDir() { return dataDir().resolve("cache"); }
    public static Path logsDir() { return dataDir().resolve("logs"); }
    public static Path backupsDir() { return dataDir().resolve("backups"); }

    /** Durable files used to roll back an online catalog update after an abrupt process termination. */
    public static Path catalogUpdateRecoveryDir() { return cacheDir().resolve("catalog-update-recovery"); }

    /** Pre-update SQLite checkpoint for the collection. Kept while a catalog update is in progress. */
    public static Path catalogUpdateRecoveryCheckpoint(String collectionId) {
        return catalogUpdateRecoveryDir().resolve(safePathSegment(collectionId) + ".checkpoint.db");
    }

    /** Commit marker: its presence means the corresponding catalog update did not finish cleanly. */
    public static Path catalogUpdateRecoveryMarker(String collectionId) {
        return catalogUpdateRecoveryDir().resolve(safePathSegment(collectionId) + ".pending");
    }

    public static Path helpDir() { return launchDir().resolve("help"); }

    private static String safePathSegment(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("collectionId must not be blank");
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Set Spring-compatible system properties before the context is created. */
    public static void configureSystemProperties() {
        try {
            Files.createDirectories(dataDir());
            Files.createDirectories(librariesDir());
            Files.createDirectories(configDir());
            Files.createDirectories(downloadsDir());
            Files.createDirectories(cacheDir());
            Files.createDirectories(logsDir());
            Files.createDirectories(backupsDir());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create MyHomeLib data directories: " + dataDir(), e);
        }
        System.setProperty("app.metadata.db-path", metadataDb().toString());
        System.setProperty("myhomelib.portable.active", Boolean.toString(portableMode()));
        System.setProperty("myhomelib.logDir", logsDir().toString());
    }
}
