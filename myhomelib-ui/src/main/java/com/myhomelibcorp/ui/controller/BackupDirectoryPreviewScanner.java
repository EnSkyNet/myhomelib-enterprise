package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.util.SafeFilePublication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A fixed-memory preview of backup contents before the user starts a restore. */
final class BackupDirectoryPreviewScanner {
    static final int MAX_DISPLAYED_DATABASES = 32;

    private BackupDirectoryPreviewScanner() { }

    static Preview scan(Path backupPath) throws IOException {
        Path root = SafeFilePublication.requireSafeDirectoryPath(
                backupPath, "Backup selection directory");
        List<String> databases = new ArrayList<>(MAX_DISPLAYED_DATABASES);
        long databaseCount = 0;
        boolean searchIndex = false;
        boolean covers = false;
        boolean portableUserData = false;
        // Iterate lazily; never create a List of all directory entries on the JavaFX thread.
        try (var entries = Files.list(root)) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                String name = path.getFileName().toString();
                if (isDatabaseFile(path)) {
                    databaseCount++;
                    if (databases.size() < MAX_DISPLAYED_DATABASES) databases.add(name);
                } else if (name.equals("search-index")
                        && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    searchIndex = true;
                } else if (name.equals("covers")
                        && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    covers = true;
                } else if (name.equals("user-data.json")
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    portableUserData = true;
                }
            }
        }
        return new Preview(databaseCount, List.copyOf(databases), searchIndex, covers, portableUserData);
    }

    /** Look up the first safe SQLite file without allocating a list of every directory entry. */
    static Path firstDatabase(Path backupPath) throws IOException {
        Path root = SafeFilePublication.requireSafeDirectoryPath(
                backupPath, "Backup database scan directory");
        try (var entries = Files.list(root)) {
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                Path candidate = iterator.next();
                if (isDatabaseFile(candidate)) return candidate;
            }
        }
        return null;
    }

    /** Windows filenames are case-insensitive; symlinks and directories are never databases. */
    private static boolean isDatabaseFile(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".db")
                && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    record Preview(long databaseCount, List<String> databaseNames,
                   boolean searchIndex, boolean covers, boolean portableUserData) { }
}
