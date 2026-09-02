package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.dto.CreateCollectionRequest;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.catalog.CollectionInfoPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CreateCollectionUseCaseRollbackTest {

    @TempDir
    Path temp;

    @Test
    void invalidSourceIsRejectedBeforeMetadataIsCreated() {
        Fixture f = new Fixture();
        CreateCollectionRequest request = request(temp.resolve("missing.inpx"));

        assertThrows(IllegalArgumentException.class, () -> f.useCase.execute(request));

        verifyNoInteractions(f.repository);
    }

    @Test
    void importFailureRestoresPreviousCollectionAndRemovesFailedResources() throws Exception {
        Fixture f = new Fixture();
        Path source = Files.writeString(temp.resolve("source.inpx"), "test");
        Collection previous = collection("old", temp.resolve("old.db"));
        Collection failed = collection("new", temp.resolve("new.db"));
        when(f.lifecycle.getCurrentCollection()).thenReturn(previous);
        when(f.repository.save(any(Collection.class))).thenReturn(failed);
        when(f.collectionInfo.read(source.toAbsolutePath().normalize())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("import failed")).when(f.importer).execute(any());

        assertThrows(IllegalStateException.class, () -> f.useCase.execute(request(source)));

        verify(f.lifecycle).initializeCollection(failed, false);
        verify(f.lifecycle).initializeCollection(previous, true);
        verify(f.storage).closeCollection(failed);
        verify(f.storage).deletePhysicalFiles(failed);
        verify(f.repository).deleteById("new");
    }

    @Test
    void cleanupFailureKeepsMetadataForRecovery() throws Exception {
        Fixture f = new Fixture();
        Path source = Files.writeString(temp.resolve("source.inpx"), "test");
        Collection failed = collection("new", temp.resolve("new.db"));
        when(f.lifecycle.getCurrentCollection()).thenReturn(null);
        when(f.repository.save(any(Collection.class))).thenReturn(failed);
        when(f.collectionInfo.read(source.toAbsolutePath().normalize())).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("import failed")).when(f.importer).execute(any());
        doThrow(new IllegalStateException("cannot delete db")).when(f.storage).deletePhysicalFiles(failed);

        assertThrows(IllegalStateException.class, () -> f.useCase.execute(request(source)));

        verify(f.lifecycle).closeCollection();
        verify(f.repository, never()).deleteById("new");
    }

    private CreateCollectionRequest request(Path source) {
        return CreateCollectionRequest.builder()
                .name("New")
                .rootFolder(temp.resolve("books"))
                .dbFile(temp.resolve("new.db"))
                .sourcePath(source.toString())
                .importOnCreate(true)
                .createIndex(false)
                .build();
    }

    private static Collection collection(String id, Path db) {
        return new Collection(id, id, null, db.toString(), 0, null, null, null, null);
    }

    private static final class Fixture {
        final CollectionRepository repository = mock(CollectionRepository.class);
        final CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        final ImportFileUseCase importer = mock(ImportFileUseCase.class);
        final CollectionInfoPort collectionInfo = mock(CollectionInfoPort.class);
        final CollectionStorageManager storage = mock(CollectionStorageManager.class);
        final CreateCollectionUseCase useCase = new CreateCollectionUseCase(
                repository, lifecycle, importer, collectionInfo,
                new LibraryOperationCoordinator(), storage);
    }
}
