package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.content.indexing.ContentIndexingQueueService;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SwitchCollectionUseCaseTest {

    @Test
    void reloadsAuthoritativeMetadataBeforeActivation() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        SwitchCollectionUseCase useCase = new SwitchCollectionUseCase(repository, lifecycle, new LibraryOperationCoordinator());

        Collection partial = new Collection("c1", "Online", Path.of("books"), "library.db", 2,
                null, null, null, null);
        Collection persisted = new Collection("c1", "Online", Path.of("books"), "library.db", 2,
                "reader", "enc:secret", "https://example.test/books", "notes");

        when(repository.findById("c1")).thenReturn(Optional.of(persisted));
        when(lifecycle.getCurrentCollection()).thenReturn(null);

        Collection activated = useCase.execute(partial);

        assertThat(activated).isSameAs(persisted);
        verify(lifecycle).initializeCollection(persisted, true);
    }

    @Test
    void refreshesMetadataWithoutReopeningAlreadyActiveCollection() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        SwitchCollectionUseCase useCase = new SwitchCollectionUseCase(repository, lifecycle, new LibraryOperationCoordinator());

        Collection oldDescriptor = new Collection("c1", "Old", null, "library.db", 0,
                null, null, null, null);
        Collection persisted = new Collection("c1", "Renamed", null, "library.db", 0,
                null, null, null, "new notes");

        when(repository.findById("c1")).thenReturn(Optional.of(persisted));
        when(lifecycle.getCurrentCollection()).thenReturn(oldDescriptor);

        Collection activated = useCase.execute(oldDescriptor);

        assertThat(activated).isSameAs(persisted);
        verify(lifecycle).updateCurrentCollection(persisted);
        verify(lifecycle, never()).initializeCollection(any(), anyBoolean());
    }
    @Test
    void switchQuiescesOldContentIndexWorkBeforeActivatingNewCollection() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        ContentIndexingQueueService queue = mock(ContentIndexingQueueService.class);
        SwitchCollectionUseCase useCase = new SwitchCollectionUseCase(
                repository, lifecycle, new LibraryOperationCoordinator(), queue);

        Collection oldCollection = new Collection("a", "A", null, "a.db", 0,
                null, null, null, null);
        Collection target = new Collection("b", "B", null, "b.db", 0,
                null, null, null, null);

        when(repository.findById("b")).thenReturn(Optional.of(target));
        when(lifecycle.getCurrentCollection()).thenReturn(oldCollection);
        when(queue.hasOutstandingForCollection("a")).thenReturn(true);
        when(queue.supersedeCollection("a", 5_000L)).thenReturn(true);
        when(lifecycle.initializeCollection(target, true)).thenReturn(true);

        Collection activated = useCase.execute(target);

        assertThat(activated).isSameAs(target);
        var order = inOrder(queue, lifecycle);
        order.verify(queue).requireRebuild("a");
        order.verify(queue).supersedeCollection("a", 5_000L);
        order.verify(lifecycle).initializeCollection(target, true);
        order.verify(queue).restore("b");
    }

    @Test
    void switchAbortsWhenOldContentIndexWorkCannotQuiesce() {
        CollectionRepository repository = mock(CollectionRepository.class);
        CollectionLifecycleService lifecycle = mock(CollectionLifecycleService.class);
        ContentIndexingQueueService queue = mock(ContentIndexingQueueService.class);
        SwitchCollectionUseCase useCase = new SwitchCollectionUseCase(
                repository, lifecycle, new LibraryOperationCoordinator(), queue);

        Collection oldCollection = new Collection("a", "A", null, "a.db", 0,
                null, null, null, null);
        Collection target = new Collection("b", "B", null, "b.db", 0,
                null, null, null, null);

        when(repository.findById("b")).thenReturn(Optional.of(target));
        when(lifecycle.getCurrentCollection()).thenReturn(oldCollection);
        when(queue.hasOutstandingForCollection("a")).thenReturn(true);
        when(queue.supersedeCollection("a", 5_000L)).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> useCase.execute(target))
                .isInstanceOf(IllegalStateException.class);

        verify(queue).requireRebuild("a");
        verify(lifecycle, never()).initializeCollection(any(), anyBoolean());
        verify(queue, never()).restore("b");
    }

}
