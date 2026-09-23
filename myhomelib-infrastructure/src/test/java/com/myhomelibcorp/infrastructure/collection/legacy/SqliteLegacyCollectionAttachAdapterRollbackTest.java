package com.myhomelibcorp.infrastructure.collection.legacy;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqliteLegacyCollectionAttachAdapterRollbackTest {
    @TempDir Path temp;

    @Test
    void modernAttachInitializationFailureRemovesMetadataWithoutDeletingExternalDatabase() throws Exception {
        Path source = temp.resolve("modern.db");
        createModernDatabase(source);
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        CollectionManager manager = mock(CollectionManager.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        Collection previous = collection("previous", temp.resolve("previous.db"));
        AtomicReference<Collection> created = new AtomicReference<>();
        when(repository.save(any(Collection.class))).thenAnswer(invocation -> {
            Collection value = invocation.getArgument(0);
            created.set(value);
            return value;
        });
        when(lifecycle.getCurrentCollection()).thenReturn(previous, previous);
        doThrow(new IllegalStateException("switch failed"))
                .when(lifecycle).initializeCollection(any(Collection.class), eq(true));

        var adapter = new SqliteLegacyCollectionAttachAdapter(repository, lifecycle, manager, storage);

        assertThatThrownBy(() -> adapter.attach(source, "Modern", temp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("switch failed");

        verify(repository).deleteById(created.get().getId());
        verify(storage).closeCollection(created.get());
        verify(storage, never()).deletePhysicalFiles(any());
        verify(lifecycle, never()).closeCollection();
    }

    @Test
    void legacyMigrationFailureRestoresLifecycleAndDeletesOwnedDatabaseBeforeMetadata() throws Exception {
        Path source = temp.resolve("legacy.hlc2");
        createLegacyDatabase(source);
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        CollectionManager manager = mock(CollectionManager.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        AtomicReference<Collection> created = new AtomicReference<>();
        when(repository.save(any(Collection.class))).thenAnswer(invocation -> {
            Collection value = invocation.getArgument(0);
            created.set(value);
            return value;
        });
        when(lifecycle.getCurrentCollection()).thenAnswer(invocation -> created.get());
        when(manager.getCurrentJdbcTemplate()).thenThrow(new IllegalStateException("no target connection"));

        var adapter = new SqliteLegacyCollectionAttachAdapter(repository, lifecycle, manager, storage);

        assertThatThrownBy(() -> adapter.attach(source, "Legacy", temp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no target connection");

        verify(lifecycle).closeCollection();
        verify(storage).closeCollection(created.get());
        verify(storage).deletePhysicalFiles(created.get());
        verify(repository).deleteById(created.get().getId());
    }

    private static Collection collection(String id, Path db) {
        return new Collection(id, id, null, db.toString(), 0, null, null, null, null);
    }

    private static void createModernDatabase(Path path) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE books (id TEXT PRIMARY KEY, file_name TEXT)");
            statement.execute("CREATE TABLE authors (id TEXT PRIMARY KEY)");
            statement.execute("CREATE TABLE genres (code TEXT PRIMARY KEY)");
        }
    }

    private static void createLegacyDatabase(Path path) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE Books (BookID INTEGER PRIMARY KEY, Title TEXT)");
        }
    }
}
