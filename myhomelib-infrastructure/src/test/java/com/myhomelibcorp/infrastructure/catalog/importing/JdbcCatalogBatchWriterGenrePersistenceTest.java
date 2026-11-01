package com.myhomelibcorp.infrastructure.catalog.importing;

import com.myhomelibcorp.application.catalog.importing.CatalogGenreDefinition;
import com.myhomelibcorp.application.catalog.importing.CatalogRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcCatalogBatchWriterGenrePersistenceTest {

    @Test
    void persistsCatalogDescriptionsAndCategoriesWithoutOverwritingExistingDefinitions() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("""
                    CREATE TABLE genres (
                        code TEXT PRIMARY KEY,
                        name TEXT,
                        parent_code TEXT,
                        fb2_code TEXT
                    )
                    """);
            jdbc.update("""
                    INSERT INTO genres(code,name,parent_code,fb2_code)
                    VALUES ('mhl-2-sf','Existing label','existing-root','existing-code')
                    """);

            CatalogRecord record = record(
                    List.of("mhl-1-sf", "mhl-2-sf", "legacy-code"),
                    List.of(
                            new CatalogGenreDefinition("mhl-1-sf", "Фантастика", "Література", "sf", true),
                            new CatalogGenreDefinition("mhl-2-sf", "Інша фантастика", "Література", "sf", true)));

            JdbcCatalogBatchWriter.persistGenres(jdbc, List.of(record));

            String categoryCode = jdbc.queryForObject(
                    "SELECT code FROM genres WHERE name=? AND parent_code IS NULL", String.class, "Література");
            assertThat(categoryCode).isNotBlank();

            Map<String, Object> imported = jdbc.queryForMap(
                    "SELECT name,parent_code,fb2_code FROM genres WHERE code=?", "mhl-1-sf");
            assertThat(imported).containsEntry("name", "Фантастика")
                    .containsEntry("parent_code", categoryCode)
                    .containsEntry("fb2_code", "mhl-1-sf");

            Map<String, Object> existing = jdbc.queryForMap(
                    "SELECT name,parent_code,fb2_code FROM genres WHERE code=?", "mhl-2-sf");
            assertThat(existing).containsEntry("name", "Existing label")
                    .containsEntry("parent_code", "existing-root")
                    .containsEntry("fb2_code", "existing-code");

            Map<String, Object> legacy = jdbc.queryForMap(
                    "SELECT name,parent_code,fb2_code FROM genres WHERE code=?", "legacy-code");
            assertThat(legacy).containsEntry("name", "legacy-code")
                    .containsEntry("fb2_code", "legacy-code");
            assertThat(legacy.get("parent_code")).isNull();
        }
    }

    @Test
    void fb2OnlyGenreDoesNotCreateAnUnverifiedCatalogCategory() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("""
                    CREATE TABLE genres (
                        code TEXT PRIMARY KEY,
                        name TEXT,
                        parent_code TEXT,
                        fb2_code TEXT
                    )
                    """);

            CatalogRecord record = record(
                    List.of("sf"),
                    List.of(new CatalogGenreDefinition("sf", "FB2 label", "FB2 category", "", false)));
            JdbcCatalogBatchWriter.persistGenres(jdbc, List.of(record));

            Map<String, Object> genre = jdbc.queryForMap(
                    "SELECT name,parent_code,fb2_code FROM genres WHERE code=?", "sf");
            assertThat(genre).containsEntry("name", "sf").containsEntry("fb2_code", "sf");
            assertThat(genre.get("parent_code")).isNull();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM genres WHERE name=?", Integer.class, "FB2 category")).isZero();
        }
    }

    private static CatalogRecord record(List<String> genres, List<CatalogGenreDefinition> definitions) {
        return new CatalogRecord(
                "source-book-1", "Genre persistence sample", List.of(), "", null,
                genres, definitions, "", "", "", "", "", 0L, false, "", "",
                null, "", List.of(), "", List.of(), null, null, Map.of(),
                List.of(), List.of(), List.of());
    }
}
