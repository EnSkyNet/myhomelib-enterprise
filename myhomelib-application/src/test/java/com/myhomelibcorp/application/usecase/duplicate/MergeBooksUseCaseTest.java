package com.myhomelibcorp.application.usecase.duplicate;

import com.myhomelibcorp.application.duplicate.merge.BookMergeJournalEntry;
import com.myhomelibcorp.application.duplicate.merge.BookMergeMutationResult;
import com.myhomelibcorp.application.duplicate.merge.BookMergePlan;
import com.myhomelibcorp.application.port.out.duplicate.BookMergePort;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MergeBooksUseCaseTest {
    private static final BookId A = BookId.fromString("11111111-1111-1111-1111-111111111111");
    private static final BookId B = BookId.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void synchronizesBothSearchDocumentsOnlyAfterPersistentMergeReturns() {
        BookMergePort port = mock(BookMergePort.class);
        SearchIndexSynchronizer search = mock(SearchIndexSynchronizer.class);
        BookMergePlan plan = new BookMergePlan(A, B, A);
        when(port.merge(plan)).thenReturn(new BookMergeMutationResult("m-1", A, B, Instant.parse("2026-09-07T17:00:00Z"), false));
        when(search.synchronizeSafelyNow(List.of(A, B))).thenReturn(true);

        MergeBooksUseCase useCase = new MergeBooksUseCase(port, search);
        var result = useCase.merge(plan);

        assertThat(result.mergeId()).isEqualTo("m-1");
        assertThat(result.searchIndexSynchronized()).isTrue();
        var order = inOrder(port, search);
        order.verify(port).merge(plan);
        order.verify(search).synchronizeSafelyNow(List.of(A, B));
    }

    @Test
    void committedUndoRemainsSuccessfulEvenWhenDerivedSearchRefreshNeedsRecovery() {
        BookMergePort port = mock(BookMergePort.class);
        SearchIndexSynchronizer search = mock(SearchIndexSynchronizer.class);
        when(port.undo("m-1")).thenReturn(new BookMergeMutationResult("m-1", A, B, Instant.now(), true));
        when(search.synchronizeSafelyNow(List.of(A, B))).thenReturn(false);
        when(port.findLatestActiveMerge(A)).thenReturn(Optional.of(new BookMergeJournalEntry("m-1", A, B, A, Instant.now())));

        MergeBooksUseCase useCase = new MergeBooksUseCase(port, search);

        assertThat(useCase.latestActiveMerge(A)).isPresent();
        var result = useCase.undo("m-1");
        assertThat(result.undone()).isTrue();
        assertThat(result.searchIndexSynchronized()).isFalse();
    }
}
