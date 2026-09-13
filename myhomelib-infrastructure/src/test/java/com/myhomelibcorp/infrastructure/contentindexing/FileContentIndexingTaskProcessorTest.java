package com.myhomelibcorp.infrastructure.contentindexing;

import com.myhomelibcorp.application.content.ContentExtractionService;
import com.myhomelibcorp.application.content.index.ContentIndexEntry;
import com.myhomelibcorp.application.content.index.ContentIndexHealth;
import com.myhomelibcorp.application.content.index.ContentIndexPage;
import com.myhomelibcorp.application.content.index.ContentIndexQuery;
import com.myhomelibcorp.application.content.indexing.*;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.infrastructure.content.TxtContentExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FileContentIndexingTaskProcessorTest {
    @TempDir Path temp;

    @Test
    void extractsTxtAndWritesIndependentContentIndex() throws Exception {
        Path source = temp.resolve("unicode.txt");
        Files.writeString(source, "Український текст для повнотекстового індексу — café.");
        CapturingIndex index = new CapturingIndex();
        FileContentIndexingTaskProcessor processor = new FileContentIndexingTaskProcessor(
                new ContentExtractionService(List.of(new TxtContentExtractor())), index);
        ContentIndexingTask task = task(source);

        ContentIndexingOutcome result = processor.process(task,
                new ContentIndexingControl(() -> false, ignored -> { }, 0));

        assertThat(result.status()).isEqualTo(ContentIndexingOutcome.Status.COMPLETED);
        assertThat(index.collectionId.get()).isEqualTo("c1");
        assertThat(index.entry.get().content().chapters().getFirst().text()).contains("Український", "café");
    }

    @Test
    void cancellationStopsBeforeIndexMutation() throws Exception {
        Path source = temp.resolve("book.txt");
        Files.writeString(source, "text");
        CapturingIndex index = new CapturingIndex();
        FileContentIndexingTaskProcessor processor = new FileContentIndexingTaskProcessor(
                new ContentExtractionService(List.of(new TxtContentExtractor())), index);
        AtomicBoolean cancelled = new AtomicBoolean(true);

        ContentIndexingOutcome result = processor.process(task(source),
                new ContentIndexingControl(cancelled::get, ignored -> { }, 1024));

        assertThat(result.status()).isEqualTo(ContentIndexingOutcome.Status.CANCELLED);
        assertThat(index.entry.get()).isNull();
    }

    private ContentIndexingTask task(Path source) {
        return new ContentIndexingTask("t1", "c1", "b1", "a1", source.toString(), "txt",
                ContentIndexingPriority.NORMAL, Instant.parse("2026-09-12T12:00:00Z"));
    }

    static final class CapturingIndex implements ContentIndexPort {
        final AtomicReference<String> collectionId = new AtomicReference<>();
        final AtomicReference<ContentIndexEntry> entry = new AtomicReference<>();
        @Override public void replaceArtifact(String collectionId, ContentIndexEntry entry) { this.collectionId.set(collectionId); this.entry.set(entry); }
        @Override public void deleteArtifact(String collectionId, String artifactId) { }
        @Override public void deleteBook(String collectionId, String bookId) { }
        @Override public void rebuild(String collectionId, Iterable<ContentIndexEntry> entries) { }
        @Override public ContentIndexPage search(ContentIndexQuery query) { return null; }
        @Override public ContentIndexHealth health(String collectionId) { return null; }
    }
}
