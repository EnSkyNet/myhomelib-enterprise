package com.myhomelibcorp.application.content.indexing;

import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContentIndexAutoSchedulingServiceIsolationTest {

    @Test
    void refusesIncrementalSchedulingForInactiveCollectionBeforeReadingBooks() {
        ContentIndexingQueueService queue = mock(ContentIndexingQueueService.class);
        ContentIndexPort index = mock(ContentIndexPort.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        Collection active = mock(Collection.class);
        when(active.getId()).thenReturn("collection-b");
        when(lifecycle.getCurrentCollection()).thenReturn(active);
        ContentIndexAutoSchedulingService service =
                new ContentIndexAutoSchedulingService(queue, index, books, lifecycle);
        ImportChangeSet changes = new ImportChangeSet(
                Set.of("book-1"), Set.of(), Set.of(), true);

        var result = service.schedule("collection-a", changes);

        assertThat(result.scheduled()).isFalse();
        assertThat(result.rebuildRequired()).isTrue();
        assertThat(result.queuedTasks()).isZero();
        verify(queue).requireRebuild("collection-a");
        verify(queue, never()).restore(anyString());
        verifyNoInteractions(books, index);
    }
    @Test
    void abortsIfActiveCollectionChangesWhileReadingDynamicBookRepository() {
        ContentIndexingQueueService queue = mock(ContentIndexingQueueService.class);
        ContentIndexPort index = mock(ContentIndexPort.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        Collection activeA = mock(Collection.class);
        Collection activeB = mock(Collection.class);
        when(activeA.getId()).thenReturn("collection-a");
        when(activeB.getId()).thenReturn("collection-b");
        // Initial validation and the pre-read check see A; post-read validation sees B.
        when(lifecycle.getCurrentCollection()).thenReturn(activeA, activeA, activeB);
        when(queue.supersedeBooks(eq("collection-a"), anySet(), anyLong())).thenReturn(true);
        when(books.findByIds(anyList())).thenReturn(List.of());
        ContentIndexAutoSchedulingService service =
                new ContentIndexAutoSchedulingService(queue, index, books, lifecycle);
        ImportChangeSet changes = new ImportChangeSet(
                Set.of(), Set.of("00000000-0000-0000-0000-000000000001"), Set.of(), true);

        var result = service.schedule("collection-a", changes);

        assertThat(result.scheduled()).isFalse();
        assertThat(result.rebuildRequired()).isTrue();
        verify(queue).requireRebuild("collection-a");
        verify(books).findByIds(anyList());
        verifyNoInteractions(index);
    }

}
