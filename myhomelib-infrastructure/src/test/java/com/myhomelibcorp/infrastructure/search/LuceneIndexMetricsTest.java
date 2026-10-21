package com.myhomelibcorp.infrastructure.search;

import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneIndexMetricsTest {
    @TempDir Path directory;

    @Test
    void countsOnlyFlatIndexFilesAndIgnoresUnrelatedNestedData() throws Exception {
        Files.write(directory.resolve("_0.cfe"), new byte[7]);
        Path unrelated = Files.createDirectory(directory.resolve("foreign"));
        Files.write(unrelated.resolve("large-private-file"), new byte[120_000]);

        try (FSDirectory index = FSDirectory.open(directory)) {
            assertThat(LuceneIndexMetrics.indexSizeBytes(index)).isEqualTo(7);
        }
    }

    @Test
    void doesNotFollowSymlinksToFilesOutsideTheIndex() throws Exception {
        Path indexRoot = Files.createDirectory(directory.resolve("index"));
        Files.write(indexRoot.resolve("_1.cfs"), new byte[11]);
        Path outside = Files.write(directory.resolve("unrelated-data.bin"), new byte[30_000]);
        try {
            Files.createSymbolicLink(indexRoot.resolve("foreign-link"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            Assumptions.assumeTrue(false, "Symbolic links unavailable on this runner");
        }
        try (FSDirectory index = FSDirectory.open(indexRoot)) {
            assertThat(LuceneIndexMetrics.indexSizeBytes(index)).isEqualTo(11);
        }
    }

    @Test
    void emptyIndexDirectoryReportsZeroBytes() throws Exception {
        try (FSDirectory index = FSDirectory.open(directory)) {
            assertThat(LuceneIndexMetrics.indexSizeBytes(index)).isZero();
        }
    }

    @Test
    void ignoresSubdirectoriesRegardlessOfHowManyFilesTheyContain() throws Exception {
        Path nested = Files.createDirectory(directory.resolve("nested"));
        for (int i = 0; i < 100; i++) {
            Files.write(nested.resolve("unrelated-" + i), new byte[1_024]);
        }
        Files.write(directory.resolve("segments_1"), new byte[12]);
        try (FSDirectory index = FSDirectory.open(directory)) {
            assertThat(LuceneIndexMetrics.indexSizeBytes(index)).isEqualTo(12);
        }
    }
}
