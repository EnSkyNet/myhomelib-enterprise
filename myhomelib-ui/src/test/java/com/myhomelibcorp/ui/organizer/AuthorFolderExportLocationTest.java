package com.myhomelibcorp.ui.organizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AuthorFolderExportLocationTest {
    @TempDir Path temporary;

    @Test void allowsReportOutsideLibraryButRejectsLibraryRootAndCatalog() throws Exception {
        Path library = Files.createDirectory(temporary.resolve("library"));
        Path catalog = Files.createFile(temporary.resolve("catalog.sqlite"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        assertThat(AuthorFolderExportLocation.safelyOutsideLibrary(
                outside.resolve("authors.txt"), library, catalog)).isTrue();
        assertThat(AuthorFolderExportLocation.safelyOutsideLibrary(
                library.resolve("authors.txt"), library, catalog)).isFalse();
        assertThat(AuthorFolderExportLocation.safelyOutsideLibrary(
                catalog, library, catalog)).isFalse();
        assertThat(AuthorFolderExportLocation.safelyOutsideLibrary(
                temporary.resolve("missing").resolve("authors.txt"), library, catalog)).isFalse();
    }

    @Test void rejectsSymlinkAliasToLibraryAndExistingSymlinkOutput() throws Exception {
        Path library = Files.createDirectory(temporary.resolve("library"));
        Path catalog = Files.createFile(temporary.resolve("catalog.sqlite"));
        Path alias = temporary.resolve("library-alias");
        try {
            Files.createSymbolicLink(alias, library);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException notSupported) {
            assumeTrue(false, "This filesystem does not support symlinks");
        }
        assertThat(AuthorFolderExportLocation.safelyOutsideLibrary(
                alias.resolve("authors.txt"), library, catalog)).isFalse();
        Path outputAlias = temporary.resolve("catalog-alias.txt");
        Files.createSymbolicLink(outputAlias, catalog);
        assertThat(AuthorFolderExportLocation.safelyOutsideLibrary(
                outputAlias, library, catalog)).isFalse();
    }
}
