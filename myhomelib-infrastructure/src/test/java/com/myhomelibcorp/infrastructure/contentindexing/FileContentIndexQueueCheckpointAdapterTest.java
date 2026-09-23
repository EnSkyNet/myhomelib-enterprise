package com.myhomelibcorp.infrastructure.contentindexing;

import com.myhomelibcorp.application.content.indexing.ContentIndexQueueCheckpoint;
import com.myhomelibcorp.application.content.indexing.ContentIndexingPriority;
import com.myhomelibcorp.application.content.indexing.ContentIndexingTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileContentIndexQueueCheckpointAdapterTest {
    @TempDir Path temp;

    @Test
    void checkpointSurvivesNewAdapterInstanceAndCanBeCleared() {
        String previous = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.toString());
        try {
            var task = new ContentIndexingTask("t1", "collection:1", "b1", "a1", temp.resolve("book.txt").toString(),
                    "txt", ContentIndexingPriority.HIGH, Instant.parse("2026-09-12T12:00:00Z"));
            var expected = new ContentIndexQueueCheckpoint("collection:1", true, true, List.of(task));
            new FileContentIndexQueueCheckpointAdapter().save(expected);

            var loaded = new FileContentIndexQueueCheckpointAdapter().load("collection:1");
            assertThat(loaded).contains(expected);
            assertThat(loaded.orElseThrow().rebuildRequired()).isTrue();

            new FileContentIndexQueueCheckpointAdapter().clear("collection:1");
            assertThat(new FileContentIndexQueueCheckpointAdapter().load("collection:1")).isEmpty();
        } finally {
            if (previous == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", previous);
        }
    }

    @Test
    void unknownFutureCheckpointVersionFailsClosedAndSaveDoesNotOverwriteIt() throws Exception {
        String previous = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.toString());
        try {
            Path checkpoint = com.myhomelibcorp.shared.util.AppPaths.contentIndexQueueStateFile("collection:1");
            Files.createDirectories(checkpoint.getParent());
            String future = "version=999\ncollectionId=collection:1\npaused=false\ntask.count=0\n";
            Files.writeString(checkpoint, future, StandardCharsets.UTF_8);
            var adapter = new FileContentIndexQueueCheckpointAdapter();

            assertThatThrownBy(() -> adapter.load("collection:1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot load content-index queue checkpoint");

            var replacement = new ContentIndexQueueCheckpoint("collection:1", false, false, List.of());
            assertThatThrownBy(() -> adapter.save(replacement))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot save content-index queue checkpoint");
            assertThat(Files.readString(checkpoint, StandardCharsets.UTF_8)).isEqualTo(future);
        } finally {
            if (previous == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", previous);
        }
    }

    @Test
    void symlinkedCheckpointIsRejectedWithoutTouchingExternalFile() throws Exception {
        String previous = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.toString());
        try {
            Path checkpoint = com.myhomelibcorp.shared.util.AppPaths.contentIndexQueueStateFile("collection:1");
            Files.createDirectories(checkpoint.getParent());
            Path outside = temp.resolve("outside.properties");
            Files.writeString(outside, "FOREIGN", StandardCharsets.UTF_8);
            Assumptions.assumeTrue(canCreateSymlink(checkpoint, outside));

            var task = new ContentIndexingTask("t1", "collection:1", "b1", "a1", temp.resolve("book.txt").toString(),
                    "txt", ContentIndexingPriority.HIGH, Instant.parse("2026-09-12T12:00:00Z"));
            var value = new ContentIndexQueueCheckpoint("collection:1", false, false, List.of(task));
            var adapter = new FileContentIndexQueueCheckpointAdapter();

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.load("collection:1"))
                    .isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.save(value))
                    .isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.clear("collection:1"))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(Files.readString(outside, StandardCharsets.UTF_8)).isEqualTo("FOREIGN");
            assertThat(Files.isSymbolicLink(checkpoint)).isTrue();
        } finally {
            if (previous == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", previous);
        }
    }

    private static boolean canCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
