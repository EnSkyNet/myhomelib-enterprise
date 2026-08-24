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
    public static Path configDir() { return dataDir().resolve("config"); }
    public static Path downloadsDir() { return dataDir().resolve("downloads"); }
    public static Path cacheDir() { return dataDir().resolve("cache"); }
    public static Path logsDir() { return dataDir().resolve("logs"); }
    public static Path helpDir() { return launchDir().resolve("help"); }

    /** Set Spring-compatible system properties before the context is created. */
    public static void configureSystemProperties() {
        try {
            Files.createDirectories(dataDir());
            Files.createDirectories(librariesDir());
            Files.createDirectories(configDir());
            Files.createDirectories(downloadsDir());
            Files.createDirectories(cacheDir());
            Files.createDirectories(logsDir());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create MyHomeLib data directories: " + dataDir(), e);
        }
        System.setProperty("app.metadata.db-path", metadataDb().toString());
        System.setProperty("app.search.index-path", searchIndexDir().toString());
        System.setProperty("myhomelib.portable.active", Boolean.toString(portableMode()));
        System.setProperty("myhomelib.logDir", logsDir().toString());
    }
}
