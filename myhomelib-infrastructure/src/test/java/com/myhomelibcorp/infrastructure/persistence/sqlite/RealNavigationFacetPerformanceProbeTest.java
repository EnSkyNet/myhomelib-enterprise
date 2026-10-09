package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in first/deep-page benchmark for the author sidebar over a real catalogue DB. */
class RealNavigationFacetPerformanceProbeTest {

    @Test
    @EnabledIfSystemProperty(named = "mhl.real.db", matches = ".+")
    void authorSidebarKeysetPagesStayInteractive() {
        Path db = Path.of(System.getProperty("mhl.real.db")).toAbsolutePath().normalize();
        assertThat(db).isRegularFile();
        try (HikariDataSource ds = new DataSourceConfig().createDataSourceForPath(db.toString())) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            CollectionManager manager = mock(CollectionManager.class);
            when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
            when(manager.getCurrentDataSource()).thenReturn(ds);
            SqliteNavigationFacetRepository repository = new SqliteNavigationFacetRepository(manager);

            for (char initial : List.of('А', 'Д', 'S', '#')) {
                long firstStarted = System.nanoTime();
                var first = repository.findAuthorsAfter(initial, BookFilterSpec.empty(), 500, null);
                long firstMs = elapsedMs(firstStarted);

                long secondMs = 0L;
                int secondSize = 0;
                if (first.nextCursor() != null) {
                    long secondStarted = System.nanoTime();
                    var second = repository.findAuthorsAfter(initial, BookFilterSpec.empty(), 500, first.nextCursor());
                    secondMs = elapsedMs(secondStarted);
                    secondSize = second.content().size();
                }

                System.out.printf("REAL_AUTHOR_NAV initial=%s firstSize=%d firstMs=%d secondSize=%d secondMs=%d hasMore=%s%n",
                        initial, first.content().size(), firstMs, secondSize, secondMs, first.nextCursor() != null);

                // The original regression was measured in tens of seconds. One second per 500-row
                // page is deliberately generous for CI/VM noise and still catches a full-table
                // GROUP BY / correlated COUNT regression immediately.
                assertThat(firstMs).as("first author page for %s", initial).isLessThan(1_000L);
                assertThat(secondMs).as("second author page for %s", initial).isLessThan(1_000L);
            }
        }
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
