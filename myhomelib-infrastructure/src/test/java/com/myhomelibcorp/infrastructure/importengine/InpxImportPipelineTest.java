package com.myhomelibcorp.infrastructure.importengine;

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

import java.nio.file.Path;
import java.util.Collections;

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
}