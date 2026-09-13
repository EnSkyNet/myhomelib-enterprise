package com.myhomelibcorp.infrastructure.contentindexing;

import com.myhomelibcorp.application.content.indexing.ContentIndexQueueCheckpoint;
import com.myhomelibcorp.application.content.indexing.ContentIndexingPriority;
import com.myhomelibcorp.application.content.indexing.ContentIndexingTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileContentIndexQueueCheckpointAdapterTest {
    @TempDir Path temp;

    @Test
    void checkpointSurvivesNewAdapterInstanceAndCanBeCleared() {
        String previous = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.toString());
        try {
            var task = new ContentIndexingTask("t1", "collection:1", "b1", "a1", temp.resolve("book.txt").toString(),
                    "txt", ContentIndexingPriority.HIGH, Instant.parse("2026-09-12T12:00:00Z"));
            var expected = new ContentIndexQueueCheckpoint("collection:1", true, List.of(task));
            new FileContentIndexQueueCheckpointAdapter().save(expected);

            var loaded = new FileContentIndexQueueCheckpointAdapter().load("collection:1");
            assertThat(loaded).contains(expected);

            new FileContentIndexQueueCheckpointAdapter().clear("collection:1");
            assertThat(new FileContentIndexQueueCheckpointAdapter().load("collection:1")).isEmpty();
        } finally {
            if (previous == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", previous);
        }
    }
}
