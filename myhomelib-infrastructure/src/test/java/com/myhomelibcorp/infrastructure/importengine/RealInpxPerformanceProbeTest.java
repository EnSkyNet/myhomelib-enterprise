package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.infrastructure.catalog.SqliteCatalogUpdateTrackingAdapter;
import com.myhomelibcorp.application.catalog.CatalogBookSnapshot;
import com.myhomelibcorp.application.catalog.CatalogSyncSession;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteBusyRetryExecutor;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteBulkImportOptimizer;
import com.myhomelibcorp.shared.util.Sha256Support;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in production-pipeline benchmark over a user supplied real INPX file. */
class RealInpxPerformanceProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "mhl.real.inpx", matches = ".+")
    void importsRealInpxThroughProductionPipeline() throws Exception {
        Path inpx = Path.of(System.getProperty("mhl.real.inpx")).toAbsolutePath().normalize();
        assertThat(inpx).isRegularFile();
        Path out = Path.of(System.getProperty("mhl.real.out", "target/real-inpx-probe")).toAbsolutePath().normalize();
        Files.createDirectories(out);
        Path db = out.resolve("real-inpx.db");
        boolean reuseExistingDb = Boolean.getBoolean("mhl.real.reuse-db");
        boolean fullSnapshot = Boolean.parseBoolean(System.getProperty("mhl.real.full-snapshot", "true"));
        if (reuseExistingDb) {
            assertThat(db).isRegularFile();
        } else {
            Files.deleteIfExists(db);
            Files.deleteIfExists(Path.of(db + "-wal"));
            Files.deleteIfExists(Path.of(db + "-shm"));
        }
        Path root = out.resolve("empty-library-root");
        Files.createDirectories(root);

        InpxReader reader = new InpxReader();
        long t0 = System.nanoTime();
        long records = reader.count(inpx, null, true);
        long countMs = ms(t0);
        t0 = System.nanoTime();
        String sha = Sha256Support.file(inpx);
        long shaMs = ms(t0);
        System.out.printf("REAL_INPX_PRECHECK records=%d countMs=%d shaMs=%d sha256=%s size=%d%n",
                records, countMs, shaMs, sha, Files.size(inpx));

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite:" + db);
        hc.setDriverClassName("org.sqlite.JDBC");
        hc.setMaximumPoolSize(2);
        hc.setMinimumIdle(1);
        hc.setConnectionTimeout(30_000);
        hc.setConnectionInitSql("PRAGMA foreign_keys=ON; PRAGMA busy_timeout=15000; PRAGMA synchronous=NORMAL; PRAGMA temp_store=MEMORY; PRAGMA cache_size=-32768; PRAGMA mmap_size=67108864;");
        try (HikariDataSource ds = new HikariDataSource(hc)) {
            try (var c = ds.getConnection(); var st = c.createStatement()) { st.execute("PRAGMA journal_mode=WAL"); }
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            long booksBefore = jdbc.queryForObject("SELECT COUNT(*) FROM books", Long.class);
            long authorsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM authors", Long.class);
            long bookAuthorsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM book_authors", Long.class);
            long bookGenresBefore = jdbc.queryForObject("SELECT COUNT(*) FROM book_genres", Long.class);
            System.out.printf("REAL_INPX_MODE reuseExistingDb=%s fullSnapshot=%s booksBefore=%d authorsBefore=%d bookAuthorsBefore=%d bookGenresBefore=%d%n",
                    reuseExistingDb, fullSnapshot, booksBefore, authorsBefore, bookAuthorsBefore, bookGenresBefore);
            CollectionManager manager = mock(CollectionManager.class);
            when(manager.hasActiveCollection()).thenReturn(true);
            when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
            when(manager.getCurrentDataSource()).thenReturn(ds);

            TimingJdbcBatchWriter writer = new TimingJdbcBatchWriter(manager);
            TimingCatalogTrackingAdapter tracking = new TimingCatalogTrackingAdapter(manager, new SqliteBusyRetryExecutor());
            var pipeline = new InpxImportPipeline(reader, writer, new SqliteBulkImportOptimizer(manager), manager,
                    tracking, new ImportIndexLifecycle(manager));
            setInt(pipeline, "changeTrackingLimit", 50_000);
            setInt(pipeline, "authorCacheSize", 250_000);
            setInt(pipeline, "onlineBatchSize", 5_000);

            AtomicLong last = new AtomicLong();
            long importStart = System.nanoTime();
            ImportResult result = pipeline.importFileWithResult(
                    inpx, 1_000, root, null,
                    "remote-collection:real-inpx-benchmark",
                    "https://alex80.github.io/mhl/download/inpx/",
                    fullSnapshot,
                    null,
                    s -> { },
                    "real-inpx-benchmark",
                    p -> {
                        long processed = p.processed();
                        long previous = last.get();
                        if (processed >= previous + 50_000 && last.compareAndSet(previous, processed)) {
                            System.out.printf("REAL_INPX_PROGRESS processed=%d total=%d inserted=%d updated=%d deleted=%d errors=%d bookWriteMs=%d authorMs=%d trackingMs=%d%n",
                                    processed, p.total(), p.inserted(), p.updated(), p.deleted(), p.errors(),
                                    writer.bookWriteMs.get(), writer.fastAuthorResolveMs.get() + writer.authorResolveMs.get(), tracking.recordMs.get());
                        }
                    });
            long importMs = ms(importStart);

            long books = jdbc.queryForObject("SELECT COUNT(*) FROM books", Long.class);
            long authors = jdbc.queryForObject("SELECT COUNT(*) FROM authors", Long.class);
            long bookAuthors = jdbc.queryForObject("SELECT COUNT(*) FROM book_authors", Long.class);
            long genres = jdbc.queryForObject("SELECT COUNT(*) FROM genres", Long.class);
            long bookGenres = jdbc.queryForObject("SELECT COUNT(*) FROM book_genres", Long.class);
            long catalogState = jdbc.queryForObject("SELECT COUNT(*) FROM catalog_book_state", Long.class);
            long deleted = jdbc.queryForObject("SELECT COUNT(*) FROM books WHERE deleted=1", Long.class);
            long dbBytes = Files.size(db);
            double rate = result.imported() / Math.max(0.001, importMs / 1000.0);
            System.out.printf("REAL_INPX_RESULT imported=%d processed=%d errors=%d durationMs=%d booksPerSec=%.2f inserted=%d updated=%d changedDeleted=%d books=%d authors=%d bookAuthors=%d genres=%d bookGenres=%d catalogState=%d deleted=%d dbBytes=%d%n",
                    result.imported(), records, result.errors(), importMs, rate,
                    result.changes().insertedCount(), result.changes().updatedCount(), result.changes().deletedCount(),
                    books, authors, bookAuthors, genres, bookGenres, catalogState, deleted, dbBytes);
            System.out.printf("REAL_INPX_PHASES bookWriteMs=%d authorResolveMs=%d fastAuthorResolveMs=%d genreWriteMs=%d trackingMs=%d%n",
                    writer.bookWriteMs.get(), writer.authorResolveMs.get(), writer.fastAuthorResolveMs.get(),
                    writer.genreWriteMs.get(), tracking.recordMs.get());
            if (reuseExistingDb && result.imported() == 0) {
                assertThat(result.skipped()).isEqualTo(records);
            } else {
                assertThat(result.imported()).isEqualTo(records);
            }
            assertThat(result.errors()).isZero();
            assertThat(books).isGreaterThan(500_000);
            assertThat(catalogState).isEqualTo(books);
            if (reuseExistingDb) {
                assertThat(books).isEqualTo(booksBefore);
                assertThat(authors).isEqualTo(authorsBefore);
                assertThat(bookAuthors).isEqualTo(bookAuthorsBefore);
                assertThat(bookGenres).isEqualTo(bookGenresBefore);
            }
        }
    }

    private static final class TimingJdbcBatchWriter extends JdbcBatchWriter {
        private final AtomicLong bookWriteMs = new AtomicLong();
        private final AtomicLong authorResolveMs = new AtomicLong();
        private final AtomicLong fastAuthorResolveMs = new AtomicLong();
        private final AtomicLong genreWriteMs = new AtomicLong();

        private TimingJdbcBatchWriter(CollectionManager manager) { super(manager); }

        @Override
        public void batchInsertFull(List<Object[]> booksData, Map<String, String> authorResolution) {
            long started = System.nanoTime();
            super.batchInsertFull(booksData, authorResolution);
            bookWriteMs.addAndGet(ms(started));
        }

        @Override
        public Map<String, String> batchInsertAuthorsAndResolveIds(List<Author> authors) {
            long started = System.nanoTime();
            try { return super.batchInsertAuthorsAndResolveIds(authors); }
            finally { authorResolveMs.addAndGet(ms(started)); }
        }

        @Override
        public Map<String, String> batchInsertAuthorsAndResolveIdsAssumingNew(List<Author> authors) {
            long started = System.nanoTime();
            try { return super.batchInsertAuthorsAndResolveIdsAssumingNew(authors); }
            finally { fastAuthorResolveMs.addAndGet(ms(started)); }
        }

        @Override
        public void batchInsertGenres(List<Genre> genres) {
            long started = System.nanoTime();
            super.batchInsertGenres(genres);
            genreWriteMs.addAndGet(ms(started));
        }
    }

    private static final class TimingCatalogTrackingAdapter extends SqliteCatalogUpdateTrackingAdapter {
        private final AtomicLong recordMs = new AtomicLong();

        private TimingCatalogTrackingAdapter(CollectionManager manager, SqliteBusyRetryExecutor busyRetry) {
            super(manager, busyRetry);
        }

        @Override
        public void recordImportedBooks(CatalogSyncSession session, List<CatalogBookSnapshot> books) {
            long started = System.nanoTime();
            try { super.recordImportedBooks(session, books); }
            finally { recordMs.addAndGet(ms(started)); }
        }
    }

    private static long ms(long started) { return (System.nanoTime() - started) / 1_000_000L; }
    private static void setInt(Object target, String fieldName, int value) throws Exception {
        var f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.setInt(target, value);
    }
}
