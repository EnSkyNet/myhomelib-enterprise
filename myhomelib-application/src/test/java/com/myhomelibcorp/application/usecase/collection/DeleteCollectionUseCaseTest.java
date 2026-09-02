package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.collection.CollectionSourceMonitorPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

class DeleteCollectionUseCaseTest {

    @Test
    void deletingInactiveCollectionDoesNotVacuumActiveDatabase() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        CollectionSourceMonitorPort sourceMonitor = mock(CollectionSourceMonitorPort.class);
        DeleteCollectionUseCase useCase = new DeleteCollectionUseCase(repository, storage, lifecycle, sourceMonitor, new LibraryOperationCoordinator());

        Collection active = mock(Collection.class);
        Collection target = mock(Collection.class);
        when(active.getId()).thenReturn("active");
        when(target.getId()).thenReturn("target");
        when(repository.findById("target")).thenReturn(Optional.of(target));
        when(repository.findAll()).thenReturn(List.of(active, target));
        when(lifecycle.getCurrentCollection()).thenReturn(active);

        useCase.execute("target");

        verify(storage).closeCollection(target);
        verify(storage, never()).vacuumCurrent();
        verify(storage).deletePhysicalFiles(target);
        verify(repository).deleteById("target");
    }
}
