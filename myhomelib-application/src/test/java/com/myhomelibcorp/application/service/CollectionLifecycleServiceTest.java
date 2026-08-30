package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

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
        final SeriesRepository series = mock(SeriesRepository.class);
        final IndexRebuilder index = mock(IndexRebuilder.class);
        final SearchIndexLifecycle searchLifecycle = mock(SearchIndexLifecycle.class);
        final DomainEventPublisher events = mock(DomainEventPublisher.class);
        final ExecutorPort executor = mock(ExecutorPort.class);
        final CollectionLifecycleService service = new CollectionLifecycleService(
                lifecycle, migrations, cacheInvalidation,
                series, index, searchLifecycle, events, executor);

        Fixture() {
            when(series.findAll()).thenReturn(java.util.List.of());
        }
    }
}
