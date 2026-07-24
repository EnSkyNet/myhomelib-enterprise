package com.myhomelibcorp.infrastructure.importengine;

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

    private InpxImportPipeline pipeline;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        pipeline = new InpxImportPipeline(
                reader,
                batchWriter,
                bulkOptimizer,
                collectionManager,
                authorRepository,
                genreRepository,
                dictionaryCache
        );
    }

    @Test
    void testImportFile_ShouldReturnZero_WhenReaderReturnsEmpty(@TempDir Path tempDir) throws Exception {
        // Arrange
        Path testFile = tempDir.resolve("test.inpx");
        // Створюємо порожній файл
        java.nio.file.Files.createFile(testFile);

        // Mock reader to return empty iterator
        when(reader.read(testFile)).thenReturn(java.util.Collections.emptyIterator());

        // Act
        long result = pipeline.importFile(testFile, 100, tempDir);

        // Assert
        assertThat(result).isZero();
        verify(bulkOptimizer).enableBulkInsertMode();
        verify(bulkOptimizer).disableBulkInsertMode();
    }

    @Test
    void testImportFile_ShouldHandleNullRootDirectory() throws Exception {
        // Arrange
        Path testFile = java.nio.file.Files.createTempFile("test", ".inpx");
        when(reader.read(testFile)).thenReturn(java.util.Collections.emptyIterator());

        // Act
        long result = pipeline.importFile(testFile, 100, null);

        // Assert
        assertThat(result).isZero();
    }
}