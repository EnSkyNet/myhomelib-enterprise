package com.myhomelibcorp.application.metadata.merge;

import com.myhomelibcorp.application.metadata.*;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MetadataReviewServiceTest {
    @Test
    void isbnBookProducesAlreadyNormalizedPreviewList() {
        Book book = Book.builder().id(BookId.generate()).title("Local").metadata(BookMetadata.builder()
                .language(LanguageCode.of("uk")).isbn(Isbn.of("9783161484100")).build()).file(BookFile.empty()).build();
        BookQueryRepository books = mock(BookQueryRepository.class);
        MetadataLookupService lookup = mock(MetadataLookupService.class);
        when(books.findById(book.getId())).thenReturn(Optional.of(book));
        MetadataCandidate candidate = new MetadataCandidate(new MetadataSource("p", "Provider", "1", ""),
                0.9, "Remote", List.of(), "9783161484100", null, "", "", "", "");
        when(lookup.search(any())).thenReturn(CompletableFuture.completedFuture(
                new MetadataLookupResult(List.of(candidate), List.of(), false)));
        MetadataReviewService service = new MetadataReviewService(books, lookup, new MetadataMergePreviewService(), directExecutor());

        MetadataReviewLookupResult result = service.lookup(book.getId()).join();

        assertThat(result.previews()).hasSize(1);
        assertThat(result.previews().getFirst().candidate()).isEqualTo(candidate);
        verify(lookup).search(argThat(q -> q.isbn().equals("9783161484100") && q.limit() == 10));
    }

    private static ExecutorPort directExecutor() {
        return new ExecutorPort() {
            @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
                try { return CompletableFuture.completedFuture(task.call()); }
                catch (Exception e) { return CompletableFuture.failedFuture(e); }
            }
            @Override public void execute(Runnable task) { task.run(); }
        };
    }
}
