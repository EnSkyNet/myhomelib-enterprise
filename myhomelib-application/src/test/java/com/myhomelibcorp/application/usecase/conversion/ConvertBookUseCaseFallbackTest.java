package com.myhomelibcorp.application.usecase.conversion;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.ConversionFailureSignal;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ConvertBookUseCaseFallbackTest {
    @TempDir Path tempDir;

    @Test
    void fallsThroughOnlyWhenFailureExplicitlyAllowsNextProvider() throws Exception {
        BookId id = BookId.generate();
        Book book = Book.builder().id(id).title("Fallback").file(new BookFile("book.fb2", "", "", 4, "")).local(true).build();
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        when(books.findById(id)).thenReturn(Optional.of(book));
        when(resources.readBookData(book)).thenAnswer(ignored -> Optional.of(new ByteArrayInputStream("<fb2".getBytes(StandardCharsets.UTF_8))));

        AtomicInteger fallbackCalls = new AtomicInteger();
        BookConverter unavailableToolchain = converter("myhomelib-toolchain", 500, (source, target) -> {
            throw new RetryableConversionException("tool unavailable");
        });
        BookConverter calibre = converter("calibre-core", 100, (source, target) -> {
            fallbackCalls.incrementAndGet();
            Files.writeString(target, "converted", StandardCharsets.UTF_8);
        });

        ConvertBookUseCase useCase = new ConvertBookUseCase(books, List.of(calibre, unavailableToolchain), resources, mutations);
        ConvertBookUseCase.Result result = useCase.execute(ConvertBookUseCase.Request.withDefaults(id, "epub", tempDir));

        assertThat(result.converterId()).isEqualTo("calibre-core");
        assertThat(fallbackCalls).hasValue(1);
        verify(mutations).upsertArtifact(eq(id), any(), eq(false));
    }

    @Test
    void hardFailureDoesNotFallThroughToLowerProvider() throws Exception {
        BookId id = BookId.generate();
        Book book = Book.builder().id(id).title("Hard stop").file(new BookFile("book.fb2", "", "", 4, "")).local(true).build();
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        when(books.findById(id)).thenReturn(Optional.of(book));
        when(resources.readBookData(book)).thenAnswer(ignored -> Optional.of(new ByteArrayInputStream("<fb2".getBytes(StandardCharsets.UTF_8))));

        AtomicInteger fallbackCalls = new AtomicInteger();
        BookConverter brokenToolchain = converter("myhomelib-toolchain", 500, (source, target) -> {
            throw new IOException("invalid input");
        });
        BookConverter calibre = converter("calibre-core", 100, (source, target) -> {
            fallbackCalls.incrementAndGet();
            Files.writeString(target, "should-not-run", StandardCharsets.UTF_8);
        });

        ConvertBookUseCase useCase = new ConvertBookUseCase(books, List.of(calibre, brokenToolchain), resources, mutations);

        assertThatThrownBy(() -> useCase.execute(ConvertBookUseCase.Request.withDefaults(id, "epub", tempDir)))
                .isInstanceOfSatisfying(BookConversionException.class, failure -> {
                    assertThat(failure.stage()).isEqualTo(BookConversionException.Stage.PROVIDER);
                    assertThat(failure.providerId()).isEqualTo("myhomelib-toolchain");
                    assertThat(failure.getCause()).isInstanceOf(IOException.class)
                            .hasMessageContaining("invalid input");
                });
        assertThat(fallbackCalls).hasValue(0);
        verifyNoInteractions(mutations);
    }

    private static BookConverter converter(String id, int priority, ConverterBody body) {
        return new BookConverter() {
            @Override public String id() { return id; }
            @Override public int priority() { return priority; }
            @Override public boolean supports(Book book) { return true; }
            @Override public boolean supports(Book book, String sourceFormat) { return "fb2".equals(sourceFormat); }
            @Override public String getTargetExtension() { return ".epub"; }
            @Override public String getFormatName() { return "epub"; }
            @Override public Set<BookConversionCapability> capabilities() {
                return Set.of(new BookConversionCapability(Set.of("fb2"), "epub", ".epub"));
            }
            @Override public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
                body.convert(sourceStream, targetFile);
            }
        };
    }

    @FunctionalInterface
    private interface ConverterBody { void convert(InputStream source, Path target) throws Exception; }

    private static final class RetryableConversionException extends Exception implements ConversionFailureSignal {
        private RetryableConversionException(String message) { super(message); }
        @Override public boolean mayTryNextProvider() { return true; }
    }
}
