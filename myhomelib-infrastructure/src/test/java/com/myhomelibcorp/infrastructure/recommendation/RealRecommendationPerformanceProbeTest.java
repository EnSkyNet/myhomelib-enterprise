package com.myhomelibcorp.infrastructure.recommendation;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.recommendation.BookSimilarityPolicy;
import com.myhomelibcorp.application.recommendation.FindSimilarBooksUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookListRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.BookRowMapper;
import com.myhomelibcorp.infrastructure.persistence.mapper.GenreRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteBookQueryRepository;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookAuthorHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookGenreHelper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookQueryBuilder;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in V33 same-environment Book Details baseline vs similar-books probe over a real catalogue DB. */
class RealRecommendationPerformanceProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "mhl.real.db", matches = ".+")
    void measuresBookDetailsBaselineAgainstRecommendations() {
        Path db = Path.of(System.getProperty("mhl.real.db")).toAbsolutePath().normalize();
        assertThat(db).isRegularFile();

        try (HikariDataSource ds = new DataSourceConfig().createDataSourceForPath(db.toString())) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            CollectionManager manager = mock(CollectionManager.class);
            when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
            when(manager.getCurrentDataSource()).thenReturn(ds);
            when(manager.hasActiveCollection()).thenReturn(true);

            var authors = new BookAuthorHelper(manager, mock(AuthorRepository.class), new AuthorRowMapper());
            var genres = new BookGenreHelper(manager, mock(GenreRepository.class), new GenreRowMapper());
            var repo = new SqliteBookQueryRepository(manager, new BookRowMapper(), new BookListRowMapper(),
                    authors, genres, new BookQueryBuilder());
            var policy = new BookSimilarityPolicy();
            var useCase = new FindSimilarBooksUseCase(repo, policy);

            int samples = Math.max(1, Math.min(10, Integer.getInteger("mhl.real.samples", 5)));
            var ids = jdbc.queryForList("""
                    SELECT b.id
                      FROM books b
                     WHERE b.deleted = 0
                       AND COALESCE(b.series, '') <> ''
                       AND EXISTS (SELECT 1 FROM book_authors ba WHERE ba.book_id=b.id)
                       AND EXISTS (SELECT 1 FROM book_genres bg WHERE bg.book_id=b.id)
                     ORDER BY b.id
                     LIMIT ?
                    """, String.class, samples);
            assertThat(ids).hasSize(samples);

            long worstRecommendationMs = 0L;
            for (String id : ids) {
                BookId sourceId = BookId.fromString(id);
                repo.findById(sourceId).orElseThrow();
                long baselineStarted = System.nanoTime();
                var source = repo.findById(sourceId).orElseThrow();
                long baselineMicros = (System.nanoTime() - baselineStarted) / 1_000L;

                forceGc();
                long heapBefore = usedHeap();
                long recommendationStarted = System.nanoTime();
                var similar = useCase.find(sourceId, 10);
                long recommendationMs = (System.nanoTime() - recommendationStarted) / 1_000_000L;
                long heapAfter = usedHeap();
                worstRecommendationMs = Math.max(worstRecommendationMs, recommendationMs);

                System.out.printf(java.util.Locale.ROOT,
                        "REAL_V33_AB sourceId=%s title='%s' baselineUs=%d recommendationMs=%d returned=%d heapDeltaBytes=%d dbBytes=%d%n",
                        sourceId.asString(), source.getTitle().replace('\n', ' '), baselineMicros, recommendationMs,
                        similar.size(), heapAfter - heapBefore, size(db));
                assertThat(similar.size()).isLessThanOrEqualTo(10);
            }
            System.out.printf(java.util.Locale.ROOT, "REAL_V33_AB_SUMMARY samples=%d worstRecommendationMs=%d%n",
                    samples, worstRecommendationMs);
        }
    }


    private static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    private static void forceGc() {
        System.gc();
        java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
    }

    private static long size(Path path) {
        try { return Files.size(path); }
        catch (Exception e) { return -1L; }
    }
}
