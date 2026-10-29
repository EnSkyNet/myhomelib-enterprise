package com.myhomelibcorp.infrastructure.catalog.importing;

import com.myhomelibcorp.application.catalog.CatalogSourceIdentity;
import com.myhomelibcorp.application.catalog.importing.*;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.context.StaleImportSourceException;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.shared.util.Sha256Support;
import org.flywaydb.core.Flyway;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

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
    void untrustedHugeRequestedBatchSizeIsCappedWithoutLosingRecords() {
        // Previously ArrayList<>(Integer.MAX_VALUE) could exhaust the heap
        // before the first book was read. A 4 GiB machine cannot afford a
        // caller-supplied unbounded import buffer.
        reader.records = List.of(
                record("huge-batch-one", false, person("Ann", "", "One", "person", "1")),
                record("huge-batch-two", false, person("Bob", "", "Two", "person", "2")));
        ImportContext context = ImportContext.builder()
                .file(source)
                .rootDirectory(temp)
                .catalogSourceKey(SOURCE_KEY)
                .catalogFullSnapshot(true)
                .indexAfterSave(false)
                .batchSize(Integer.MAX_VALUE)
                .build();

        ImportResult result = adapter.importCatalog(context);

        assertThat(result.imported()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM books", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_manifests", Integer.class)).isOne();
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
    void unsafeCatalogResourcePathsAreNotPersistedAsFilesystemAuthorities() {
        CatalogPerson author = person("Ann", "", "One", "person", "1");
        CatalogRecord traversal = new CatalogRecord("escape", "Escape", List.of(author), "", null, List.of("fiction"),
                "eng", "fb2", "../outside.fb2", "../outside.zip", "../entry.fb2", 123L, false,
                "", "", null, "", List.of(), "", List.of(), null, null, Map.of(),
                List.of(), List.of(new ExternalIdentity("record", "escape")), List.of());
        reader.records = List.of(traversal);

        adapter.importCatalog(context(true));

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT file_name, folder, archive_entry FROM books WHERE id=?", stableId("escape"));
        assertThat(stored.get("file_name")).isEqualTo("");
        assertThat(stored.get("folder")).isEqualTo("");
        assertThat(stored.get("archive_entry")).isEqualTo("");
    }


    @Test
    void cancellationFromFinalProgressCallbackRollsBackBooksAndManifest() {
        reader.records = List.of(record("cancel-before-commit", false,
                person("Ann", "", "One", "person", "1")));
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
        ImportContext context = ImportContext.builder()
                .file(source)
                .rootDirectory(temp)
                .catalogSourceKey(SOURCE_KEY)
                .catalogFullSnapshot(true)
                .indexAfterSave(false)
                .batchSize(1000)
                .cancelFlag(cancelled)
                .progressListener(value -> {
                    callbacks.incrementAndGet();
                    cancelled.set(true);
                })
                .build();

        ImportResult result = adapter.importCatalog(context);

        assertThat(callbacks.get()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(
                com.myhomelibcorp.application.imports.statistics.ImportStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM books", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_manifests", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_sources", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
    }

    @Test
    void watcherCatalogChangeDuringStreamingRollsBackWholeTransaction() throws Exception {
        reader.records = List.of(
                record("one", false, person("Ann", "", "One", "person", "1")),
                record("two", false, person("Bob", "", "Two", "person", "2")),
                record("three", false, person("Cat", "", "Three", "person", "3")));
        String readyFingerprint = Sha256Support.file(source);
        reader.onFirstNext = () -> {
            try {
                Files.writeString(source, "dataset-changed-during-import", StandardCharsets.UTF_8);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };

        ImportContext watcher = ImportContext.builder()
                .file(source)
                .rootDirectory(temp)
                .catalogSourceKey(SOURCE_KEY)
                .catalogFullSnapshot(true)
                .indexAfterSave(false)
                .batchSize(2)
                .expectedSourceSha256(readyFingerprint)
                .build();

        assertThatThrownBy(() -> adapter.importCatalog(watcher))
                .isInstanceOf(StaleImportSourceException.class)
                .hasMessageContaining("changed during import");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM books", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_manifests", Integer.class)).isZero();
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
                "9780306406157", "Publisher", 2024, "City", List.of(), "Annotation", List.of(), 4.0, null,
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

    @Test
    void portableMetabibImportPreservesNumericSourceLibIdWithoutTrustingInvalidIdentityValues() {
        // Real group exports use mhl-portable as the dataset library and carry
        // the original LibID in identities.catalog, not locator.book_id.
        CatalogRecord valid = portableRecord("portable-valid", "893718");
        CatalogRecord invalid = portableRecord("portable-invalid", "not-a-number");
        reader.records = List.of(valid, invalid);

        ImportResult result = adapter.importCatalog(context(true));

        assertThat(result.imported()).isEqualTo(2);
        String originalBookId = UUID.nameUUIDFromBytes("inpx:libid:893718".getBytes(StandardCharsets.UTF_8))
                .toString();
        assertThat(jdbc.queryForObject("SELECT lib_id FROM books WHERE id=?", String.class, originalBookId))
                .isEqualTo("893718");
        assertThat(jdbc.queryForObject("SELECT lib_id FROM books WHERE id=?", String.class, stableId("portable-invalid")))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM books", Integer.class)).isEqualTo(2);
    }

    private static CatalogRecord portableRecord(String sourceBookId, String sourceLibId) {
        return new CatalogRecord(sourceBookId, "Portable " + sourceBookId, List.of(), "", null,
                List.of("fiction"), "eng", "fb2", sourceBookId + ".fb2",
                "books-0001.zip", sourceBookId + ".fb2", 7L, false, "", "", null, "",
                List.of(), "", List.of(), null, null,
                Map.of("library", "mhl-portable", "locator.kind", "archive_entry"),
                List.of(), List.of(new ExternalIdentity("catalog:mhl-source-libid", sourceLibId)), List.of());
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
                "City", List.of(), "Annotation", List.of("keyword"), 4.0, null, Map.of(),
                List.of(new CatalogArtifact(id + ".fb2", "application/fb2+xml", "fb2", "", "", 123L,
                        "", "", Map.of())), List.of(new ExternalIdentity("record", id)), List.of());
    }

    private static final class MutableReader implements CatalogReader {
        List<CatalogRecord> records = List.of();
        Map<String, String> datasetMetadata = Map.of();
        Runnable onFirstNext;
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
                @Override public CatalogRecord next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    CatalogRecord record = snapshot.get(index++);
                    if (index == 1 && onFirstNext != null) {
                        Runnable hook = onFirstNext;
                        onFirstNext = null;
                        hook.run();
                    }
                    return record;
                }
                @Override public void close() { }
            };
        }
        @Override public String formatName() { return "test"; }
    }
}
