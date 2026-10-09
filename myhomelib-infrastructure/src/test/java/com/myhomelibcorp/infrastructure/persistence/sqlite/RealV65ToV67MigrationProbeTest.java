package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in migration acceptance over a large real-data V65-shaped catalogue copy. */
class RealV65ToV67MigrationProbeTest {
    @Test
    @EnabledIfSystemProperty(named = "mhl.real.v65.db", matches = ".+")
    void migratesLargeV65CatalogueToV67WithoutChangingCatalogRows() throws Exception {
        Path db = Path.of(System.getProperty("mhl.real.v65.db")).toAbsolutePath().normalize();
        assertThat(db).isRegularFile();
        long beforeBytes = Files.size(db);
        try (HikariDataSource ds = new DataSourceConfig().createDataSourceForPath(db.toString())) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            long booksBefore = jdbc.queryForObject("SELECT COUNT(*) FROM books", Long.class);
            long authorsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM authors", Long.class);
            Integer versionBefore = jdbc.queryForObject("SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success=1", Integer.class);
            long started = System.nanoTime();
            var result = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
            long migrateMs = (System.nanoTime() - started) / 1_000_000L;
            Integer versionAfter = jdbc.queryForObject("SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success=1", Integer.class);
            long booksAfter = jdbc.queryForObject("SELECT COUNT(*) FROM books", Long.class);
            long authorsAfter = jdbc.queryForObject("SELECT COUNT(*) FROM authors", Long.class);
            String quick = jdbc.queryForObject("PRAGMA quick_check", String.class);
            Integer koboTables = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('kobo_sync_meta','kobo_sync_books','kobo_progress_versions')", Integer.class);
            Integer readwiseTables = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='annotation_remote_export_state'", Integer.class);
            System.out.printf(java.util.Locale.ROOT,
                    "REAL_V65_V67 versionBefore=%d versionAfter=%d migrations=%d migrateMs=%d books=%d authors=%d quick=%s bytesBefore=%d bytesAfter=%d%n",
                    versionBefore, versionAfter, result.migrationsExecuted, migrateMs, booksAfter, authorsAfter, quick, beforeBytes, Files.size(db));
            assertThat(versionBefore).isEqualTo(65);
            assertThat(versionAfter).isEqualTo(67);
            assertThat(result.migrationsExecuted).isEqualTo(2);
            assertThat(booksAfter).isEqualTo(booksBefore);
            assertThat(authorsAfter).isEqualTo(authorsBefore);
            assertThat(koboTables).isEqualTo(3);
            assertThat(readwiseTables).isEqualTo(1);
            assertThat(quick).isEqualToIgnoringCase("ok");
        }
    }
}
