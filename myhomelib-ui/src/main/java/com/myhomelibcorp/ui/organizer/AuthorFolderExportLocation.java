package com.myhomelibcorp.ui.organizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Prevent organizer reports/selections from being saved into the actual library via symlink aliases. */
final class AuthorFolderExportLocation {
    private AuthorFolderExportLocation() { }

    static boolean safelyOutsideLibrary(Path output, Path libraryRoot, Path catalog) {
        if (output == null || libraryRoot == null || catalog == null) return false;
        try {
            Path normalized = output.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent == null || normalized.getFileName() == null || Files.isSymbolicLink(normalized)) return false;
            // Resolve existing ancestor symlinks, not merely the string supplied by FileChooser.
            Path realTarget = parent.toRealPath().resolve(normalized.getFileName()).normalize();
            return !realTarget.startsWith(libraryRoot.toRealPath())
                    && !realTarget.equals(catalog.toRealPath());
        } catch (IOException | RuntimeException unsafePath) {
            // Inaccessible or uncertain destinations must never be accepted.
            return false;
        }
    }
}
