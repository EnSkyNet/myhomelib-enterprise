package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DatabaseToolsServiceLifecycleTest {

    @Test
    void queuedVacuumRefusesDifferentActiveCollectionBeforeStorageMutation() {
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        Collection other = new Collection("c2", "Other", null, "other.db", 0,
                null, null, null, null);
        when(lifecycle.getCurrentCollection()).thenReturn(other);
        DatabaseToolsService service = new DatabaseToolsService(
                lifecycle, storage, new LibraryOperationCoordinator());

        assertThrows(IllegalStateException.class, () -> service.vacuumCurrent("c1"));
        verify(storage, never()).vacuumCurrent();
    }
    @Test
    void indexCountRefusesDifferentActiveCollectionBeforeReadingIndex() {
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        Collection other = new Collection("c2", "Other", null, "other.db", 0,
                null, null, null, null);
        when(lifecycle.getCurrentCollection()).thenReturn(other);
        DatabaseToolsService service = new DatabaseToolsService(
                lifecycle, storage, new LibraryOperationCoordinator());

        assertThrows(IllegalStateException.class, () -> service.getIndexedDocumentCount("c1"));
        verify(lifecycle, never()).getIndexedDocumentCount();
    }

    @Test
    void indexCountKeepsCollectionStableUntilCountHasBeenRead() {
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        CollectionStorageManager storage = mock(CollectionStorageManager.class);
        Collection current = new Collection("c1", "Current", null, "current.db", 0,
                null, null, null, null);
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        when(lifecycle.getCurrentCollection()).thenReturn(current);
        when(lifecycle.getIndexedDocumentCount()).thenAnswer(invocation -> {
            assertThrows(IllegalStateException.class, () -> coordinator.acquire(LibraryOperationType.SWITCH));
            return 42;
        });
        DatabaseToolsService service = new DatabaseToolsService(lifecycle, storage, coordinator);

        assertEquals(42, service.getIndexedDocumentCount("c1"));
    }

}
