package com.myhomelibcorp.application.bulkedit;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.history.OperationHistoryPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BatchMetadataEditUseCaseUndoGuardTest {

    @Test
    void undoRefusesToOverwriteMetadataChangedAfterBatch() {
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookCommandRepository commands = mock(BookCommandRepository.class);
        GenreRepository genres = mock(GenreRepository.class);
        OperationHistoryPort history = mock(OperationHistoryPort.class);
        SearchIndexSynchronizer searchIndex = mock(SearchIndexSynchronizer.class);
        CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);

        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        var useCase = new BatchMetadataEditUseCase(books, commands, genres, history, searchIndex,
                new LibraryOperationCoordinator(), collections, transaction, 20);

        BookId id = BookId.fromString("00000000-0000-0000-0000-000000000101");
        BatchMetadataEditableSnapshot before = snapshot("before");
        BatchMetadataEditableSnapshot after = snapshot("after");
        when(history.countBulkChanges("op-1")).thenReturn(1);
        when(history.loadBulkChanges("op-1", 0, BatchMetadataEditUseCase.CHUNK_SIZE))
                .thenReturn(List.of(new BatchMetadataChange(id, before, after)));

        Collection collection = mock(Collection.class);
        when(collection.getId()).thenReturn("c1");
        when(collections.getCurrentCollection()).thenReturn(collection);

        Book externallyEdited = mock(Book.class);
        when(externallyEdited.getId()).thenReturn(id);
        when(externallyEdited.getTitle()).thenReturn("external-change");
        when(externallyEdited.getSeries()).thenReturn(null);
        when(externallyEdited.getSequenceNumber()).thenReturn(null);
        when(externallyEdited.getLanguage()).thenReturn(LanguageCode.of("en"));
        when(externallyEdited.getYear()).thenReturn(2026);
        when(externallyEdited.getPublisher()).thenReturn("");
        when(externallyEdited.getKeywords()).thenReturn("");
        when(externallyEdited.getAnnotation()).thenReturn("");
        when(externallyEdited.getGenres()).thenReturn(List.of());
        when(books.findByIds(List.of(id))).thenReturn(List.of(externallyEdited));

        assertThatThrownBy(() -> useCase.undo("c1", "op-1", new AtomicBoolean(), ignored -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed after batch operation");

        verify(history, never()).markUndone(anyString());
        verify(commands, never()).saveBatch(anyList());
        verify(searchIndex, never()).synchronizeAfterCommit(anyList());
    }

    private static BatchMetadataEditableSnapshot snapshot(String title) {
        return new BatchMetadataEditableSnapshot(title, null, null, "en", 2026, "", "", "", List.of());
    }
}
