package com.myhomelibcorp.infrastructure.contentindex;

import com.myhomelibcorp.application.content.ContentAnchor;
import com.myhomelibcorp.application.content.ExtractedChapter;
import com.myhomelibcorp.application.content.ExtractedContent;
import com.myhomelibcorp.application.content.index.ContentIndexEntry;
import com.myhomelibcorp.application.content.index.ContentIndexQuery;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuceneContentIndexServiceIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void indexesSearchesAndFiltersByBookWithStableChapterOffsets() throws Exception {
        LuceneContentIndexService service = service();
        service.replaceArtifact("c1", entry("book-1", "artifact-1", "Alpha chapter", "Unique needle in first book"));
        service.replaceArtifact("c1", entry("book-2", "artifact-2", "Beta chapter", "Another needle in second book"));

        var page = service.search(ContentIndexQuery.allBooks("c1", "needle", 20));
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.hits()).extracting(hit -> hit.bookId()).containsExactlyInAnyOrder("book-1", "book-2");
        assertThat(page.hits()).allSatisfy(hit -> {
            assertThat(hit.artifactId()).startsWith("artifact-");
            assertThat(hit.chapterId()).isEqualTo("chapter-1");
            assertThat(hit.chapterStartOffset()).isZero();
            assertThat(hit.chapterEndOffset()).isPositive();
            assertThat(hit.snippet()).containsIgnoringCase("needle");
            assertThat(hit.matchOffset()).isGreaterThanOrEqualTo(hit.chapterStartOffset());
            assertThat(hit.matchOffset()).isLessThan(hit.chapterEndOffset());
        });

        var filtered = service.search(new ContentIndexQuery("c1", "needle", "book-2", 0, 20));
        assertThat(filtered.total()).isEqualTo(1);
        assertThat(filtered.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.bookId()).isEqualTo("book-2");
            assertThat(hit.chapterTitle()).isEqualTo("Beta chapter");
        });

        var health = service.health("c1");
        assertThat(health.compatible()).isTrue();
        assertThat(health.schemaVersion()).isEqualTo(LuceneContentIndexService.SCHEMA_VERSION);
        assertThat(health.documentCount()).isEqualTo(2);
        assertThat(health.sizeBytes()).isPositive();
    }

    @Test
    void replaceDeleteArtifactAndDeleteBookAreIdempotent() {
        LuceneContentIndexService service = service();
        service.replaceArtifact("c1", entry("book-1", "artifact-1", "One", "oldterm only"));
        service.replaceArtifact("c1", entry("book-1", "artifact-1", "One", "newterm only"));

        assertThat(service.search(ContentIndexQuery.allBooks("c1", "oldterm", 10)).total()).isZero();
        assertThat(service.search(ContentIndexQuery.allBooks("c1", "newterm", 10)).total()).isEqualTo(1);

        service.deleteArtifact("c1", "artifact-1");
        service.deleteArtifact("c1", "artifact-1");
        assertThat(service.search(ContentIndexQuery.allBooks("c1", "newterm", 10)).total()).isZero();

        service.replaceArtifact("c1", entry("book-1", "artifact-a", "One", "bookterm alpha"));
        service.replaceArtifact("c1", entry("book-1", "artifact-b", "Two", "bookterm beta"));
        service.deleteBook("c1", "book-1");
        assertThat(service.search(ContentIndexQuery.allBooks("c1", "bookterm", 10)).total()).isZero();
    }

    @Test
    void independentRebuildDoesNotTouchCatalogueIndexAndFullyReplacesOldCorpus() throws Exception {
        Path catalogue = temp.resolve("search-index").resolve("c1");
        Files.createDirectories(catalogue);
        Path sentinel = catalogue.resolve("catalogue-sentinel.txt");
        Files.writeString(sentinel, "metadata-index-must-stay-unchanged");

        LuceneContentIndexService service = service();
        List<ContentIndexEntry> first = corpus(120, "oldcorpus");
        service.rebuild("c1", first);
        assertThat(service.health("c1").documentCount()).isEqualTo(120);
        assertThat(service.search(ContentIndexQuery.allBooks("c1", "oldcorpus", 5)).total()).isEqualTo(120);

        List<ContentIndexEntry> second = corpus(75, "newcorpus");
        service.rebuild("c1", second);
        assertThat(service.health("c1").documentCount()).isEqualTo(75);
        assertThat(service.search(ContentIndexQuery.allBooks("c1", "oldcorpus", 5)).total()).isZero();
        assertThat(service.search(ContentIndexQuery.allBooks("c1", "newcorpus", 5)).total()).isEqualTo(75);

        assertThat(Files.readString(sentinel)).isEqualTo("metadata-index-must-stay-unchanged");
        assertThat(Files.isDirectory(temp.resolve("content-index").resolve("c1"))).isTrue();
    }

    @Test
    void schemaMismatchBlocksIncrementalWritesButRebuildRepairsIndex() throws Exception {
        Path index = temp.resolve("content-index").resolve("c1");
        Files.createDirectories(index);
        try (FSDirectory directory = FSDirectory.open(index);
             StandardAnalyzer analyzer = new StandardAnalyzer();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer)
                     .setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
            writer.setLiveCommitData(Map.of(LuceneContentIndexService.SCHEMA_KEY, "0").entrySet());
            writer.commit();
        }

        LuceneContentIndexService service = service();
        assertThat(service.health("c1").compatible()).isFalse();
        assertThatThrownBy(() -> service.replaceArtifact("c1", entry("book-1", "artifact-1", "One", "blocked")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompatible");

        service.rebuild("c1", List.of(entry("book-1", "artifact-1", "One", "repaired needle")));
        assertThat(service.health("c1").compatible()).isTrue();
        assertThat(service.health("c1").schemaVersion()).isEqualTo(LuceneContentIndexService.SCHEMA_VERSION);
        assertThat(service.search(ContentIndexQuery.allBooks("c1", "needle", 10)).total()).isEqualTo(1);
    }


    @Test
    void failedRebuildKeepsPreviousActiveIndexUntouched() {
        LuceneContentIndexService service = service();
        service.rebuild("c1", List.of(entry("book-old", "artifact-old", "Old", "stable oldtoken")));

        Iterable<ContentIndexEntry> failing = () -> new java.util.Iterator<>() {
            private int state;
            @Override public boolean hasNext() { return state < 2; }
            @Override public ContentIndexEntry next() {
                if (state++ == 0) return entry("book-new", "artifact-new", "New", "newtoken");
                throw new IllegalStateException("synthetic extraction failure");
            }
        };

        assertThatThrownBy(() -> service.rebuild("c1", failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.search(ContentIndexQuery.allBooks("c1", "oldtoken", 10)).total()).isEqualTo(1);
        assertThat(service.search(ContentIndexQuery.allBooks("c1", "newtoken", 10)).total()).isZero();
        assertThat(service.health("c1").documentCount()).isEqualTo(1);
    }

    private LuceneContentIndexService service() {
        return new LuceneContentIndexService(id -> temp.resolve("content-index").resolve(id));
    }

    private static List<ContentIndexEntry> corpus(int count, String token) {
        List<ContentIndexEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(entry("book-" + i, "artifact-" + i, "Chapter " + i,
                    token + " deterministic document " + i + " with searchable text"));
        }
        return entries;
    }

    private static ContentIndexEntry entry(String bookId, String artifactId, String title, String text) {
        ContentAnchor anchor = new ContentAnchor("chapter-1:p1", "chapter-1", "p1", 0, text.length());
        ExtractedChapter chapter = new ExtractedChapter("chapter-1", title, 0, text.length(), text, List.of(anchor));
        ExtractedContent content = new ExtractedContent(artifactId, "txt", text, List.of(chapter));
        return new ContentIndexEntry(bookId, artifactId, content);
    }
}
