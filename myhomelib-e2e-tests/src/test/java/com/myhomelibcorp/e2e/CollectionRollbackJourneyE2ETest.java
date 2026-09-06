package com.myhomelibcorp.e2e;

import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.catalog.CollectionInfoPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.collection.CreateCollectionUseCase;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteCollectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionRollbackJourneyE2ETest {

    @TempDir
    Path tempDir;

    @Test
    void collectionMetadataRoundTripEncryptsCredentialInRealSqlite() {
        Db metadata = metadataDb(tempDir.resolve("meta.db"));
        SqliteCollectionRepository repository = new SqliteCollectionRepository(metadata.jdbc());
        Path libraryDb = tempDir.resolve("library.db");

        Collection saved = repository.save(new Collection(
                null, "E2E Collection", tempDir.resolve("books"), libraryDb.toString(), 1,
                "reader", "plain-secret", "https://catalog.example.test", "notes", "script"));

        String rawPassword = metadata.jdbc().queryForObject(
                "SELECT password FROM collections WHERE id=?", String.class, saved.getId());
        assertThat(rawPassword).startsWith("mhlenc:v1:").doesNotContain("plain-secret");
        assertThat(saved.isPasswordEncrypted()).isTrue();
        assertThat(saved.getDecryptedPassword()).isEqualTo("plain-secret");

        Collection reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("E2E Collection");
        assertThat(reloaded.getDecryptedPassword()).isEqualTo("plain-secret");
        assertThat(reloaded.getUrl()).isEqualTo("https://catalog.example.test");
    }

    @Test
    void failedImportDuringCollectionCreationRollsBackMetadataAndPhysicalDatabase() throws Exception {
        Db metadata = metadataDb(tempDir.resolve("rollback-meta.db"));
        SqliteCollectionRepository repository = new SqliteCollectionRepository(metadata.jdbc());
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        ImportFileUseCase importer = mock(ImportFileUseCase.class);
        CollectionInfoPort info = path -> Optional.empty();
        Path physicalDb = tempDir.resolve("failed-create.db");
        Path source = tempDir.resolve("broken.fb2");
        Files.writeString(source, "not a valid import payload");

        when(lifecycle.getCurrentCollection()).thenReturn(null);
        doAnswer(invocation -> {
            Collection collection = invocation.getArgument(0);
            Path db = Path.of(collection.getDbFile());
            Files.createDirectories(db.getParent());
            Files.writeString(db, "initialized");
            return null;
        }).when(lifecycle).initializeCollection(any(Collection.class), any(Boolean.class));
        doThrow(new IllegalStateException("simulated import failure")).when(importer).execute(any());

        CollectionStorageManager storage = new CollectionStorageManager() {
            @Override public void closeCollection(Collection collection) { }
            @Override public void deletePhysicalFiles(Collection collection) {
                try {
                    if (collection.getDbFile() != null) Files.deleteIfExists(Path.of(collection.getDbFile()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            @Override public void vacuumCurrent() { }
        };

        CreateCollectionUseCase useCase = new CreateCollectionUseCase(
                repository, lifecycle, importer, info, new LibraryOperationCoordinator(), storage);
        CreateCollectionRequest request = CreateCollectionRequest.builder()
                .name("Rollback Collection")
                .rootFolder(tempDir.resolve("books"))
                .dbFile(physicalDb)
                .sourcePath(source.toString())
                .importOnCreate(true)
                .createIndex(true)
                .build();

        assertThatThrownBy(() -> useCase.execute(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated import failure");

        assertThat(repository.findAll()).isEmpty();
        assertThat(physicalDb).doesNotExist();
    }

    private static Db metadataDb(Path file) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE collections(
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    root_folder TEXT,
                    db_file TEXT,
                    type INTEGER NOT NULL DEFAULT 0,
                    user TEXT,
                    password TEXT,
                    url TEXT,
                    notes TEXT,
                    connection_script TEXT
                )
                """);
        return new Db(ds, jdbc);
    }

    private record Db(SQLiteDataSource dataSource, JdbcTemplate jdbc) { }
}
