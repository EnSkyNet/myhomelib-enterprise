package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.content.indexing.ContentIndexingQueueService;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.collection.CollectionSourceMonitorPort;
import com.myhomelibcorp.application.port.out.collection.IncomingFolderWatchPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionDatabaseOwnershipPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DeleteCollectionUseCaseTest {

    @Test
    void ownedInactiveCollectionUsesPreparedDeletionProtocolAndDoesNotVacuumActiveDatabase() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        CollectionSourceMonitorPort sourceMonitor = mock(CollectionSourceMonitorPort.class);
        IncomingFolderWatchPort incomingWatcher = mock(IncomingFolderWatchPort.class);
        DeleteCollectionUseCase useCase = new DeleteCollectionUseCase(repository, storage, lifecycle, sourceMonitor,
                incomingWatcher, new LibraryOperationCoordinator());

        Collection active = mock(Collection.class);
        Collection target = mock(Collection.class);
        when(active.getId()).thenReturn("active");
        when(target.getId()).thenReturn("target");
        when(repository.findById("target")).thenReturn(Optional.of(target));
        when(repository.findAll()).thenReturn(List.of(active, target));
        when(lifecycle.getCurrentCollection()).thenReturn(active);

        useCase.execute("target");

        verify(sourceMonitor).stopMonitoring("target");
        verify(incomingWatcher).stopMonitoring("target");
        verify(storage).closeCollection(target);
        verify(storage, never()).vacuumCurrent();
        var order = inOrder(storage, repository);
        order.verify(storage).preparePhysicalDeletion(target);
        order.verify(repository).deleteById("target");
        order.verify(storage).commitPreparedPhysicalDeletion(target);
        verify(storage, never()).deletePhysicalFiles(any());
    }

    @Test
    void doesNotRemoveRegistryRecordWhenPhysicalDeletionPreparationFails() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        CollectionSourceMonitorPort sourceMonitor = mock(CollectionSourceMonitorPort.class);
        IncomingFolderWatchPort incomingWatcher = mock(IncomingFolderWatchPort.class);
        DeleteCollectionUseCase useCase = new DeleteCollectionUseCase(repository, storage, lifecycle,
                sourceMonitor, incomingWatcher, new LibraryOperationCoordinator());

        Collection active = mock(Collection.class);
        Collection target = mock(Collection.class);
        when(active.getId()).thenReturn("active");
        when(target.getId()).thenReturn("target");
        when(repository.findById("target")).thenReturn(Optional.of(target));
        when(repository.findAll()).thenReturn(List.of(active, target));
        when(lifecycle.getCurrentCollection()).thenReturn(active);
        doThrow(new IllegalStateException("database detach failed")).when(storage).preparePhysicalDeletion(target);

        assertThrows(IllegalStateException.class, () -> useCase.execute("target"));

        verify(storage).preparePhysicalDeletion(target);
        verify(repository, never()).deleteById("target");
        verify(storage, never()).commitPreparedPhysicalDeletion(any());
    }

    @Test
    void registryFailureRollsBackPreparedOwnedDatabaseDeletion() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        CollectionSourceMonitorPort sourceMonitor = mock(CollectionSourceMonitorPort.class);
        IncomingFolderWatchPort incomingWatcher = mock(IncomingFolderWatchPort.class);
        DeleteCollectionUseCase useCase = new DeleteCollectionUseCase(repository, storage, lifecycle,
                sourceMonitor, incomingWatcher, new LibraryOperationCoordinator());

        Collection active = mock(Collection.class);
        Collection target = mock(Collection.class);
        when(active.getId()).thenReturn("active");
        when(target.getId()).thenReturn("target");
        when(repository.findById("target")).thenReturn(Optional.of(target));
        when(repository.findAll()).thenReturn(List.of(active, target));
        when(lifecycle.getCurrentCollection()).thenReturn(active);
        doThrow(new IllegalStateException("registry failed")).when(repository).deleteById("target");

        assertThrows(IllegalStateException.class, () -> useCase.execute("target"));

        var order = inOrder(storage, repository);
        order.verify(storage).preparePhysicalDeletion(target);
        order.verify(repository).deleteById("target");
        order.verify(storage).rollbackPreparedPhysicalDeletion(target);
        verify(storage, never()).commitPreparedPhysicalDeletion(any());
    }

    @Test
    void externalDatabaseIsPreservedAndOnlyDerivedFilesAreCleaned() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        CollectionSourceMonitorPort sourceMonitor = mock(CollectionSourceMonitorPort.class);
        IncomingFolderWatchPort incomingWatcher = mock(IncomingFolderWatchPort.class);
        CollectionDatabaseOwnershipPort ownership = mock(CollectionDatabaseOwnershipPort.class);
        DeleteCollectionUseCase useCase = new DeleteCollectionUseCase(repository, storage, lifecycle,
                sourceMonitor, incomingWatcher, new LibraryOperationCoordinator(), null, ownership);

        Collection active = mock(Collection.class);
        Collection target = mock(Collection.class);
        when(active.getId()).thenReturn("active");
        when(target.getId()).thenReturn("target");
        when(repository.findById("target")).thenReturn(Optional.of(target));
        when(repository.findAll()).thenReturn(List.of(active, target));
        when(lifecycle.getCurrentCollection()).thenReturn(active);
        when(ownership.findOwnership("target")).thenReturn(CollectionDatabaseOwnershipPort.Ownership.EXTERNAL);

        useCase.execute("target");

        verify(repository).deleteById("target");
        verify(storage).deleteDerivedFiles(target);
        verify(storage, never()).preparePhysicalDeletion(any());
        verify(storage, never()).deletePhysicalFiles(any());
    }

    @Test
    void unknownDatabaseOwnershipFailsSafeByPreservingDatabase() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        CollectionSourceMonitorPort sourceMonitor = mock(CollectionSourceMonitorPort.class);
        IncomingFolderWatchPort incomingWatcher = mock(IncomingFolderWatchPort.class);
        CollectionDatabaseOwnershipPort ownership = mock(CollectionDatabaseOwnershipPort.class);
        DeleteCollectionUseCase useCase = new DeleteCollectionUseCase(repository, storage, lifecycle,
                sourceMonitor, incomingWatcher, new LibraryOperationCoordinator(), null, ownership);

        Collection active = mock(Collection.class);
        Collection target = mock(Collection.class);
        when(active.getId()).thenReturn("active");
        when(target.getId()).thenReturn("target");
        when(repository.findById("target")).thenReturn(Optional.of(target));
        when(repository.findAll()).thenReturn(List.of(active, target));
        when(lifecycle.getCurrentCollection()).thenReturn(active);
        when(ownership.findOwnership("target")).thenReturn(CollectionDatabaseOwnershipPort.Ownership.UNKNOWN);

        useCase.execute("target");

        verify(repository).deleteById("target");
        verify(storage).deleteDerivedFiles(target);
        verify(storage, never()).preparePhysicalDeletion(any());
    }

    @Test
    void quiescesAndForgetsFullTextQueueBeforePreparedDeletion() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        CollectionSourceMonitorPort sourceMonitor = mock(CollectionSourceMonitorPort.class);
        IncomingFolderWatchPort incomingWatcher = mock(IncomingFolderWatchPort.class);
        ContentIndexingQueueService queue = mock(ContentIndexingQueueService.class);
        DeleteCollectionUseCase useCase = new DeleteCollectionUseCase(repository, storage, lifecycle,
                sourceMonitor, incomingWatcher, new LibraryOperationCoordinator(), queue);

        Collection active = mock(Collection.class);
        Collection target = mock(Collection.class);
        when(active.getId()).thenReturn("active");
        when(target.getId()).thenReturn("target");
        when(repository.findById("target")).thenReturn(Optional.of(target));
        when(repository.findAll()).thenReturn(List.of(active, target));
        when(lifecycle.getCurrentCollection()).thenReturn(active);
        when(queue.discardCollection(eq("target"), anyLong())).thenReturn(true);

        useCase.execute("target");

        var order = inOrder(queue, storage, repository);
        order.verify(queue).discardCollection(eq("target"), anyLong());
        order.verify(storage).preparePhysicalDeletion(target);
        order.verify(repository).deleteById("target");
        order.verify(storage).commitPreparedPhysicalDeletion(target);
    }

    @Test
    void abortsDeletionWhenFullTextWorkerCannotQuiesce() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        CollectionSourceMonitorPort sourceMonitor = mock(CollectionSourceMonitorPort.class);
        IncomingFolderWatchPort incomingWatcher = mock(IncomingFolderWatchPort.class);
        ContentIndexingQueueService queue = mock(ContentIndexingQueueService.class);
        DeleteCollectionUseCase useCase = new DeleteCollectionUseCase(repository, storage, lifecycle,
                sourceMonitor, incomingWatcher, new LibraryOperationCoordinator(), queue);

        Collection active = mock(Collection.class);
        Collection target = mock(Collection.class);
        when(active.getId()).thenReturn("active");
        when(target.getId()).thenReturn("target");
        when(repository.findById("target")).thenReturn(Optional.of(target));
        when(repository.findAll()).thenReturn(List.of(active, target));
        when(lifecycle.getCurrentCollection()).thenReturn(active);
        when(queue.discardCollection(eq("target"), anyLong())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> useCase.execute("target"));

        verify(storage, never()).preparePhysicalDeletion(any());
        verify(repository, never()).deleteById(anyString());
        verifyNoInteractions(sourceMonitor, incomingWatcher);
    }
}
