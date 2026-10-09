package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression coverage for V69: every registered extension keeps the canonical search format. */
class CanonicalBookFormatMigrationV69Test {

    @TempDir
    Path tempDir;

    @Test
    void repairsEveryRegisteredPhysicalExtensionWithoutProducingUnknown() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("v69-formats.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("68"))
                .load()
                .migrate();

        record Expected(String id, String fileName, String format) { }
        List<Expected> expected = new ArrayList<>();
        int index = 0;
        for (var format : SupportedFormatRegistry.standard().all()) {
            for (String extension : format.extensions()) {
                String id = "format-" + (++index);
                String fileName = "sample." + extension;
                jdbc.update("""
                        INSERT INTO books(id,title,file_name,folder,archive_entry,file_size,deleted,local,format)
                        VALUES (?,?,?,'','',1,0,1,'BROKEN')
                        """, id, fileName, fileName);
                expected.add(new Expected(id, fileName, format.searchFormat()));
            }
        }

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

        for (Expected item : expected) {
            assertThat(jdbc.queryForObject("SELECT format FROM books WHERE id=?", String.class, item.id()))
                    .as("V69 canonical format for %s", item.fileName())
                    .isEqualTo(item.format());
        }
    }
}
