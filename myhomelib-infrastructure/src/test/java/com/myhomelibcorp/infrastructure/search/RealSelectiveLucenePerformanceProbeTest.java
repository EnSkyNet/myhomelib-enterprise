package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookListRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.GenreRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteBookQueryRepository;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookQueryBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in real-DB probe for the selective Lucene phase of a changed full snapshot.
 * The baseline DB must represent the state before the catalog mutation; the changed DB must contain
 * the exact changed ids in book_search_state. Neither DB nor the generated Lucene index is a fixture.
 */
class RealSelectiveLucenePerformanceProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "mhl.real.seed.db", matches = ".+")
    void measuresSelectiveUpdate() throws Exception {
        Path seedDb = requiredPath("mhl.real.seed.db");
        Path changedDb = requiredPath("mhl.real.changed.db");
        Path index = Path.of(System.getProperty("mhl.real.index", "target/real-selective-lucene-index"))
                .toAbsolutePath().normalize();
        deleteTree(index);
        Files.createDirectories(index);

        long fullMs;
        int fullDocs;
        try (HikariDataSource seedDs = dataSource(seedDb)) {
            JdbcTemplate seedJdbc = new JdbcTemplate(seedDs);
            CollectionManager seedManager = manager(seedJdbc, seedDs);
            // Full rebuild uses streamSearchSnapshots(), so the heavier aggregate mappers/helpers are not needed.
            var seedRepo = new SqliteBookQueryRepository(seedManager, null, null, null, null, new BookQueryBuilder());
            try (var analyzer = new StandardAnalyzer()) {
                var service = new LuceneSearchService(FSDirectory.open(index), analyzer, parser(analyzer), seedRepo);
                setCommitInterval(service);
                service.init();
                try {
                    long started = System.nanoTime();
                    service.rebuildIndex();
                    fullMs = elapsedMs(started);
                    fullDocs = service.getDocumentCount();
                } finally {
                    service.close();
                }
            }
        }

        try (HikariDataSource changedDs = dataSource(changedDb)) {
            JdbcTemplate jdbc = new JdbcTemplate(changedDs);
            CollectionManager manager = manager(jdbc, changedDs);
            var repo = fullBookRepository(manager);
            List<String> ids = jdbc.queryForList("SELECT book_id FROM book_search_state ORDER BY book_id", String.class);
            assertThat(ids).as("exact selective Lucene change-set").isNotEmpty();

            try (var analyzer = new StandardAnalyzer()) {
                var service = new LuceneSearchService(FSDirectory.open(index), analyzer, parser(analyzer), repo);
                setCommitInterval(service);
                service.init();
                try {
                    long dbReadNanos = 0L;
                    long luceneWriteNanos = 0L;
                    int processed = 0;
                    long started = System.nanoTime();
                    service.beginAtomicUpdate();

                    for (int start = 0; start < ids.size(); start += 400) {
                        List<BookId> batchIds = ids.subList(start, Math.min(ids.size(), start + 400)).stream()
                                .map(BookId::fromString).toList();
                        long dbStarted = System.nanoTime();
                        List<Book> books = repo.findByIds(batchIds);
                        dbReadNanos += System.nanoTime() - dbStarted;

                        Map<String, Book> byId = new HashMap<>();
                        for (Book book : books) if (book != null) byId.put(book.getId().asString(), book);

                        long luceneStarted = System.nanoTime();
                        for (BookId id : batchIds) {
                            Book book = byId.get(id.asString());
                            if (book == null || book.isDeleted()) service.deleteBook(id);
                            else service.indexBook(book);
                            processed++;
                        }
                        luceneWriteNanos += System.nanoTime() - luceneStarted;
                    }

                    long commitStarted = System.nanoTime();
                    service.commit();
                    long commitMs = elapsedMs(commitStarted);
                    long wallMs = elapsedMs(started);
                    System.out.printf("REAL_SELECTIVE_LUCENE_RESULT fullBuildMs=%d fullDocs=%d changedIds=%d processed=%d wallMs=%d dbReadMs=%d luceneWriteMs=%d commitMs=%d finalDocs=%d%n",
                            fullMs, fullDocs, ids.size(), processed, wallMs, dbReadNanos / 1_000_000L,
                            luceneWriteNanos / 1_000_000L, commitMs, service.getDocumentCount());
                    assertThat(processed).isEqualTo(ids.size());
                    assertThat(service.getDocumentCount()).isEqualTo(fullDocs);
                } finally {
                    service.close();
                }
            }
        }
    }

    private static SqliteBookQueryRepository fullBookRepository(CollectionManager manager) {
        var authorHelper = new BookAuthorHelper(manager, mock(AuthorRepository.class), new AuthorRowMapper());
        var genreHelper = new BookGenreHelper(manager, mock(GenreRepository.class), new GenreRowMapper());
        return new SqliteBookQueryRepository(manager, new BookRowMapper(), new BookListRowMapper(),
                authorHelper, genreHelper, new BookQueryBuilder());
    }

    private static MultiFieldQueryParser parser(StandardAnalyzer analyzer) {
        var parser = new MultiFieldQueryParser(
                new String[]{"title", "authors", "series", "genres", "keywords", "annotation", "file_name", "publisher"},
                analyzer);
        parser.setAllowLeadingWildcard(true);
        return parser;
    }

    private static HikariDataSource dataSource(Path db) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite:" + db);
        hc.setDriverClassName("org.sqlite.JDBC");
        hc.setMaximumPoolSize(2);
        hc.setMinimumIdle(1);
        hc.setConnectionInitSql("PRAGMA foreign_keys=ON; PRAGMA busy_timeout=15000; PRAGMA synchronous=NORMAL; PRAGMA temp_store=MEMORY; PRAGMA cache_size=-32768; PRAGMA mmap_size=67108864;");
        return new HikariDataSource(hc);
    }

    private static CollectionManager manager(JdbcTemplate jdbc, HikariDataSource ds) {
        CollectionManager manager = mock(CollectionManager.class);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        when(manager.getCurrentDataSource()).thenReturn(ds);
        when(manager.hasActiveCollection()).thenReturn(true);
        return manager;
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing -D" + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        assertThat(path).isRegularFile();
        return path;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void setCommitInterval(LuceneSearchService service) throws Exception {
        var field = service.getClass().getDeclaredField("commitInterval");
        field.setAccessible(true);
        field.setInt(service, 10_000);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
