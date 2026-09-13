package com.myhomelibcorp.application.metadata.merge;

import com.myhomelibcorp.application.metadata.*;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MetadataBatchServiceTest {
    private final MetadataReviewService review = mock(MetadataReviewService.class);
    private final CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
    private final BookQueryRepository books = mock(BookQueryRepository.class);
    private final LibraryOperationCoordinator operations = new LibraryOperationCoordinator();
    private final CollectionLifecyclePort collections = mock(CollectionLifecyclePort.class);
    private final MetadataBatchService service = new MetadataBatchService(review,
            new ApplyMetadataCandidateUseCase(books, mutations), mutations, operations, collections);

    MetadataBatchServiceTest() {
        Collection collection = mock(Collection.class);
        when(collection.getId()).thenReturn("library");
        when(collections.getCurrentCollection()).thenReturn(collection);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 9, MetadataBatchService.MAX_BOOKS})
    void boundedBatchesReportOrderedProgressAndNeverAutoApply(int size) {
        List<BookId> ids = ids(size);
        var progress = new ArrayList<MetadataBatchService.Progress>();
        when(review.lookup(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(empty()));

        var result = service.lookup("library", ids, new AtomicBoolean(), progress::add).join();

        assertThat(result.items()).extracting(MetadataBatchService.Item::bookId).containsExactlyElementsOf(ids);
        assertThat(progress).extracting(MetadataBatchService.Progress::completed)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, size).boxed().toList());
        assertThat(result.cancelled()).isFalse();
        assertThat(operations.isBusy()).isFalse();
        verifyNoInteractions(mutations, books);
    }

    @Test
    void outOfOrderCompletionNeverAdmitsMoreThanFourBooksAndKeepsInputOrder() {
        List<BookId> ids = ids(15);
        var pending = new LinkedHashMap<BookId, CompletableFuture<MetadataReviewLookupResult>>();
        when(review.lookup(any(), any(), any())).thenAnswer(call -> {
            assertThat(pending.values().stream().filter(future -> !future.isDone()).count())
                    .isLessThan(MetadataBatchService.CONCURRENCY);
            var future = new CompletableFuture<MetadataReviewLookupResult>();
            pending.put(call.getArgument(0), future);
            return future;
        });
        var result = service.lookup("library", ids, new AtomicBoolean(), null);
        assertThat(pending).hasSize(MetadataBatchService.CONCURRENCY);
        assertThat(operations.isBusy()).isTrue();
        while (!result.isDone()) {
            var next = pending.values().stream().filter(future -> !future.isDone()).reduce((a, b) -> b).orElseThrow();
            next.complete(empty());
        }
        assertThat(result.join().items()).extracting(MetadataBatchService.Item::bookId).containsExactlyElementsOf(ids);
        assertThat(operations.isBusy()).isFalse();
    }

    @Test
    void oneBookFailureAndOneProviderTimeoutDoNotDiscardOtherBooks() {
        List<BookId> ids = ids(3);
        when(review.lookup(eq(ids.get(0)), any(), any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("missing")));
        when(review.lookup(eq(ids.get(1)), any(), any())).thenReturn(CompletableFuture.completedFuture(
                new MetadataReviewLookupResult(List.of(), List.of(new MetadataProviderIssue("p", "Provider",
                        MetadataProviderErrorKind.TIMEOUT, "timed out")), false)));
        when(review.lookup(eq(ids.get(2)), any(), any())).thenReturn(CompletableFuture.completedFuture(empty()));
        var result = service.lookup("library", ids, new AtomicBoolean(), null).join();
        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).failed()).isTrue();
        assertThat(result.items().get(1).lookup().issues().get(0).kind()).isEqualTo(MetadataProviderErrorKind.TIMEOUT);
        assertThat(result.items().get(2).failed()).isFalse();
        verifyNoInteractions(mutations, books);
    }

    @Test
    void cancellationStopsAdmissionAndReleasesLeaseOnlyAfterWorkersFinish() {
        var pending = new ArrayList<CompletableFuture<MetadataReviewLookupResult>>();
        when(review.lookup(any(), any(), any())).thenAnswer(call -> {
            var future = new CompletableFuture<MetadataReviewLookupResult>();
            pending.add(future);
            return future;
        });
        AtomicBoolean cancelled = new AtomicBoolean();
        var result = service.lookup("library", ids(20), cancelled, null);
        assertThat(result.cancel(true)).isTrue();
        assertThat(cancelled).isTrue();
        assertThat(operations.isBusy()).isTrue();
        pending.forEach(future -> future.complete(empty()));
        assertThat(pending).hasSize(MetadataBatchService.CONCURRENCY);
        assertThat(operations.isBusy()).isFalse();
        verifyNoInteractions(mutations, books);
    }

    @Test
    void preCancelledBatchAndOversizedBatchDoNotScheduleWork() {
        assertThat(service.lookup("library", ids(2), new AtomicBoolean(true), null).join().cancelled()).isTrue();
        assertThatThrownBy(() -> service.lookup("library", ids(101), new AtomicBoolean(), null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(review, mutations, books);
    }

    @Test
    void applyPreservesLocalStateAndRespectsDifferentFieldSelectionsPerBook() {
        Book first = book("First");
        Book second = book("Second");
        when(books.findById(first.getId())).thenReturn(Optional.of(first));
        when(books.findById(second.getId())).thenReturn(Optional.of(second));
        when(mutations.updateBatch(anyList(), any(), any())).thenAnswer(call -> {
            List<BookId> ids = call.getArgument(0);
            Function<BookId, Book> update = call.getArgument(1);
            return ids.stream().map(update).toList();
        });
        var candidate = candidate();
        var result = service.apply("library", List.of(
                new ApplyMetadataCandidateUseCase.Request(first.getId(), candidate, Set.of(MetadataMergeField.TITLE)),
                new ApplyMetadataCandidateUseCase.Request(second.getId(), candidate, Set.of(MetadataMergeField.YEAR))), new AtomicBoolean());
        assertThat(result.get(0).getTitle()).isEqualTo("Remote");
        assertThat(result.get(0).getYear()).isEqualTo(2001);
        assertThat(result.get(1).getTitle()).isEqualTo("Second");
        assertThat(result.get(1).getYear()).isEqualTo(2026);
        for (Book book : result) {
            assertThat(book.getProgress()).isEqualTo(73);
            assertThat(book.getRate()).isEqualTo(4);
            assertThat(book.getKeywords()).isEqualTo("local keywords");
            assertThat(book.getReview()).isEqualTo("local review");
        }
        verify(mutations, never()).save(any());
        assertThat(operations.isBusy()).isFalse();
    }

    @Test
    void cancelEmptySelectionAndChangedCollectionPerformZeroMutations() {
        var request = new ApplyMetadataCandidateUseCase.Request(BookId.generate(), candidate(), Set.of(MetadataMergeField.TITLE));
        assertThatThrownBy(() -> service.apply("library", List.of(request), new AtomicBoolean(true)))
                .isInstanceOf(CancellationException.class);
        assertThat(service.apply("library", List.of(new ApplyMetadataCandidateUseCase.Request(
                request.bookId(), request.candidate(), Set.of())), new AtomicBoolean())).isEmpty();
        assertThatThrownBy(() -> service.apply("other", List.of(request), new AtomicBoolean()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("different collection");
        assertThatThrownBy(() -> service.apply("library", List.of(request, request), new AtomicBoolean()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mutations, books);
        assertThat(operations.isBusy()).isFalse();
    }

    private static List<BookId> ids(int count) { return IntStream.range(0, count).mapToObj(i -> BookId.generate()).toList(); }
    private static MetadataReviewLookupResult empty() { return new MetadataReviewLookupResult(List.of(), List.of(), false); }
    private static Book book(String title) {
        return Book.builder().title(title).metadata(BookMetadata.builder().year(2001).rate(4).progress(73)
                .keywords("local keywords").review("local review").language(LanguageCode.of("uk")).build()).build();
    }
    private static MetadataCandidate candidate() {
        return new MetadataCandidate(new MetadataSource("p", "Provider", "1", ""), 0.9,
                "Remote", List.of("Remote Author"), "", 2026, "Remote publisher", "en", "Remote annotation", "");
    }
}
