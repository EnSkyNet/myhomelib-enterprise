package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteBookQueryRepository;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookQueryBuilder;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in full Lucene rebuild over a real imported catalogue DB. */
class RealLucenePerformanceProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "mhl.real.db", matches = ".+")
    void rebuildsLuceneFromRealDatabase() throws Exception {
        Path db = Path.of(System.getProperty("mhl.real.db")).toAbsolutePath().normalize();
        assertThat(db).isRegularFile();
        Path index = Path.of(System.getProperty("mhl.real.index", "target/real-lucene-index")).toAbsolutePath().normalize();
        if (Files.exists(index)) {
            try (var walk = Files.walk(index)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception e) { throw new RuntimeException(e); } });
            }
        }
        Files.createDirectories(index);

        try (HikariDataSource ds = new DataSourceConfig().createDataSourceForPath(db.toString())) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            CollectionManager manager = mock(CollectionManager.class);
            when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
            when(manager.getCurrentDataSource()).thenReturn(ds);
            when(manager.hasActiveCollection()).thenReturn(true);

            var repo = new SqliteBookQueryRepository(manager, null, null, null, null, new BookQueryBuilder());
            long active = jdbc.queryForObject("SELECT COUNT(*) FROM books WHERE deleted=0", Long.class);
            long all = jdbc.queryForObject("SELECT COUNT(*) FROM books", Long.class);
            System.out.printf("REAL_LUCENE_PRECHECK all=%d active=%d dbBytes=%d%n", all, active, Files.size(db));

            var analyzer = new StandardAnalyzer();
            var parser = new MultiFieldQueryParser(
                    new String[]{"title", "authors", "series", "genres", "keywords", "annotation", "file_name", "publisher"}, analyzer);
            parser.setAllowLeadingWildcard(true);
            var service = new LuceneSearchService(FSDirectory.open(index), analyzer, parser, repo);
            setInt(service, "commitInterval", 10_000);
            service.init();
            try {
                AtomicLong last = new AtomicLong();
                long started = System.nanoTime();
                service.rebuildIndex(null, p -> {
                    long done = p.processed();
                    long prev = last.get();
                    if (done >= prev + 50_000 && last.compareAndSet(prev, done)) {
                        System.out.printf("REAL_LUCENE_PROGRESS processed=%d total=%d%n", done, p.total());
                    }
                });
                // Standalone probe does not wire LuceneCollectionIndexLifecycle, whose full-rebuild
                // observer marks the production index queryable after a successful atomic rebuild.
                service.setQueryAvailability(true, null);
                long wallMs = (System.nanoTime() - started) / 1_000_000L;
                var r = service.lastPerformanceReport().orElseThrow();
                long indexBytes;
                try (var walk = Files.walk(index)) { indexBytes = walk.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } }).sum(); }
                System.out.printf("REAL_LUCENE_RESULT wallMs=%d docs=%d docsPerSec=%.2f dbReadMs=%d documentMs=%d writeMs=%d mergeMs=%d commitMs=%d peakHeapBytes=%d indexBytes=%d%n",
                        wallMs, r.processedDocuments(), r.documentsPerSecond(), r.dbReadMs(), r.documentBuildMs(), r.luceneWriteMs(), r.mergeWaitMs(), r.commitMs(), r.peakHeapBytes(), indexBytes);
                assertThat(r.processedDocuments()).isEqualTo(active);

                for (String query : java.util.List.of("Дорничев", "любовь", "война", "фантастика")) {
                    service.search(SearchRequest.builder().text(query).limit(100).build()); // warm-up
                    long searchStarted = System.nanoTime();
                    var result = service.search(SearchRequest.builder().text(query).limit(100).build());
                    long searchMicros = (System.nanoTime() - searchStarted) / 1_000L;
                    System.out.printf("REAL_LUCENE_SEARCH query='%s' hits=%d returned=%d durationUs=%d%n",
                            query, result.totalHits(), result.bookIds().size(), searchMicros);
                    assertThat(result.totalHits()).isGreaterThanOrEqualTo(0L);
                }
            } finally {
                service.close();
                analyzer.close();
            }
        }
    }

    private static void setInt(Object target, String fieldName, int value) throws Exception {
        var f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.setInt(target, value);
    }
}
