package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in regression benchmark for the left-sidebar author type-ahead over a real catalogue DB. */
class RealAuthorSearchPerformanceProbeTest {

    @Test
    @EnabledIfSystemProperty(named = "mhl.real.db", matches = ".+")
    void realSidebarAuthorSearchStaysInteractive() {
        Path db = Path.of(System.getProperty("mhl.real.db")).toAbsolutePath().normalize();
        assertThat(db).isRegularFile();

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite:" + db);
        hc.setDriverClassName("org.sqlite.JDBC");
        hc.setMaximumPoolSize(2);
        hc.setMinimumIdle(1);
        hc.setConnectionInitSql("PRAGMA foreign_keys=ON; PRAGMA busy_timeout=15000; PRAGMA synchronous=NORMAL; "
                + "PRAGMA temp_store=MEMORY; PRAGMA cache_size=-32768; PRAGMA mmap_size=67108864;");

        try (HikariDataSource ds = new HikariDataSource(hc)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            CollectionManager manager = mock(CollectionManager.class);
            when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
            when(manager.getCurrentDataSource()).thenReturn(ds);

            SqliteAuthorRepository repository = new SqliteAuthorRepository(manager, new AuthorRowMapper());
            List<String> queries = List.of("дорничев", "дорб", "Дмитрий Дорничев", "Дорничев Дмитрий");

            for (String query : queries) {
                repository.searchByName(query, 200); // warm-up
                List<Long> samplesMs = new ArrayList<>();
                int resultCount = 0;
                for (int i = 0; i < 5; i++) {
                    long started = System.nanoTime();
                    resultCount = repository.searchByName(query, 200).size();
                    samplesMs.add((System.nanoTime() - started) / 1_000_000L);
                }
                long worstMs = samplesMs.stream().mapToLong(Long::longValue).max().orElseThrow();
                System.out.printf("REAL_AUTHOR_SEARCH query='%s' results=%d samplesMs=%s worstMs=%d%n",
                        query, resultCount, samplesMs, worstMs);

                // The reported regression was 16-30 seconds. Keep a deliberately generous
                // one-second guardrail so normal CI/VM noise cannot turn this probe flaky.
                assertThat(worstMs)
                        .as("author type-ahead must stay interactive for '%s'", query)
                        .isLessThan(1_000L);
            }
        }
    }
}
