package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderScopedPreferencesMigrationV65Test {
    @TempDir Path tempDir;

    @Test
    void migratesReleasedBookOverridesWithoutDroppingLegacyTableAndCascadesOnlyBookScope() {
        Path db = tempDir.resolve("reader-v65.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("64")).load().migrate();
        jdbc.update("INSERT INTO books(id,title,file_name) VALUES('book-v65','Reader V65','reader.fb2')");
        jdbc.update("INSERT INTO reader_book_preferences(book_id,preferences_json) VALUES('book-v65','{\"theme\":\"dark\"}')");

        Flyway flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("70");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='reader_book_preferences'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT preferences_json FROM reader_preference_overrides WHERE scope_type='BOOK' AND scope_key='book-v65'", String.class)).contains("dark");

        jdbc.update("INSERT INTO reader_preference_overrides(scope_type,scope_key,preferences_json) VALUES('SERIES','series-v65','{}'),('FORMAT','epub','{}')");
        jdbc.execute("PRAGMA foreign_keys=ON");
        jdbc.update("DELETE FROM books WHERE id='book-v65'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_preference_overrides WHERE scope_type='BOOK' AND scope_key='book-v65'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reader_preference_overrides WHERE scope_type IN ('SERIES','FORMAT')", Integer.class)).isEqualTo(2);
    }
}
