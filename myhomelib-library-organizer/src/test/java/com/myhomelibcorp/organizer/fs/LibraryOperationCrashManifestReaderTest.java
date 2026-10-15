package com.myhomelibcorp.organizer.fs;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class LibraryOperationCrashManifestReaderTest {
    @TempDir Path tempDir;

    @Test
    void rejectsManifestReachedThroughSymlinkedParent() throws Exception {
        Path real = Files.createDirectory(tempDir.resolve("real"));
        Path manifest = real.resolve("operation.json");
        Files.writeString(manifest, validManifest());
        Path link = tempDir.resolve("linked");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException unsupported) {
            Assumptions.abort("Symbolic links are unavailable in this test environment");
        }

        assertThatThrownBy(() -> new LibraryOperationCrashManifestReader().read(link.resolve("operation.json")))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void rejectsOversizedManifestBeforeJsonParsing() throws Exception {
        Path manifest = tempDir.resolve("oversized.json");
        Files.writeString(manifest, "x".repeat(4 * 1024 * 1024 + 1));

        assertThatThrownBy(() -> new LibraryOperationCrashManifestReader().read(manifest))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("exceeds safety limit");
    }

    @Test
    void readsLegacySchemaTwoWithoutSupplementaryEventLog() throws Exception {
        Path manifest = tempDir.resolve("legacy.json");
        Files.writeString(manifest, validManifest());
        var snapshot = new LibraryOperationCrashManifestReader().read(manifest);
        assertThat(snapshot.schemaVersion()).isEqualTo(2);
        assertThat(snapshot.items()).isEmpty();
    }

    private static String validManifest() {
        return """
                {
                  "schemaVersion": 2,
                  "operationId": "11111111-1111-1111-1111-111111111111",
                  "profileId": "profile",
                  "sourceRoot": "/tmp/source",
                  "destinationRoot": "/tmp/destination",
                  "state": "PLANNED",
                  "message": "",
                  "updatedAt": "2026-09-22T00:00:00Z",
                  "items": []
                }
                """;
    }
}
