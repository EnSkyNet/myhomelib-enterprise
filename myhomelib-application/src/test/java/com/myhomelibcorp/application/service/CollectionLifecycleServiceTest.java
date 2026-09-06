package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CollectionLifecycleServiceTest {

    @Test
    void changedCollectionClosesOldIndexBeforeSwitchAndActivatesTargetIndex() {
        Fixture f = new Fixture();
        Collection oldCollection = collection("old", "Old");
        Collection next = collection("next", "Next");
        when(f.lifecycle.getCurrentCollection()).thenReturn(oldCollection);
        when(f.migrations.migrateCurrentCollection()).thenReturn(0);

        f.service.initializeCollection(next, false);

        InOrder order = inOrder(f.searchLifecycle, f.lifecycle);
        order.verify(f.searchLifecycle).closeCurrentIndex();
        order.verify(f.lifecycle).switchToCollection(next);
        order.verify(f.searchLifecycle).sealClosedIndex(oldCollection);
        order.verify(f.searchLifecycle).activateCollectionIndex(next);
        verify(f.index, never()).rebuildIndex();
    }

    @Test
    void reportsWhetherTargetIndexWasProvenReusable() {
        Fixture f = new Fixture();
        Collection oldCollection = collection("old", "Old");
        Collection next = collection("next", "Next");
        when(f.lifecycle.getCurrentCollection()).thenReturn(oldCollection);
        when(f.migrations.migrateCurrentCollection()).thenReturn(0);
        when(f.searchLifecycle.activateCollectionIndex(next)).thenReturn(true);

        boolean reusable = f.service.initializeCollection(next, false);

        org.junit.jupiter.api.Assertions.assertTrue(reusable);
        verify(f.index, never()).rebuildIndex();
    }

    @Test
    void remoteCollectionStartupDoesNotRunCatalogWideRepair() {
        Fixture f = new Fixture();
        Collection next = collection("remote", "Remote");
        when(next.getType()).thenReturn(2);
        when(f.lifecycle.getCurrentCollection()).thenReturn(null);
        when(f.migrations.migrateCurrentCollection()).thenReturn(0);
        when(f.searchLifecycle.activateCollectionIndex(next)).thenReturn(true);

        f.service.initializeCollection(next, true);

        verify(f.index, never()).rebuildIndex();
    }

    @Test
    void failedChangedCollectionRestoresPreviousCollectionAndIndex() {
        Fixture f = new Fixture();
        Collection oldCollection = collection("old", "Old");
        Collection next = collection("next", "Next");
        when(f.lifecycle.getCurrentCollection()).thenReturn(oldCollection);
        doThrow(new IllegalStateException("switch failed")).when(f.lifecycle).switchToCollection(next);

        assertThrows(RuntimeException.class, () -> f.service.initializeCollection(next, false));

        verify(f.searchLifecycle, atLeastOnce()).closeCurrentIndex();
        verify(f.lifecycle).switchToCollection(oldCollection);
        verify(f.searchLifecycle).activateCollectionIndex(oldCollection);
        verify(f.index).rebuildIndex();
    }

    @Test
    void manualAsyncRebuildOwnsIndexLeaseBeforeExecutorStartsAndUntilCompletion() {
        Fixture f = new Fixture();
        Collection current = collection("a", "A");
        when(f.lifecycle.getCurrentCollection()).thenReturn(current);
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        doAnswer(invocation -> { scheduled.set(invocation.getArgument(0)); return null; })
                .when(f.executor).execute(any(Runnable.class));

        var future = f.service.rebuildSearchIndexAsync();

        org.junit.jupiter.api.Assertions.assertEquals(LibraryOperationType.INDEX, f.coordinator.activeOperation());
        assertThrows(LibraryOperationConflictException.class,
                () -> f.coordinator.acquire(LibraryOperationType.SYNC));
        org.junit.jupiter.api.Assertions.assertFalse(future.isDone());

        scheduled.get().run();
        future.join();

        verify(f.index).rebuildIndex(any(java.util.concurrent.atomic.AtomicBoolean.class));
        org.junit.jupiter.api.Assertions.assertFalse(f.coordinator.isBusy());
    }

    @Test
    void failedAsyncRebuildReleasesDetachedLease() {
        Fixture f = new Fixture();
        Collection current = collection("a", "A");
        when(f.lifecycle.getCurrentCollection()).thenReturn(current);
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        doAnswer(invocation -> { scheduled.set(invocation.getArgument(0)); return null; })
                .when(f.executor).execute(any(Runnable.class));
        doThrow(new IllegalStateException("boom"))
                .when(f.index).rebuildIndex(any(java.util.concurrent.atomic.AtomicBoolean.class));

        var future = f.service.rebuildSearchIndexAsync();
        scheduled.get().run();

        assertThrows(CompletionException.class, future::join);
        org.junit.jupiter.api.Assertions.assertFalse(f.coordinator.isBusy());
    }

    @Test
    void repeatedManualAsyncRequestReusesActiveRebuildInsteadOfCancellingIt() {
        Fixture f = new Fixture();
        Collection current = collection("a", "A");
        when(f.lifecycle.getCurrentCollection()).thenReturn(current);
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        doAnswer(invocation -> { scheduled.set(invocation.getArgument(0)); return null; })
                .when(f.executor).execute(any(Runnable.class));

        var first = f.service.rebuildSearchIndexAsync();
        var second = f.service.rebuildSearchIndexAsync();

        org.junit.jupiter.api.Assertions.assertSame(first, second);
        org.junit.jupiter.api.Assertions.assertEquals(LibraryOperationType.INDEX, f.coordinator.activeOperation());
        verify(f.executor, times(1)).execute(any(Runnable.class));

        scheduled.get().run();
        first.join();
        org.junit.jupiter.api.Assertions.assertFalse(f.coordinator.isBusy());
    }

    private static Collection collection(String id, String name) {
        Collection c = mock(Collection.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }

    private static final class Fixture {
        final CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        final DatabaseMigrationPort migrations = mock(DatabaseMigrationPort.class);
        final CacheInvalidationPort cacheInvalidation = mock(CacheInvalidationPort.class);
        final IndexRebuilder index = mock(IndexRebuilder.class);
        final SearchIndexLifecycle searchLifecycle = mock(SearchIndexLifecycle.class);
        final DomainEventPublisher events = mock(DomainEventPublisher.class);
        final ExecutorPort executor = mock(ExecutorPort.class);
        final LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        final CollectionLifecycleService service = new CollectionLifecycleService(
                lifecycle, migrations, cacheInvalidation,
                index, searchLifecycle, events, executor, coordinator);
    }
}
