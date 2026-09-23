package com.myhomelibcorp.application.imports.scanner;

import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class LibraryScannerPathSafetyTest {
    @TempDir Path temp;

    @Test
    void rejectsRootReachedThroughSymlinkedParentWhenSupported() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.createDirectories(outside.resolve("library"));
        Path alias = temp.resolve("alias");
        try {
            Files.createSymbolicLink(alias, outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            return;
        }

        LibraryScanner scanner = new LibraryScanner(mock(ImporterRegistry.class));
        Path rootThroughLink = alias.resolve("library");

        assertThrows(IllegalArgumentException.class,
                () -> scanner.streamSupportedFiles(rootThroughLink));
    }
}
