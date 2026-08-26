package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.catalog.CatalogSyncSession;
import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.infrastructure.cache.DictionaryCache;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    private AuthorRepository authorRepository;

    @Mock
    private GenreRepository genreRepository;

    @Mock
    private DictionaryCache dictionaryCache;

    @Mock
    private CatalogUpdateTrackingPort catalogUpdateTrackingPort;

    private InpxImportPipeline pipeline;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // За замовчуванням колекція не активна
        when(collectionManager.hasActiveCollection()).thenReturn(false);
        pipeline = new InpxImportPipeline(
                reader,
                batchWriter,
                bulkOptimizer,
                collectionManager,
                authorRepository,
                genreRepository,
                dictionaryCache,
                catalogUpdateTrackingPort
        );
    }

    @Test
    void testImportFile_ShouldReturnZero_WhenReaderReturnsEmpty(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("test.inpx");
        java.nio.file.Files.createFile(testFile);

        when(reader.read(testFile)).thenReturn(Collections.emptyIterator());

        long result = pipeline.importFile(testFile, 100, tempDir);

        assertThat(result).isZero();
        verify(bulkOptimizer).enableBulkInsertMode();
        verify(bulkOptimizer).disableBulkInsertMode();
        verify(authorRepository, never()).findAll();
        // Перевіряємо, що методи dropIndexes/createIndexes не викликали JdbcTemplate (бо колекція не активна)
        verify(collectionManager, never()).getCurrentJdbcTemplate();
    }

    @Test
    void testImportFile_ShouldHandleNullRootDirectory() throws Exception {
        Path testFile = java.nio.file.Files.createTempFile("test", ".inpx");
        when(reader.read(testFile)).thenReturn(Collections.emptyIterator());

        long result = pipeline.importFile(testFile, 100, null);

        assertThat(result).isZero();
    }
    @Test
    void importWithResultReportsPreparationAndCompletionWithoutLoadingAllAuthors(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("progress.inpx");
        java.nio.file.Files.createFile(testFile);
        when(reader.count(eq(testFile), any())).thenReturn(0L);
        when(reader.read(testFile)).thenReturn(Collections.emptyIterator());

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
        verify(authorRepository, never()).findAll();
    }

    @Test
    void deltaCatalogImportDoesNotMarkAllExistingBooksMissing(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("extra.inpx");
        java.nio.file.Files.createFile(testFile);
        when(reader.count(eq(testFile), any())).thenReturn(0L);
        when(reader.read(testFile)).thenReturn(Collections.emptyIterator());
        when(genreRepository.findAll()).thenReturn(List.of());
        when(collectionManager.hasActiveCollection()).thenReturn(true);
        when(collectionManager.getCurrentDataSource()).thenReturn(null);
        when(collectionManager.getCurrentJdbcTemplate()).thenReturn(mock(JdbcTemplate.class));
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
    void fullCatalogImportMarksPreviouslyTrackedBooksMissing(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("full.inpx");
        java.nio.file.Files.createFile(testFile);
        when(reader.count(eq(testFile), any())).thenReturn(0L);
        when(reader.read(testFile)).thenReturn(Collections.emptyIterator());
        when(genreRepository.findAll()).thenReturn(List.of());
        when(collectionManager.hasActiveCollection()).thenReturn(true);
        when(collectionManager.getCurrentDataSource()).thenReturn(null);
        when(collectionManager.getCurrentJdbcTemplate()).thenReturn(mock(JdbcTemplate.class));
        CatalogSyncSession session = new CatalogSyncSession(
                "remote-42", "remote-collection:42", 3L, "fingerprint", false, true);
        when(catalogUpdateTrackingPort.beginSync(eq("remote-collection:42"), anyString(), anyString()))
                .thenReturn(session);

        pipeline.importFileWithResult(
                testFile, 5000, tempDir, null, "remote-collection:42",
                "https://alex80.github.io/mhl/update/flibusta_online_fb2.zip",
                true, null, null);

        verify(catalogUpdateTrackingPort).markTrackedBooksMissing(session);
    }

}