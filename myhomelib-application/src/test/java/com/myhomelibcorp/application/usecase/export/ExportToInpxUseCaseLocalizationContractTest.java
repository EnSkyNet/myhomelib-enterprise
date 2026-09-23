package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.application.dto.InpxExportRequest;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportToInpxUseCaseLocalizationContractTest {

    @Test
    void validationFailuresExposeStableKeysInsteadOfDisplayText() {
        ExportToInpxUseCase useCase = new ExportToInpxUseCase(mock(BookQueryRepository.class), activeLifecycle(), new com.myhomelibcorp.application.operation.LibraryOperationCoordinator());

        ExportToInpxUseCase.ExportResult noRequest = useCase.execute(null);
        assertThat(noRequest.errorKey()).isEqualTo("ui.export.inpx.error.no_request");
        assertThat(noRequest.technicalDetail()).isEmpty();

        ExportToInpxUseCase.ExportResult noOutput = useCase.execute(InpxExportRequest.builder().collectionId("c1").build());
        assertThat(noOutput.errorKey()).isEqualTo("ui.export.inpx.error.no_output");
        assertThat(noOutput.technicalDetail()).isEmpty();
    }

    @Test
    void technicalFailureIsSeparatedFromUserFacingLocalizationKey() throws IOException {
        BookQueryRepository books = mock(BookQueryRepository.class);
        when(books.streamAll()).thenThrow(new IllegalStateException("SQLITE_BUSY secret-driver-detail"));
        ExportToInpxUseCase useCase = new ExportToInpxUseCase(books, activeLifecycle(), new com.myhomelibcorp.application.operation.LibraryOperationCoordinator());
        Path output = Files.createTempDirectory("inpx-i18n-").resolve("library.inpx");

        ExportToInpxUseCase.ExportResult result = useCase.execute(InpxExportRequest.builder()
                .collectionId("c1")
                .outputFile(output)
                .build());

        assertThat(result.errorKey()).isEqualTo("ui.export.inpx.error.generic");
        assertThat(result.technicalDetail()).contains("SQLITE_BUSY");
        assertThat(result.errorKey()).doesNotContain("SQLITE_BUSY");
    }

    @Test
    void emptyCatalogUsesLocalizedNoBooksKey() throws IOException {
        BookQueryRepository books = mock(BookQueryRepository.class);
        when(books.streamAll()).thenReturn(Stream.empty());
        ExportToInpxUseCase useCase = new ExportToInpxUseCase(books, activeLifecycle(), new com.myhomelibcorp.application.operation.LibraryOperationCoordinator());
        Path output = Files.createTempDirectory("inpx-empty-").resolve("library.inpx");

        ExportToInpxUseCase.ExportResult result = useCase.execute(InpxExportRequest.builder()
                .collectionId("c1")
                .outputFile(output)
                .build());

        assertThat(result.errorKey()).isEqualTo("ui.export.inpx.error.no_books");
        assertThat(result.technicalDetail()).isEmpty();
    }
    @Test
    void missingAuthorAndUnknownUpdateDateStayEmpty() throws Exception {
        BookQueryRepository books = mock(BookQueryRepository.class);
        LocalDateTime createdAt = LocalDateTime.of(2020, 5, 6, 7, 8, 9);
        Book book = Book.builder()
                .id(BookId.generate())
                .title("Title")
                .file(new BookFile("book.fb2", "", "", 123, ""))
                .createdAt(createdAt)
                .updateDate(null)
                .build();
        when(books.streamAll()).thenReturn(Stream.of(book));
        ExportToInpxUseCase useCase = new ExportToInpxUseCase(books, activeLifecycle(), new com.myhomelibcorp.application.operation.LibraryOperationCoordinator());
        Path output = Files.createTempDirectory("inpx-neutral-").resolve("library.inpx");

        ExportToInpxUseCase.ExportResult result = useCase.execute(InpxExportRequest.builder()
                .collectionId("c1")
                .outputFile(output)
                .collectionVersion("20200506")
                .build());

        assertThat(result.failed()).isZero();
        try (ZipFile zip = new ZipFile(output.toFile())) {
            String line = new String(zip.getInputStream(zip.getEntry("books.inp")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            String[] fields = line.split(String.valueOf((char) 4), -1);
            assertThat(fields).hasSizeGreaterThanOrEqualTo(15);
            assertThat(fields[0]).isEmpty();
            assertThat(fields[10]).isEmpty();
        }
    }

    @Test
    void fatalStreamingFailureKeepsExistingOutputAndRemovesStagingFile() throws Exception {
        BookQueryRepository books = mock(BookQueryRepository.class);
        Book book = Book.builder().id(BookId.generate()).title("One").build();
        Iterator<Book> iterator = new Iterator<>() {
            private int step;
            @Override public boolean hasNext() {
                if (step < 1) return true;
                throw new IllegalStateException("stream failed");
            }
            @Override public Book next() {
                step++;
                return book;
            }
        };
        Stream<Book> failing = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, 0), false);
        when(books.streamAll()).thenReturn(failing);
        ExportToInpxUseCase useCase = new ExportToInpxUseCase(books, activeLifecycle(), new com.myhomelibcorp.application.operation.LibraryOperationCoordinator());
        Path dir = Files.createTempDirectory("inpx-crash-safe-");
        Path output = dir.resolve("library.inpx");
        Files.writeString(output, "PREVIOUS");

        ExportToInpxUseCase.ExportResult result = useCase.execute(InpxExportRequest.builder()
                .collectionId("c1")
                .outputFile(output)
                .build());

        assertThat(result.errorKey()).isEqualTo("ui.export.inpx.error.generic");
        assertThat(Files.readString(output)).isEqualTo("PREVIOUS");
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.map(path -> path.getFileName().toString())
                    .noneMatch(name -> name.endsWith(".part"))).isTrue();
        }
    }


    private static com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort activeLifecycle() {
        var lifecycle = org.mockito.Mockito.mock(
                com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort.class);
        org.mockito.Mockito.when(lifecycle.getCurrentCollection()).thenReturn(
                new com.myhomelibcorp.domain.model.collection.Collection(
                        "c1", "Test", null, null, 0, null, null, null, null));
        return lifecycle;
    }
}
