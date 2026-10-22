package com.myhomelibcorp.infrastructure.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Filesystem regressions for low-heap cleanup of incompatible Lucene indexes. */
class LuceneIndexWriterFactoryCleanupTest {
    @TempDir Path temp;

    @Test
    void deletesThousandsOfNestedIndexFilesWithoutRetainingAllPaths() throws Exception {
        Path index = Files.createDirectory(temp.resolve("index"));
        Path other = Files.writeString(temp.resolve("unrelated.txt"), "must survive");

        // More entries than a typical Lucene directory, including nested and empty
        // folders, so post-order cleanup must work without a sorted path list.
        for (int directory = 0; directory < 16; directory++) {
            Path nested = Files.createDirectories(index.resolve("segment-" + directory).resolve("nested"));
            for (int file = 0; file < 128; file++) {
                Files.writeString(nested.resolve("segment-" + file + ".tmp"), "index");
            }
        }
        Files.createDirectories(index.resolve("empty").resolve("subdirectory"));

        LuceneIndexWriterFactory.deleteDirectory(index);

        assertThat(Files.exists(index)).isFalse();
        assertThat(Files.readString(other)).isEqualTo("must survive");
        assertThatCode(() -> LuceneIndexWriterFactory.deleteDirectory(index))
                .doesNotThrowAnyException();
    }

    @Test
    void doesNotTraverseSymlinksPointingOutsideIndex() throws Exception {
        Path external = Files.createDirectory(temp.resolve("external"));
        Path sentinel = Files.writeString(external.resolve("keep.txt"), "outside-index");
        Path index = Files.createDirectory(temp.resolve("index-with-link"));
        Files.writeString(index.resolve("segment"), "index");
        Path link = index.resolve("linked-external");
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            // Creating symlinks can require special privileges on Windows runners.
            assumeTrue(false, "Symbolic links are unavailable on this runner: " + unavailable);
        }

        LuceneIndexWriterFactory.deleteDirectory(index);

        assertThat(Files.exists(index)).isFalse();
        assertThat(Files.readString(sentinel)).isEqualTo("outside-index");
        assertThat(Files.isDirectory(external)).isTrue();
    }

    @Test
    void refusesToDeleteThroughASymbolicLinkRoot() throws Exception {
        Path external = Files.createDirectory(temp.resolve("external-root"));
        Path sentinel = Files.writeString(external.resolve("keep.txt"), "outside-index");
        Path rootLink = temp.resolve("linked-index");
        try {
            Files.createSymbolicLink(rootLink, external);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable on this runner: " + unavailable);
        }

        assertThatThrownBy(() -> LuceneIndexWriterFactory.deleteDirectory(rootLink))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(sentinel)).isEqualTo("outside-index");
    }
}
