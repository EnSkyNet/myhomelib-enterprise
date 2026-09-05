package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.catalog.CatalogSyncSession;
import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.author.AuthorNameKey;
import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InpxImportPipelineTest {

    @Mock
    private InpxReader reader;

    @Mock
    private JdbcBatchWriter batchWriter;

    @Mock
    private BulkImportOptimizer bulkOptimizer;

    @Mock
    private CollectionManager collectionManager;

    @Mock
    private CatalogUpdateTrackingPort catalogUpdateTrackingPort;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ImportIndexLifecycle importIndexLifecycle;

    private InpxImportPipeline pipeline;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // За замовчуванням колекція не активна
        when(collectionManager.hasActiveCollection()).thenReturn(false);

        // Мокуємо JdbcTemplate для buildGenreCache()
        when(collectionManager.getCurrentJdbcTemplate()).thenReturn(jdbcTemplate);

        // Мокуємо void метод query() - використовуємо doAnswer або doNothing
        doAnswer(invocation -> {
            // Викликаємо callback без даних (нічого не робимо)
            org.springframework.jdbc.core.RowCallbackHandler handler =
                    invocation.getArgument(1);
            // Не викликаємо handler, тому що немає даних
            return null;
        }).when(jdbcTemplate).query(
                eq("SELECT code FROM genres"),
                any(org.springframework.jdbc.core.RowCallbackHandler.class)
        );

        // Мокуємо queryForObject для перевірки наявності колекції
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM collections WHERE id=?"),
                eq(Integer.class),
                anyString()
        )).thenReturn(0);

        pipeline = new InpxImportPipeline(
                reader,
                batchWriter,
                bulkOptimizer,
                collectionManager,
                catalogUpdateTrackingPort,
                importIndexLifecycle
        );
    }

    @Test
    void testImportFile_ShouldReturnZero_WhenReaderReturnsEmpty(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("test.inpx");
        java.nio.file.Files.createFile(testFile);

        when(reader.read(testFile, false)).thenReturn(Collections.emptyIterator());

        long result = pipeline.importFile(testFile, 100, tempDir);

        assertThat(result).isZero();
        verify(bulkOptimizer).enableBulkInsertMode();
        verify(bulkOptimizer).disableBulkInsertMode();
    }

    @Test
    void testImportFile_ShouldHandleNullRootDirectory() throws Exception {
        Path testFile = java.nio.file.Files.createTempFile("test", ".inpx");
        when(reader.read(testFile, false)).thenReturn(Collections.emptyIterator());

        long result = pipeline.importFile(testFile, 100, null);

        assertThat(result).isZero();
    }

    @Test
    void importWithResultReportsPreparationAndCompletionWithoutLoadingAllAuthors(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("progress.inpx");
        java.nio.file.Files.createFile(testFile);
        when(reader.count(eq(testFile), any(), eq(false))).thenReturn(0L);
        when(reader.read(testFile, false)).thenReturn(Collections.emptyIterator());

        List<Double> progress = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        var result = pipeline.importFileWithResult(
                testFile, 100, tempDir, null, null, null, progress::add, statuses::add);

        assertThat(result.imported()).isZero();
        assertThat(progress).isNotEmpty();
        assertThat(progress.getFirst()).isEqualTo(0.0);
        assertThat(progress.getLast()).isEqualTo(1.0);
        assertThat(statuses).anyMatch(s -> s.contains("Підготовка INPX"));
        assertThat(statuses).anyMatch(s -> s.contains("Аналіз індексу"));
    }

    @Test
    void deltaCatalogImportDoesNotMarkAllExistingBooksMissing(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("extra.inpx");
        java.nio.file.Files.createFile(testFile);
        when(reader.count(eq(testFile), any(), eq(true))).thenReturn(0L);
        when(reader.read(testFile, true)).thenReturn(Collections.emptyIterator());
        when(collectionManager.hasActiveCollection()).thenReturn(true);
        when(collectionManager.getCurrentDataSource()).thenReturn(null);
        when(collectionManager.getCurrentJdbcTemplate()).thenReturn(jdbcTemplate);
        CatalogSyncSession session = new CatalogSyncSession(
                "remote-42", "remote-collection:42", 2L, "fingerprint", false, true);
        when(catalogUpdateTrackingPort.beginSync(eq("remote-collection:42"), anyString(), anyString()))
                .thenReturn(session);

        pipeline.importFileWithResult(
                testFile, 5000, tempDir, null, "remote-collection:42",
                "https://alex80.github.io/mhl/update/extra_flibusta_online_fb2.zip",
                false, null, null);

        verify(catalogUpdateTrackingPort, never()).markTrackedBooksMissing(any());
    }

    @Test
    void unchangedOnlineFullSnapshotSkipsReplayAndReportsNoChanges(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("unchanged.inpx");
        java.nio.file.Files.createFile(testFile);
        when(reader.count(eq(testFile), any(), eq(true))).thenReturn(562_307L);
        when(collectionManager.hasActiveCollection()).thenReturn(true);
        when(collectionManager.getCurrentDataSource()).thenReturn(null);
        when(collectionManager.getCurrentJdbcTemplate()).thenReturn(jdbcTemplate);
        CatalogSyncSession session = new CatalogSyncSession(
                "remote-42", "remote-collection:42", 7L, "same-fingerprint", false, false);
        when(catalogUpdateTrackingPort.beginSync(eq("remote-collection:42"), anyString(), anyString()))
                .thenReturn(session);

        List<String> statuses = new ArrayList<>();
        var result = pipeline.importFileWithResult(
                testFile, 5000, tempDir, null, "remote-collection:42",
                "https://alex80.github.io/mhl/download/inpx/", true, null, statuses::add);

        assertThat(result.imported()).isZero();
        assertThat(result.skipped()).isEqualTo(562_307L);
        assertThat(result.errors()).isZero();
        assertThat(result.changes().complete()).isTrue();
        assertThat(result.changes().insertedCount()).isZero();
        assertThat(result.changes().updatedCount()).isZero();
        assertThat(result.changes().deletedCount()).isZero();
        assertThat(statuses).anyMatch(s -> s.contains("не змінився"));
        verify(reader, never()).read(any(Path.class), anyBoolean());
        verifyNoInteractions(batchWriter);
        verify(catalogUpdateTrackingPort, never()).recordImportedBooks(any(), anyList());
        verify(importIndexLifecycle, never()).suspendForFullSnapshot(anyBoolean());
    }

    @Test
    void fullCatalogImportDoesNotInferDeletionFromAbsence(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("full.inpx");
        java.nio.file.Files.createFile(testFile);
        when(reader.count(eq(testFile), any(), eq(true))).thenReturn(0L);
        when(reader.read(testFile, true)).thenReturn(Collections.emptyIterator());
        when(collectionManager.hasActiveCollection()).thenReturn(true);
        when(collectionManager.getCurrentDataSource()).thenReturn(null);
        when(collectionManager.getCurrentJdbcTemplate()).thenReturn(jdbcTemplate);
        CatalogSyncSession session = new CatalogSyncSession(
                "remote-42", "remote-collection:42", 3L, "fingerprint", false, true);
        when(catalogUpdateTrackingPort.beginSync(eq("remote-collection:42"), anyString(), anyString()))
                .thenReturn(session);

        pipeline.importFileWithResult(
                testFile, 5000, tempDir, null, "remote-collection:42",
                "https://alex80.github.io/mhl/update/flibusta_online_fb2.zip",
                true, null, null);

        verify(catalogUpdateTrackingPort, never()).markTrackedBooksMissing(any());
    }

    @Test
    void catalogImportWithActiveCollection_andExistingData_succeeds(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("catalog.inpx");
        java.nio.file.Files.createFile(testFile);

        // Мокуємо активну колекцію
        when(collectionManager.hasActiveCollection()).thenReturn(true);
        when(collectionManager.getCurrentDataSource()).thenReturn(null);
        when(collectionManager.getCurrentJdbcTemplate()).thenReturn(jdbcTemplate);

        // Мокуємо count та read
        when(reader.count(eq(testFile), any(), eq(false))).thenReturn(5L);
        when(reader.read(testFile, false)).thenReturn(Collections.emptyIterator());

        // Мокуємо beginSync
        CatalogSyncSession session = new CatalogSyncSession(
                "source-1", "test-source", 1L, "fingerprint", true, true);
        when(catalogUpdateTrackingPort.beginSync(anyString(), anyString(), anyString()))
                .thenReturn(session);

        var result = pipeline.importFileWithResult(
                testFile, 100, tempDir, null, "test-source",
                "https://example.test/catalog.inpx", true, null, null);

        assertThat(result).isNotNull();
        verify(bulkOptimizer).enableBulkInsertMode();
        verify(bulkOptimizer).disableBulkInsertMode();
    }
    @Test
    void existingCatalogUsesConstantTimeExistenceProbeAndSkipsAuthorEmptyCheck(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("existing.inpx");
        java.nio.file.Files.createFile(testFile);
        when(collectionManager.hasActiveCollection()).thenReturn(true);
        when(collectionManager.getCurrentDataSource()).thenReturn(null);
        when(jdbcTemplate.queryForObject(
                eq("SELECT EXISTS(SELECT 1 FROM books LIMIT 1)"), eq(Integer.class))).thenReturn(1);
        when(reader.count(eq(testFile), any(), eq(false))).thenReturn(0L);
        when(reader.read(testFile, false)).thenReturn(Collections.emptyIterator());
        when(catalogUpdateTrackingPort.beginSync(anyString(), anyString(), anyString())).thenReturn(
                new CatalogSyncSession("source-1", "test-source", 2L, "fingerprint", false, true));

        pipeline.importFileWithResult(
                testFile, 1000, tempDir, null, "test-source",
                "https://example.test/catalog.inpx", true, null, null);

        verify(jdbcTemplate).queryForObject(
                eq("SELECT EXISTS(SELECT 1 FROM books LIMIT 1)"), eq(Integer.class));
        verify(jdbcTemplate, never()).queryForObject(
                eq("SELECT EXISTS(SELECT 1 FROM authors LIMIT 1)"), eq(Integer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void authorCacheEvictionForcesSafeDatabaseResolutionPath() throws Exception {
        Method cacheFactory = InpxImportPipeline.class.getDeclaredMethod("newBoundedAuthorCache", int.class);
        cacheFactory.setAccessible(true);
        Map<AuthorNameKey, String> cache = (Map<AuthorNameKey, String>) cacheFactory.invoke(null, 10_000);
        for (int i = 0; i <= 10_000; i++) {
            cache.put(new AuthorNameKey("First" + i, "", "Last" + i), "persistent-" + i);
        }

        Field authorCache = InpxImportPipeline.class.getDeclaredField("authorCache");
        authorCache.setAccessible(true);
        authorCache.set(pipeline, cache);
        Field genreCache = InpxImportPipeline.class.getDeclaredField("genreCache");
        genreCache.setAccessible(true);
        genreCache.set(pipeline, new HashMap<String, String>());

        AuthorNameKey key = new AuthorNameKey("Дмитрий", "", "Дорничев");
        Author candidate = new Author(key.firstName(), key.middleName(), key.lastName());
        Map<AuthorNameKey, Author> pendingAuthors = new LinkedHashMap<>();
        pendingAuthors.put(key, candidate);
        String persistentId = "persistent-dornichev";
        when(batchWriter.batchInsertAuthorsAndResolveIds(anyList())).thenReturn(
                Map.of(candidate.getId().asString(), persistentId));

        Method flush = InpxImportPipeline.class.getDeclaredMethod(
                "flush", List.class, Map.class, Map.class, CatalogSyncSession.class, boolean.class,
                ImportChangeAccumulator.class, boolean.class, boolean.class);
        flush.setAccessible(true);
        flush.invoke(pipeline, List.of(), pendingAuthors, new LinkedHashMap<String, com.myhomelibcorp.domain.model.genre.Genre>(),
                new CatalogSyncSession("source", "source", 1L, "fp", true, true), false,
                new ImportChangeAccumulator(100), true, true);

        verify(batchWriter).batchInsertAuthorsAndResolveIds(anyList());
        verify(batchWriter, never()).batchInsertAuthorsAndResolveIdsAssumingNew(anyList());
        assertThat(cache.get(key)).isEqualTo(persistentId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void authorPreloadRunsOnlyWhenWholeAuthorTableFitsBoundedCache() throws Exception {
        Method cacheFactory = InpxImportPipeline.class.getDeclaredMethod("newBoundedAuthorCache", int.class);
        cacheFactory.setAccessible(true);
        Map<AuthorNameKey, String> cache = (Map<AuthorNameKey, String>) cacheFactory.invoke(null, 250_000);
        Field authorCache = InpxImportPipeline.class.getDeclaredField("authorCache");
        authorCache.setAccessible(true);
        authorCache.set(pipeline, cache);
        Field cacheSize = InpxImportPipeline.class.getDeclaredField("authorCacheSize");
        cacheSize.setAccessible(true);
        cacheSize.setInt(pipeline, 250_000);

        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM authors", Long.class)).thenReturn(126_317L);

        Method preload = InpxImportPipeline.class.getDeclaredMethod("preloadAuthorCacheIfFits", boolean.class);
        preload.setAccessible(true);
        preload.invoke(pipeline, false);

        verify(jdbcTemplate).queryForObject("SELECT COUNT(*) FROM authors", Long.class);
        verify(jdbcTemplate).query(eq("SELECT id, first_name, middle_name, last_name FROM authors"),
                any(org.springframework.jdbc.core.RowCallbackHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void authorPreloadSkipsLargeCatalogAndKeepsIncrementalFallback() throws Exception {
        Method cacheFactory = InpxImportPipeline.class.getDeclaredMethod("newBoundedAuthorCache", int.class);
        cacheFactory.setAccessible(true);
        Map<AuthorNameKey, String> cache = (Map<AuthorNameKey, String>) cacheFactory.invoke(null, 250_000);
        Field authorCache = InpxImportPipeline.class.getDeclaredField("authorCache");
        authorCache.setAccessible(true);
        authorCache.set(pipeline, cache);
        Field cacheSize = InpxImportPipeline.class.getDeclaredField("authorCacheSize");
        cacheSize.setAccessible(true);
        cacheSize.setInt(pipeline, 250_000);

        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM authors", Long.class)).thenReturn(250_001L);

        Method preload = InpxImportPipeline.class.getDeclaredMethod("preloadAuthorCacheIfFits", boolean.class);
        preload.setAccessible(true);
        preload.invoke(pipeline, false);

        verify(jdbcTemplate).queryForObject("SELECT COUNT(*) FROM authors", Long.class);
        verify(jdbcTemplate, never()).query(eq("SELECT id, first_name, middle_name, last_name FROM authors"),
                any(org.springframework.jdbc.core.RowCallbackHandler.class));
        assertThat(cache).isEmpty();
    }


    @Test
    void selectivePreviewGateOnlyAllowsLargeChangedOnlineFullSnapshotWithoutLocalFiles() throws Exception {
        Method gate = InpxImportPipeline.class.getDeclaredMethod(
                "shouldUseSelectivePreviewFastPath", boolean.class, boolean.class, boolean.class,
                long.class, java.util.Set.class);
        gate.setAccessible(true);

        assertThat(gate.invoke(null, true, true, false, 562_307L, java.util.Set.of())).isEqualTo(true);
        assertThat(gate.invoke(null, true, true, false, 100_000L, java.util.Set.of())).isEqualTo(true);
        assertThat(gate.invoke(null, true, true, false, 99_999L, java.util.Set.of())).isEqualTo(false);
        assertThat(gate.invoke(null, true, true, true, 562_307L, java.util.Set.of())).isEqualTo(false);
        assertThat(gate.invoke(null, true, false, false, 562_307L, java.util.Set.of())).isEqualTo(false);
        assertThat(gate.invoke(null, false, true, false, 562_307L, java.util.Set.of())).isEqualTo(false);
        assertThat(gate.invoke(null, true, true, false, 562_307L, java.util.Set.of(Path.of("book.zip"))))
                .isEqualTo(false);
        assertThat(gate.invoke(null, true, true, false, 562_307L, null)).isEqualTo(false);
    }

    @Test
    void authorPreloadGateOnlyAllowsLargeOnlineFullSnapshots() throws Exception {
        Method gate = InpxImportPipeline.class.getDeclaredMethod(
                "shouldPreloadAuthorCache", boolean.class, boolean.class, long.class);
        gate.setAccessible(true);

        assertThat(gate.invoke(null, true, true, 562_307L)).isEqualTo(true);
        assertThat(gate.invoke(null, true, true, 100_000L)).isEqualTo(true);
        assertThat(gate.invoke(null, true, true, 99_999L)).isEqualTo(false);
        assertThat(gate.invoke(null, true, false, 562_307L)).isEqualTo(false);
        assertThat(gate.invoke(null, false, true, 562_307L)).isEqualTo(false);
        assertThat(gate.invoke(null, false, false, 562_307L)).isEqualTo(false);
    }

}