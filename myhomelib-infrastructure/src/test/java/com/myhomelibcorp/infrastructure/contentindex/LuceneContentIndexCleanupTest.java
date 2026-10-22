package com.myhomelibcorp.infrastructure.contentindex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Cleanup must keep heap bounded and preserve evidence if any unsafe filesystem entry exists. */
class LuceneContentIndexCleanupTest {
    @TempDir Path temp;

    @Test
    void deletesThousandsOfNestedContentIndexFilesAndEmptyDirectories() throws Exception {
        Path root = Files.createDirectory(temp.resolve("content-index-old"));
        Path unrelated = Files.writeString(temp.resolve("unrelated.txt"), "keep");
        for (int directory = 0; directory < 12; directory++) {
            Path nested = Files.createDirectories(root.resolve("segments-" + directory).resolve("nested"));
            for (int file = 0; file < 256; file++) {
                Files.writeString(nested.resolve("segment-" + file + ".tmp"), "lucene");
            }
        }
        Files.createDirectories(root.resolve("empty").resolve("nested"));

        LuceneContentIndexService.deleteTreeQuietly(root);
        LuceneContentIndexService.deleteTreeQuietly(root); // Absent cleanup is idempotent.

        assertThat(Files.exists(root, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.readString(unrelated)).isEqualTo("keep");
    }

    @Test
    void symlinkPreflightPreservesEveryOriginalIndexFileAndExternalTarget() throws Exception {
        Path external = Files.createDirectory(temp.resolve("external"));
        Path externalSentinel = Files.writeString(external.resolve("keep.txt"), "external");
        Path root = Files.createDirectory(temp.resolve("content-index-with-link"));
        Path original = Files.writeString(root.resolve("existing-segment"), "preserve");
        Path nested = Files.createDirectory(root.resolve("nested"));
        Path nestedOriginal = Files.writeString(nested.resolve("other-segment"), "preserve");
        Path link = root.resolve("external-link");
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symlink creation unavailable on Windows runner: " + unavailable);
        }

        LuceneContentIndexService.deleteTreeQuietly(root);

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readString(original)).isEqualTo("preserve");
        assertThat(Files.readString(nestedOriginal)).isEqualTo("preserve");
        assertThat(Files.readString(externalSentinel)).isEqualTo("external");
    }

    @Test
    void refusesLinkedRootWithoutDeletingExternalFiles() throws Exception {
        Path external = Files.createDirectory(temp.resolve("external-root"));
        Path sentinel = Files.writeString(external.resolve("keep.txt"), "external");
        Path rootLink = temp.resolve("linked-content-index");
        try {
            Files.createSymbolicLink(rootLink, external);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symlink creation unavailable on Windows runner: " + unavailable);
        }

        LuceneContentIndexService.deleteTreeQuietly(rootLink);

        assertThat(Files.isSymbolicLink(rootLink)).isTrue();
        assertThat(Files.readString(sentinel)).isEqualTo("external");
    }

    @Test
    void regularFileIsNotMistakenForAnIndexDirectory() throws Exception {
        Path file = Files.writeString(temp.resolve("content-index-file"), "evidence");
        LuceneContentIndexService.deleteTreeQuietly(file);
        assertThat(Files.readString(file)).isEqualTo("evidence");
    }
}
