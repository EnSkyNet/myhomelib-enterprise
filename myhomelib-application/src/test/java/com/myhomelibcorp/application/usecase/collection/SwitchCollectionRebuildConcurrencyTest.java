package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.cache.CacheInvalidationPort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseMigrationPort;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.event.DomainEventPublisher;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** End-to-end concurrency proof for MHL-035 at the application lifecycle boundary. */
class SwitchCollectionRebuildConcurrencyTest {

    @Test
    void switchCancelsCollectionARebuildBeforeActivatingCollectionB() throws Exception {
        Collection a = collection("a", "A");
        Collection b = collection("b", "B");
        AtomicReference<Collection> current = new AtomicReference<>(a);

        CollectionLifecyclePort lifecyclePort = mock(CollectionLifecyclePort.class);
        when(lifecyclePort.getCurrentCollection()).thenAnswer(invocation -> current.get());
        doAnswer(invocation -> {
            current.set(invocation.getArgument(0));
            return null;
        }).when(lifecyclePort).switchToCollection(any(Collection.class));

        DatabaseMigrationPort migrations = mock(DatabaseMigrationPort.class);
        when(migrations.migrateCurrentCollection()).thenReturn(0);
        CacheInvalidationPort cache = mock(CacheInvalidationPort.class);
        SearchIndexLifecycle searchLifecycle = mock(SearchIndexLifecycle.class);
        when(searchLifecycle.activateCollectionIndex(b)).thenReturn(true);
        DomainEventPublisher events = mock(DomainEventPublisher.class);
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();

        CountDownLatch rebuildStarted = new CountDownLatch(1);
        CountDownLatch rebuildObservedCancellation = new CountDownLatch(1);
        IndexRebuilder index = mock(IndexRebuilder.class);
        doAnswer(invocation -> {
            var cancelFlag = (java.util.concurrent.atomic.AtomicBoolean) invocation.getArgument(0);
            rebuildStarted.countDown();
            while (!cancelFlag.get()) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
            rebuildObservedCancellation.countDown();
            return null;
        }).when(index).rebuildIndex(any(java.util.concurrent.atomic.AtomicBoolean.class));

        ExecutorPort executor = new ExecutorPort() {
            @Override
            public <T> CompletableFuture<T> submit(Callable<T> task) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return task.call();
                    } catch (Exception e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                });
            }

            @Override
            public void execute(Runnable task) {
                CompletableFuture.runAsync(task);
            }
        };

        CollectionLifecycleService service = new CollectionLifecycleService(
                lifecyclePort, migrations, cache, index, searchLifecycle, events, executor, coordinator);
        CollectionRepository collections = mock(CollectionRepository.class);
        when(collections.findById("b")).thenReturn(Optional.of(b));
        SwitchCollectionUseCase switcher = new SwitchCollectionUseCase(collections, service, coordinator);

        CompletableFuture<Void> rebuildA = service.rebuildSearchIndexAsync();
        assertThat(rebuildStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.activeOperation()).isEqualTo(LibraryOperationType.INDEX);

        Collection activated = switcher.execute(b, false);

        assertThat(rebuildObservedCancellation.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(rebuildA.isCancelled()).isTrue();
        assertThat(activated).isSameAs(b);
        assertThat(current.get()).isSameAs(b);
        assertThat(coordinator.isBusy()).isFalse();
        verify(index, times(1)).rebuildIndex(any(java.util.concurrent.atomic.AtomicBoolean.class));
        verify(lifecyclePort).switchToCollection(b);
    }

    private static Collection collection(String id, String name) {
        Collection collection = mock(Collection.class);
        when(collection.getId()).thenReturn(id);
        when(collection.getName()).thenReturn(name);
        return collection;
    }
}
