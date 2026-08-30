package com.myhomelibcorp.infrastructure.catalog.importing;

import com.myhomelibcorp.application.catalog.CatalogSourceIdentity;
import com.myhomelibcorp.application.catalog.importing.*;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcCatalogImportAdapterTest {
    @TempDir Path temp;

    private JdbcTemplate jdbc;
    private MutableReader reader;
    private JdbcCatalogImportAdapter adapter;
    private Path source;
    private static final String SOURCE_KEY = "test:catalog";

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("catalog.db"));
        jdbc = new JdbcTemplate(ds);
        createSchema(jdbc);

        CollectionManager manager = mock(CollectionManager.class);
        when(manager.hasActiveCollection()).thenReturn(true);
        when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
        when(manager.getCurrentDataSource()).thenReturn(ds);

        reader = new MutableReader();
        adapter = new JdbcCatalogImportAdapter(List.of(reader), manager);
        source = temp.resolve("dataset.jsonl");
        Files.writeString(source, "dataset-v1", StandardCharsets.UTF_8);
    }

    @Test
    void sameNameAuthorsWithExternalIdsRemainDistinctAndPipeIsData() {
        CatalogPerson p1 = person("A|B", "", "Smith", "person", "1");
        CatalogPerson p2 = person("A|B", "", "Smith", "person", "2");
        reader.records = List.of(record("one", false, p1), record("two", false, p2));

        ImportResult result = adapter.importCatalog(context(true));

        assertThat(result.imported()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM authors", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM author_identities", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT first_name FROM authors ORDER BY id", String.class))
                .containsOnly("A|B");
    }

    @Test
    void deltaDoesNotDeleteAbsentBooksButExplicitDeleteDoes() throws Exception {
        reader.records = List.of(record("one", false, person("Ann", "", "One", "person", "1")),
                record("two", false, person("Bob", "", "Two", "person", "2")));
        adapter.importCatalog(context(true));

        rewriteSource("delta-1");
        reader.records = List.of(record("one", false, person("Ann", "", "One", "person", "1")));
        adapter.importCatalog(context(false));
        assertThat(deleted("two")).isZero();

        rewriteSource("delta-2-longer");
        reader.records = List.of(record("two", true, person("Bob", "", "Two", "person", "2")));
        ImportResult delta = adapter.importCatalog(context(false));
        assertThat(deleted("two")).isOne();
        assertThat(delta.changes().deleted()).contains(stableId("two"));
    }

    @Test
    void fullSnapshotMarksMissingSourceBookDeletedAndPreservesUserFields() throws Exception {
        reader.records = List.of(record("one", false, person("Ann", "", "One", "person", "1")),
                record("two", false, person("Bob", "", "Two", "person", "2")));
        adapter.importCatalog(context(true));
        jdbc.update("UPDATE books SET rate=5,progress=73,review='mine',local=1,file_name='downloaded.fb2' WHERE id=?", stableId("one"));

        rewriteSource("full-v2-longer");
        reader.records = List.of(record("one", false, person("Ann", "", "One", "person", "1")));
        adapter.importCatalog(context(true));

        assertThat(deleted("two")).isOne();
        Map<String, Object> user = jdbc.queryForMap("SELECT rate,progress,review,local,file_name FROM books WHERE id=?", stableId("one"));
        assertThat(((Number) user.get("rate")).intValue()).isEqualTo(5);
        assertThat(((Number) user.get("progress")).intValue()).isEqualTo(73);
        assertThat(user.get("review")).isEqualTo("mine");
        assertThat(((Number) user.get("local")).intValue()).isOne();
        assertThat(user.get("file_name")).isEqualTo("downloaded.fb2");
    }

    @Test
    void unchangedManifestSkipsReparse() {
        reader.records = List.of(record("one", false, person("Ann", "", "One", "person", "1")));
        ImportResult first = adapter.importCatalog(context(true));
        int opens = reader.opens;
        ImportResult second = adapter.importCatalog(context(true));

        assertThat(first.imported()).isOne();
        assertThat(second.imported()).isZero();
        assertThat(second.skipped()).isOne();
        assertThat(reader.opens).isEqualTo(opens);
    }

    @Test
    void incompatibleManifestForcesReparseEvenWhenFileMetadataIsUnchanged() {
        reader.records = List.of(record("one", false, person("Ann", "", "One", "person", "1")));
        adapter.importCatalog(context(true));
        int opens = reader.opens;
        jdbc.update("UPDATE catalog_manifests SET normalization_version='legacy-v7' WHERE source_key=?", SOURCE_KEY);

        ImportResult second = adapter.importCatalog(context(true));

        assertThat(second.imported()).isOne();
        assertThat(reader.opens).isEqualTo(opens + 1);
        assertThat(jdbc.queryForObject("SELECT manifest_schema FROM catalog_manifests WHERE source_key=?", String.class, SOURCE_KEY))
                .isEqualTo("mhl.catalog-manifest/2");
    }

    @Test
    void preservesDatasetProvenanceRelationsAndAllArtifactOccurrences() {
        reader.datasetMetadata = Map.of(
                "generator.name", "metabib", "generator.version", "1.2.3",
                "normalization.model", "metabib.norm/1", "database.format", "flibusta-current",
                "ordering.json", "{\"mode\":\"archive_entry\"}",
                "processing.json", "{\"parse_fb2\":true}",
                "metabib.header.json", "{\"schema\":\"metabib.dataset/1\"}");
        Map<String, String> sourceMetadata = Map.ofEntries(
                Map.entry("dataset", "dataset-1"), Map.entry("locator.kind", "archive_entry"),
                Map.entry("locator.source", "archive.zip"), Map.entry("locator.index", "7"),
                Map.entry("metabib.record.schema", "metabib.dataset_record/1"),
                Map.entry("metabib.record.json", "{\"schema\":\"metabib.dataset_record/1\"}"),
                Map.entry("metabib.observations.json", "[]"), Map.entry("metabib.claims.json", "{}"),
                Map.entry("metabib.relation.count", "1"), Map.entry("metabib.relation.0.type", "replaced_by"),
                Map.entry("metabib.relation.0.observation", "obs-1"),
                Map.entry("metabib.relation.0.target.scheme", "flibusta:book_id"),
                Map.entry("metabib.relation.0.target.value", "42"),
                Map.entry("metabib.relation.0.raw.json", "{\"type\":\"replaced_by\"}"));
        Map<String, String> artifactMetadata = Map.ofEntries(
                Map.entry("occurrence.count", "2"),
                Map.entry("occurrence.0.archive", "a.zip"), Map.entry("occurrence.0.entry", "1.fb2"),
                Map.entry("occurrence.0.index", "1"), Map.entry("occurrence.0.compressed_size", "100"),
                Map.entry("occurrence.0.uncompressed_size", "200"),
                Map.entry("occurrence.1.archive", "b.zip"), Map.entry("occurrence.1.entry", "1.fb2"),
                Map.entry("occurrence.1.index", "4"));
        CatalogRecord record = new CatalogRecord("one", "Title", List.of(person("Ann", "", "One", "person", "1")),
                "", null, List.of("fiction"), "eng", "fb2", "1.fb2", "a.zip", "1.fb2", 200L, false,
                "9780306406157", "Publisher", 2024, "City", List.of(), "Annotation", List.of(), 4.0,
                sourceMetadata, List.of(new CatalogArtifact("1.fb2", "application/fb2+xml", "fb2", "a.zip", "1.fb2",
                200L, "", "fp", artifactMetadata)), List.of(), List.of());
        reader.records = List.of(record);

        adapter.importCatalog(context(true));

        assertThat(jdbc.queryForObject("SELECT normalization_model FROM catalog_dataset_metadata", String.class))
                .isEqualTo("metabib.norm/1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_record_provenance", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT relation_type FROM book_source_relations", String.class)).isEqualTo("replaced_by");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM artifact_occurrences", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT uncompressed_size FROM artifact_occurrences WHERE archive_name='a.zip'", Long.class))
                .isEqualTo(200L);
    }

    private ImportContext context(boolean full) {
        return ImportContext.builder()
                .file(source)
                .rootDirectory(temp)
                .catalogSourceKey(SOURCE_KEY)
                .catalogSourceLocation("https://example.invalid/catalog")
                .catalogFullSnapshot(full)
                .indexAfterSave(false)
                .batchSize(2)
                .build();
    }

    private int deleted(String sourceBookId) {
        return jdbc.queryForObject("SELECT deleted FROM books WHERE id=?", Integer.class, stableId(sourceBookId));
    }

    private String stableId(String sourceBookId) {
        String sourceId = CatalogSourceIdentity.stableId(SOURCE_KEY);
        return UUID.nameUUIDFromBytes(("catalog-book:" + sourceId + ":" + sourceBookId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void rewriteSource(String value) throws Exception {
        Thread.sleep(3); // ensure mtime changes even on coarse test filesystems; content/size also changes in these tests.
        Files.writeString(source, value, StandardCharsets.UTF_8);
    }

    private static CatalogPerson person(String first, String middle, String last, String scheme, String value) {
        return new CatalogPerson(first, middle, last, "", last + " " + first, "",
                List.of(new ExternalIdentity(scheme, value)));
    }

    private static CatalogRecord record(String id, boolean deleted, CatalogPerson author) {
        return new CatalogRecord(id, "Title " + id, List.of(author), "", null, List.of("fiction"), "eng",
                "fb2", id + ".fb2", "", "", 123L, deleted, "9780306406157", "Publisher", 2024,
                "City", List.of(), "Annotation", List.of("keyword"), 4.0, Map.of(),
                List.of(new CatalogArtifact(id + ".fb2", "application/fb2+xml", "fb2", "", "", 123L,
                        "", "", Map.of())), List.of(new ExternalIdentity("record", id)), List.of());
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE books(
                    id TEXT PRIMARY KEY,title TEXT NOT NULL,series TEXT,sequence_number INTEGER,file_name TEXT NOT NULL,
                    folder TEXT,archive_entry TEXT,language TEXT,file_size INTEGER,keywords TEXT,annotation TEXT,
                    rate INTEGER DEFAULT 0,progress INTEGER DEFAULT 0,update_date TEXT,isbn TEXT,deleted INTEGER DEFAULT 0,
                    local INTEGER DEFAULT 0,review TEXT DEFAULT '',created_at TEXT,collection_root TEXT,year INTEGER,
                    publisher TEXT,lib_id TEXT,library_rate INTEGER DEFAULT 0,translators TEXT,city TEXT,source_url TEXT,
                    format TEXT,author_sort TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE authors(id TEXT PRIMARY KEY,first_name TEXT,middle_name TEXT,last_name TEXT,search_name TEXT,
                    nickname TEXT,display_name TEXT,disambiguation TEXT)
                """);
        jdbc.execute("CREATE INDEX idx_authors_name_lookup ON authors(first_name,middle_name,last_name)");
        jdbc.execute("CREATE TABLE book_authors(book_id TEXT NOT NULL,author_id TEXT NOT NULL,PRIMARY KEY(book_id,author_id))");
        jdbc.execute("CREATE TABLE genres(code TEXT PRIMARY KEY,name TEXT,parent_code TEXT,fb2_code TEXT)");
        jdbc.execute("CREATE TABLE book_genres(book_id TEXT NOT NULL,genre_code TEXT NOT NULL,PRIMARY KEY(book_id,genre_code))");
        jdbc.execute("""
                CREATE TABLE catalog_sources(source_id TEXT PRIMARY KEY,source_key TEXT NOT NULL UNIQUE,source_location TEXT,
                    source_revision INTEGER NOT NULL DEFAULT 1,source_fingerprint TEXT NOT NULL,profile_type TEXT,dataset_schema TEXT,
                    last_synced_at TEXT DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE catalog_manifests(source_key TEXT PRIMARY KEY,source_path TEXT,size_bytes INTEGER NOT NULL DEFAULT 0,
                    mtime_millis INTEGER NOT NULL DEFAULT 0,fingerprint TEXT,parser_version TEXT NOT NULL,record_count INTEGER,
                    dataset_schema TEXT,last_parsed_at TEXT DEFAULT CURRENT_TIMESTAMP,manifest_schema TEXT NOT NULL DEFAULT '',
                    importer_version TEXT NOT NULL DEFAULT '',source_format TEXT NOT NULL DEFAULT '',
                    normalization_version TEXT NOT NULL DEFAULT '',fingerprint_model TEXT NOT NULL DEFAULT '',
                    fingerprint_version INTEGER NOT NULL DEFAULT 0,processing_flags TEXT NOT NULL DEFAULT '',
                    features_enabled TEXT NOT NULL DEFAULT '',dataset_normalization_model TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE author_identities(author_id TEXT NOT NULL,source_id TEXT NOT NULL,scheme TEXT NOT NULL,external_id TEXT NOT NULL,
                    PRIMARY KEY(source_id,scheme,external_id))
                """);
        jdbc.execute("""
                CREATE TABLE book_identities(book_id TEXT NOT NULL,source_id TEXT NOT NULL,scheme TEXT NOT NULL,external_id TEXT NOT NULL,
                    PRIMARY KEY(source_id,scheme,external_id))
                """);
        jdbc.execute("""
                CREATE TABLE book_artifacts(artifact_id TEXT PRIMARY KEY,book_id TEXT NOT NULL,source_id TEXT,artifact_name TEXT NOT NULL,
                    media_type TEXT,file_format TEXT,file_name TEXT,archive_name TEXT,archive_entry TEXT,size_bytes INTEGER,sha256 TEXT,
                    content_fingerprint TEXT,remote INTEGER NOT NULL DEFAULT 0,local INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP,updated_at TEXT DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE catalog_dataset_metadata(source_id TEXT PRIMARY KEY,dataset_id TEXT,dataset_schema TEXT,record_schema TEXT,
                    library TEXT,generator_name TEXT,generator_version TEXT,normalization_model TEXT,database_id TEXT,database_format TEXT,
                    dump_date TEXT,dump_checksum TEXT,database_dumps_json TEXT,ordering_json TEXT,processing_json TEXT,archives_json TEXT,
                    features_json TEXT,raw_header_json TEXT,imported_at TEXT DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE catalog_record_provenance(source_id TEXT NOT NULL,book_id TEXT NOT NULL,dataset_id TEXT,source_book_id TEXT NOT NULL,
                    record_schema TEXT,locator_kind TEXT,locator_source TEXT,locator_value TEXT,raw_record_json TEXT,observations_json TEXT,
                    claims_json TEXT,identities_json TEXT,artifacts_json TEXT,imported_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(source_id,book_id))
                """);
        jdbc.execute("""
                CREATE TABLE book_source_relations(relation_id TEXT PRIMARY KEY,source_id TEXT NOT NULL,book_id TEXT NOT NULL,
                    relation_index INTEGER NOT NULL,relation_type TEXT NOT NULL,observation_id TEXT,target_scheme TEXT,target_value TEXT,
                    event_id TEXT,event_time TEXT,participants_json TEXT,raw_relation_json TEXT,imported_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(source_id,book_id,relation_index))
                """);
        jdbc.execute("CREATE TABLE book_artifact_metadata(artifact_id TEXT NOT NULL,metadata_key TEXT NOT NULL,metadata_value TEXT,PRIMARY KEY(artifact_id,metadata_key))");
        jdbc.execute("""
                CREATE TABLE artifact_occurrences(occurrence_id TEXT PRIMARY KEY,artifact_id TEXT NOT NULL,source_id TEXT NOT NULL,book_id TEXT NOT NULL,
                    occurrence_index INTEGER NOT NULL,archive_name TEXT NOT NULL,entry_name TEXT NOT NULL,archive_index INTEGER,
                    compressed_size INTEGER,uncompressed_size INTEGER,modified_at TEXT,UNIQUE(artifact_id,occurrence_index))
                """);
    }

    private static final class MutableReader implements CatalogReader {
        List<CatalogRecord> records = List.of();
        Map<String, String> datasetMetadata = Map.of();
        int opens;

        @Override public boolean supports(Path source) { return source != null && source.toString().endsWith(".jsonl"); }
        @Override public CatalogReadSession open(Path source) {
            opens++;
            List<CatalogRecord> snapshot = List.copyOf(records);
            return new CatalogReadSession() {
                int index;
                @Override public CatalogDatasetInfo dataset() {
                    return new CatalogDatasetInfo("metabib.dataset/1", "metabib.dataset_record/1", "test", "test", (long) snapshot.size(), datasetMetadata);
                }
                @Override public boolean hasNext() { return index < snapshot.size(); }
                @Override public CatalogRecord next() { if (!hasNext()) throw new NoSuchElementException(); return snapshot.get(index++); }
                @Override public void close() { }
            };
        }
        @Override public String formatName() { return "test"; }
    }
}
