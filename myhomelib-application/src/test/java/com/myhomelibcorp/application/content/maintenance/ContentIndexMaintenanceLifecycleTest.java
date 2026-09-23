package com.myhomelibcorp.application.content.maintenance;

import com.myhomelibcorp.application.content.ContentExtractionService;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.application.content.indexing.ContentIndexingQueueService;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
}
