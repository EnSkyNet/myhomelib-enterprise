package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationConflictException;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.statistics.ReadingStatisticsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReadingSessionServiceCollectionIsolationTest {

    @Test
    void finishDropsSessionWhenOriginatingCollectionIsNoLongerActive() {
        ReadingStatisticsPort statistics = mock(ReadingStatisticsPort.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        AtomicReference<Collection> active = new AtomicReference<>(collection("A"));
        when(lifecycle.getCurrentCollection()).thenAnswer(invocation -> active.get());
        ReadingSessionService service = new ReadingSessionService(
                statistics, lifecycle, new LibraryOperationCoordinator());

        service.start("A", "book-1", 10);
        active.set(collection("B"));
        service.finish("A", "book-1", 20);

        verify(statistics, never()).recordSession(any());
    }

    @Test
    void finishRecordsSessionOnlyWhileOriginatingCollectionRemainsActive() {
        ReadingStatisticsPort statistics = mock(ReadingStatisticsPort.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(collection("A"));
        ReadingSessionService service = new ReadingSessionService(
                statistics, lifecycle, new LibraryOperationCoordinator());

        service.start("A", "book-1", 10);
        service.finish("A", "book-1", 20);

        verify(statistics).recordSession(any());
    }

    @Test
    void discardWithoutExplicitCollectionOnlyRemovesCurrentCollectionSession() {
        ReadingStatisticsPort statistics = mock(ReadingStatisticsPort.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        AtomicReference<Collection> active = new AtomicReference<>(collection("A"));
        when(lifecycle.getCurrentCollection()).thenAnswer(invocation -> active.get());
        ReadingSessionService service = new ReadingSessionService(
                statistics, lifecycle, new LibraryOperationCoordinator());

        service.start("A", "same-book", 10);
        active.set(collection("B"));
        service.start("B", "same-book", 20);
        service.discard("same-book");

        active.set(collection("A"));
        service.finish("A", "same-book", 30);
        active.set(collection("B"));
        service.finish("B", "same-book", 40);

        verify(statistics, times(1)).recordSession(any());
    }

    @Test
    void findKeepsLifecycleStableUntilStatisticsReadCompletes() {
        ReadingStatisticsPort statistics = mock(ReadingStatisticsPort.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(collection("A"));
        LibraryOperationCoordinator coordinator = new LibraryOperationCoordinator();
        when(statistics.findByBookId("book-1")).thenAnswer(invocation -> {
            assertThrows(LibraryOperationConflictException.class,
                    () -> coordinator.acquireDetached(LibraryOperationType.SWITCH));
            return Optional.empty();
        });
        ReadingSessionService service = new ReadingSessionService(statistics, lifecycle, coordinator);

        service.find("book-1");

        verify(statistics).findByBookId("book-1");
    }

    private static Collection collection(String id) {
        return new Collection(id, id, null, null, 0, null, null, null, null);
    }
}
