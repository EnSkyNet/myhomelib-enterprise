package com.myhomelibcorp.infrastructure.persistence.sqlite;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the global metadata database schema used by Organizer crash-safe journaling. */
class MetadataDatabaseMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void currentMetadataSchemaContainsAllOrganizerJournalAuditColumns() {
        Path db = tempDir.resolve("metadata.db");
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration_meta")
                .load();

        flyway.migrate();

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Set<String> columns = jdbc.queryForList("PRAGMA table_info(library_organization_items)").stream()
                .map(row -> row.get("name"))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toSet());

        assertThat(columns).contains(
                "operation_id", "item_id", "source_path", "source_entry", "target_path",
                "source_sha256", "target_sha256", "action", "state", "backup_path",
                "optimization_profile", "size_before", "size_after", "bytes_saved", "percent_saved",
                "optimization_summary_json", "target_standard", "device_profile", "compression_backend",
                "validation_status", "error_code", "error_message");

        Set<String> collectionColumns = jdbc.queryForList("PRAGMA table_info(collections)").stream()
                .map(row -> row.get("name"))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertThat(collectionColumns).contains("owns_database");

        Set<String> watcherIndices = jdbc.queryForList("PRAGMA index_list('incoming_folder_file')").stream()
                .map(row -> row.get("name"))
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertThat(watcherIndices).contains("idx_incoming_folder_waiting_replay");
        java.util.List<String> orderedColumns = jdbc.queryForList(
                        "PRAGMA index_info('idx_incoming_folder_waiting_replay')").stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
        assertThat(orderedColumns).containsExactly("status", "collection_id", "file_path");
    }
}
