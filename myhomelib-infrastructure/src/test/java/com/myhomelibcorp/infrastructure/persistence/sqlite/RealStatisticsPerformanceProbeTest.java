package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in production statistics refresh benchmark over a real imported catalogue DB. */
class RealStatisticsPerformanceProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "mhl.real.db", matches = ".+")
    void refreshesStatisticsFromRealDatabase() throws Exception {
        Path db = Path.of(System.getProperty("mhl.real.db")).toAbsolutePath().normalize();
        assertThat(db).isRegularFile();

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite:" + db);
        hc.setDriverClassName("org.sqlite.JDBC");
        hc.setMaximumPoolSize(2);
        hc.setMinimumIdle(1);
        hc.setConnectionInitSql("PRAGMA foreign_keys=ON; PRAGMA busy_timeout=15000; PRAGMA synchronous=NORMAL; PRAGMA temp_store=MEMORY; PRAGMA cache_size=-32768; PRAGMA mmap_size=67108864;");
        try (HikariDataSource ds = new HikariDataSource(hc)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            CollectionManager manager = mock(CollectionManager.class);
            when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
            when(manager.getCurrentDataSource()).thenReturn(ds);
            when(manager.hasActiveCollection()).thenReturn(true);

            SqliteStatisticsRepository repository = new SqliteStatisticsRepository(
                    manager, new QueryExecutor(manager), new SqliteBusyRetryExecutor());
            long started = System.nanoTime();
            repository.invalidate();
            repository.refreshStatistics();
            long wallMs = (System.nanoTime() - started) / 1_000_000L;
            var stats = repository.getStatistics();

            System.out.printf("REAL_STATISTICS_RESULT wallMs=%d books=%d authors=%d languages=%d totalSizeBytes=%d local=%d remote=%d deleted=%d dbBytes=%d%n",
                    wallMs, stats.getBooksCount(), stats.getAuthorsCount(), stats.getLanguagesCount(),
                    stats.getTotalSizeBytes(), stats.getLocalBooksCount(), stats.getRemoteBooksCount(),
                    stats.getDeletedBooksCount(), Files.size(db));
            assertThat(stats.getBooksCount()).isGreaterThan(400_000);
            assertThat(stats.getDeletedBooksCount()).isGreaterThan(100_000);
        }
    }
}
