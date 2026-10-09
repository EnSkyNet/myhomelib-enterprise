package com.myhomelibcorp.application.content.maintenance;

import com.myhomelibcorp.application.content.ContentExtractionService;
import com.myhomelibcorp.application.content.index.ContentIndexHealth;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.application.content.indexing.ContentIndexingQueueService;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ContentIndexMaintenanceLifecycleTest {

    @Test
    void queuedRebuildRefusesDifferentActiveCollectionBeforeReadingCatalogOrTouchingIndexQueue() {
        ContentIndexPort contentIndex = mock(ContentIndexPort.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookMapper mapper = mock(BookMapper.class);
        ResolveBookContentUseCase resolver = mock(ResolveBookContentUseCase.class);
        ContentExtractionService extraction = mock(ContentExtractionService.class);
        ContentIndexingQueueService indexingQueue = mock(ContentIndexingQueueService.class);
        CollectionBackupPort backup = mock(CollectionBackupPort.class);
        Collection active = mock(Collection.class);
        when(active.getId()).thenReturn("collection-b");
        when(backup.getCurrentCollection()).thenReturn(active);

        ContentIndexMaintenanceService service = new ContentIndexMaintenanceService(
                contentIndex, books, mapper, resolver, extraction, indexingQueue,
                backup, new LibraryOperationCoordinator());

        assertThatThrownBy(() -> service.rebuild("collection-a", () -> false, ignored -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Активна колекція змінилася");

        verifyNoInteractions(contentIndex, books, mapper, resolver, extraction, indexingQueue);
    }

    @Test
    void fullTextRebuildKeepsCollectionStableButAllowsOnlineDownloadToJoin() throws Exception {
        ContentIndexPort contentIndex = mock(ContentIndexPort.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookMapper mapper = mock(BookMapper.class);
        ResolveBookContentUseCase resolver = mock(ResolveBookContentUseCase.class);
        ContentExtractionService extraction = mock(ContentExtractionService.class);
        ContentIndexingQueueService indexingQueue = mock(ContentIndexingQueueService.class);
        CollectionBackupPort backup = mock(CollectionBackupPort.class);
        Collection active = mock(Collection.class);
        when(active.getId()).thenReturn("collection-a");
        when(backup.getCurrentCollection()).thenReturn(active);
        when(books.countAll()).thenReturn(0L);
        when(books.streamAll()).thenReturn(Stream.empty());
        when(indexingQueue.supersedeCollection("collection-a", 5_000L)).thenReturn(true);
        ContentIndexHealth health = new ContentIndexHealth(
                "collection-a", 1, 0, 0, true, ContentIndexHealth.State.STALE, "");
        when(contentIndex.health("collection-a")).thenReturn(health);

        CountDownLatch rebuildEntered = new CountDownLatch(1);
        CountDownLatch allowRebuildToFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            rebuildEntered.countDown();
            assertThat(allowRebuildToFinish.await(3, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(contentIndex).rebuild(eq("collection-a"), any());

        LibraryOperationCoordinator operations = new LibraryOperationCoordinator();
        ContentIndexMaintenanceService service = new ContentIndexMaintenanceService(
                contentIndex, books, mapper, resolver, extraction, indexingQueue, backup, operations);

        CompletableFuture<ContentIndexRebuildResult> rebuild = CompletableFuture.supplyAsync(
                () -> service.rebuild("collection-a", () -> false, ignored -> { }));
        assertThat(rebuildEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(operations.activeOperation()).isEqualTo(LibraryOperationType.INDEX);

        var downloadLease = operations.acquireDetachedShared(LibraryOperationType.DOWNLOAD);
        try {
            assertThat(operations.activeOperation()).isEqualTo(LibraryOperationType.INDEX);
        } finally {
            downloadLease.close();
            allowRebuildToFinish.countDown();
        }

        assertThat(rebuild.get(3, TimeUnit.SECONDS).status())
                .isEqualTo(ContentIndexRebuildResult.Status.COMPLETED);
        verify(indexingQueue).holdStale("collection-a");
        verify(indexingQueue).acknowledgeFresh("collection-a");
        verify(indexingQueue).releaseStaleHold("collection-a");
        assertThat(operations.isBusy()).isFalse();
    }
}
