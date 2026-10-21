package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.util.SafeFilePublication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
                if (name.endsWith(".db") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
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

    record Preview(long databaseCount, List<String> databaseNames,
                   boolean searchIndex, boolean covers, boolean portableUserData) { }
}
