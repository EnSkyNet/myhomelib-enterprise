package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeriesIdentityMigrationV61Test {

    @TempDir
    Path tempDir;

    @Test
    void deduplicatesLegacySeriesAndCreatesNormalizedIndexes() {
        Path db = tempDir.resolve("series-v61.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("60"))
                .load()
                .migrate();

        jdbc.update("INSERT INTO series(id,name,description) VALUES ('s1','  Chronicles  ',''),('s2','chronicles','duplicate'),('s3','Other','')");
        jdbc.update("INSERT INTO books(id,title,file_name,deleted,series,format) VALUES ('b-v61','Book','book.fb2',0,' CHRONICLES ','FB2')");

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM series WHERE LOWER(TRIM(name))='chronicles'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT name FROM series WHERE LOWER(TRIM(name))='chronicles'", String.class)).isEqualTo("Chronicles");
        assertThat(indexExists(jdbc, "ux_series_name_normalized")).isTrue();
        assertThat(indexExists(jdbc, "idx_books_active_series_normalized")).isTrue();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO series(id,name) VALUES ('duplicate',' CHRONICLES ')")).isInstanceOf(Exception.class);
    }

    private static boolean indexExists(JdbcTemplate jdbc, String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?", Integer.class, name);
        return count != null && count == 1;
    }
}
