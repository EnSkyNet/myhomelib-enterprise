package com.myhomelibcorp.benchmark.search;

import com.myhomelibcorp.application.content.ContentAnchor;
import com.myhomelibcorp.application.content.ExtractedChapter;
import com.myhomelibcorp.application.content.ExtractedContent;
import com.myhomelibcorp.application.content.index.ContentIndexEntry;
import com.myhomelibcorp.application.content.index.ContentIndexQuery;
import com.myhomelibcorp.infrastructure.contentindex.LuceneContentIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in reproducible MHL-302 benchmark. The benchmark is deliberately excluded from normal
 * regression runs; enable with -Dmyhomelib.contentBenchmark=true.
 */
class ContentIndexPerformanceBenchmarkTest {

    @TempDir
    Path temp;

    @Test
    @EnabledIfSystemProperty(named = "myhomelib.contentBenchmark", matches = "true")
    void benchmarkIndependentContentRebuildAndWarmSearch() throws Exception {
        String oldDataDir = System.getProperty("myhomelib.dataDir");
        try {
            Path dataDir = temp.resolve("data");
            System.setProperty("myhomelib.dataDir", dataDir.toString());
            int documents = Integer.getInteger("myhomelib.contentBenchmark.documents", 5_000);
            int runs = Integer.getInteger("myhomelib.contentBenchmark.runs", 7);
            Path output = Path.of(System.getProperty("myhomelib.contentBenchmark.output",
                    "target/content-index-benchmark.csv"));

            Path catalogueSentinel = dataDir.resolve("search-index/benchmark/catalogue-sentinel.bin");
            Files.createDirectories(catalogueSentinel.getParent());
            byte[] sentinel = "catalogue-index-isolation-sentinel".getBytes(StandardCharsets.UTF_8);
            Files.write(catalogueSentinel, sentinel);

            LuceneContentIndexService service = new LuceneContentIndexService();
            List<ContentIndexEntry> corpus = corpus(documents);

            long rebuildStarted = System.nanoTime();
            service.rebuild("benchmark", corpus);
            long rebuildMicros = (System.nanoTime() - rebuildStarted) / 1_000L;

            long[] warmMicros = new long[Math.max(1, runs)];
            long totalHits = 0L;
            for (int i = 0; i < warmMicros.length; i++) {
                long started = System.nanoTime();
                var page = service.search(ContentIndexQuery.allBooks("benchmark", "commonterm", 100));
                warmMicros[i] = (System.nanoTime() - started) / 1_000L;
                totalHits = page.total();
            }
            Arrays.sort(warmMicros);
            var health = service.health("benchmark");
            boolean catalogueUnchanged = Arrays.equals(sentinel, Files.readAllBytes(catalogueSentinel));

            assertThat(health.compatible()).isTrue();
            assertThat(health.documentCount()).isEqualTo(documents);
            assertThat(totalHits).isEqualTo(documents);
            assertThat(catalogueUnchanged).isTrue();

            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            String csv = "generated_at,documents,rebuild_us,documents_per_second,warm_runs,warm_min_us,warm_median_us,warm_max_us,total_hits,index_size_bytes,catalogue_sentinel_unchanged\n"
                    + Instant.now() + ',' + documents + ',' + rebuildMicros + ','
                    + String.format(Locale.ROOT, "%.2f", documents * 1_000_000.0 / Math.max(1L, rebuildMicros)) + ','
                    + warmMicros.length + ',' + warmMicros[0] + ',' + warmMicros[warmMicros.length / 2] + ','
                    + warmMicros[warmMicros.length - 1] + ',' + totalHits + ',' + health.sizeBytes() + ',' + catalogueUnchanged + '\n';
            Files.writeString(output, csv, StandardCharsets.UTF_8);
        } finally {
            if (oldDataDir == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", oldDataDir);
        }
    }

    private static List<ContentIndexEntry> corpus(int documents) {
        List<ContentIndexEntry> entries = new ArrayList<>(documents);
        for (int i = 0; i < documents; i++) {
            String text = "commonterm deterministic searchable content document " + i
                    + " category" + (i % 32) + (i % 1000 == 0 ? " rareterm" : "");
            String chapterId = "chapter-1";
            ContentAnchor anchor = new ContentAnchor(chapterId + ":p1", chapterId, "p1", 0, text.length());
            ExtractedChapter chapter = new ExtractedChapter(chapterId, "Chapter " + i, 0, text.length(), text, List.of(anchor));
            ExtractedContent content = new ExtractedContent("artifact-" + i, "txt", text, List.of(chapter));
            entries.add(new ContentIndexEntry("book-" + i, "artifact-" + i, content));
        }
        return entries;
    }
}
