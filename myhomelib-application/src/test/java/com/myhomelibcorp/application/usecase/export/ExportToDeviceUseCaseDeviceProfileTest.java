package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.action.BookActionExecutionService;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.ExportHistoryService;
import com.myhomelibcorp.application.export.ExportCompletionService;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExportToDeviceUseCaseDeviceProfileTest {
    @TempDir Path temp;

    @Test
    void copiesPreferredExistingArtifactWithoutInvokingConverter() throws Exception {
        BookId id = BookId.generate();
        BookArtifact fb2 = artifact("fb2", "book.fb2", "fb2", "FB2".getBytes(StandardCharsets.UTF_8));
        BookArtifact epub = artifact("epub", "book.epub", "epub", "EPUB".getBytes(StandardCharsets.UTF_8));
        Book book = Book.builder().id(id).title("Book")
                .file(fb2.getFile()).artifacts(List.of(fb2, epub)).preferredArtifactId(fb2.getId()).local(true).build();

        BookQueryRepository books = mock(BookQueryRepository.class);
        BookConverter converter = mock(BookConverter.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        BookActionProfileService actions = mock(BookActionProfileService.class);
        BookActionExecutionService actionExecution = mock(BookActionExecutionService.class);
        ExportHistoryService history = mock(ExportHistoryService.class);
        when(books.findById(id)).thenReturn(Optional.of(book));
        when(resources.readBookData(eq("book.epub"), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new ByteArrayInputStream("EPUB".getBytes(StandardCharsets.UTF_8))));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(converter.isAvailable()).thenReturn(true);
        when(converter.supports(any())).thenReturn(true);
        when(converter.getTargetExtension()).thenReturn(".epub");
        when(converter.getFormatName()).thenReturn("EPUB");
        ExportToDeviceUseCase useCase = new ExportToDeviceUseCase(books, List.of(converter), resources,
                settings, actions, actionExecution, history, mock(ExportCompletionService.class), mock(ConvertBookUseCase.class));

        Path destination = temp.resolve("device");
        ExportRequest request = ExportRequest.builder()
                .bookIds(List.of(id)).destinationFolder(destination)
                .format(ExportRequest.ExportFormat.EPUB)
                .preferredFormats(List.of(ExportRequest.ExportFormat.EPUB, ExportRequest.ExportFormat.FB2))
                .collisionPolicy(ExportRequest.CollisionPolicy.OVERWRITE)
                .customFileNameTemplate("book").subfolderTemplate("")
                .build();

        ExportToDeviceUseCase.ExportResult result = useCase.execute(request);

        assertThat(result.failed()).isZero();
        assertThat(result.exported()).isEqualTo(1);
        assertThat(Files.readString(destination.resolve("Без автора").resolve("book.epub")))
                .isEqualTo("EPUB");
        verify(converter, never()).convert(any(), any(), any());
    }


    @Test
    void selectedFb2ZipUsesConverterBeforeLaterDirectFb2Fallback() throws Exception {
        BookId id = BookId.generate();
        BookArtifact fb2 = artifact("fb2", "book.fb2", "fb2", "FB2".getBytes(StandardCharsets.UTF_8));
        Book book = Book.builder().id(id).title("Book")
                .file(fb2.getFile()).artifacts(List.of(fb2)).preferredArtifactId(fb2.getId()).local(true).build();

        BookQueryRepository books = mock(BookQueryRepository.class);
        BookConverter converter = mock(BookConverter.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(books.findById(id)).thenReturn(Optional.of(book));
        when(resources.readBookData(book)).thenReturn(Optional.of(
                new ByteArrayInputStream("FB2".getBytes(StandardCharsets.UTF_8))));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(converter.isAvailable()).thenReturn(true);
        when(converter.supports(book)).thenReturn(true);
        when(converter.getTargetExtension()).thenReturn(".fb2.zip");
        when(converter.getFormatName()).thenReturn("FB2 ZIP");
        doAnswer(inv -> {
            java.io.InputStream input = inv.getArgument(1);
            Path target = inv.getArgument(2);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
                zip.putNextEntry(new ZipEntry("book.fb2"));
                input.transferTo(zip);
                zip.closeEntry();
            }
            return null;
        }).when(converter).convert(eq(book), any(), any());

        ExportToDeviceUseCase useCase = new ExportToDeviceUseCase(books, List.of(converter), resources, settings,
                mock(BookActionProfileService.class), mock(BookActionExecutionService.class),
                mock(ExportHistoryService.class), mock(ExportCompletionService.class), mock(ConvertBookUseCase.class));

        Path destination = temp.resolve("fb2zip-device");
        ExportRequest request = ExportRequest.builder()
                .bookIds(List.of(id)).destinationFolder(destination)
                .format(ExportRequest.ExportFormat.FB2_ZIP)
                .preferredFormats(List.of(ExportRequest.ExportFormat.FB2_ZIP, ExportRequest.ExportFormat.FB2))
                .collisionPolicy(ExportRequest.CollisionPolicy.OVERWRITE)
                .customFileNameTemplate("book").subfolderTemplate("safe")
                .build();

        ExportToDeviceUseCase.ExportResult result = useCase.execute(request);

        Path archive = destination.resolve("safe").resolve("book.fb2.zip");
        assertThat(result.failed()).isZero();
        assertThat(result.exported()).isEqualTo(1);
        assertThat(archive).exists();
        assertThat(destination.resolve("safe").resolve("book.fb2")).doesNotExist();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertThat(zip.getEntry("book.fb2")).isNotNull();
        }
        verify(converter).convert(eq(book), any(), argThat(path -> path != null && path.getFileName().toString().endsWith(".fb2.zip")));
    }

    @Test
    void copiesLegacySourceDirectlyWhenRequestedFormatExistsButNoConverterIsAvailable() throws Exception {
        BookId id = BookId.generate();
        Book book = Book.builder().id(id).title("Legacy")
                .file(new BookFile("legacy.fb2", "", "", 4, temp.toString())).local(true).build();
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(books.findById(id)).thenReturn(Optional.of(book));
        when(resources.locateBookFile(book)).thenReturn(Optional.of(temp.resolve("legacy.fb2")));
        when(resources.readBookData(book)).thenReturn(Optional.of(
                new ByteArrayInputStream("FB2".getBytes(StandardCharsets.UTF_8))));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        ExportToDeviceUseCase useCase = new ExportToDeviceUseCase(books, List.of(), resources, settings,
                mock(BookActionProfileService.class), mock(BookActionExecutionService.class),
                mock(ExportHistoryService.class), mock(ExportCompletionService.class), mock(ConvertBookUseCase.class));

        Path destination = temp.resolve("legacy-device");
        ExportRequest request = ExportRequest.builder()
                .bookIds(List.of(id)).destinationFolder(destination)
                .format(ExportRequest.ExportFormat.FB2)
                .collisionPolicy(ExportRequest.CollisionPolicy.OVERWRITE)
                .customFileNameTemplate("legacy").subfolderTemplate("safe")
                .build();

        ExportToDeviceUseCase.ExportResult result = useCase.execute(request);

        assertThat(result.failed()).isZero();
        assertThat(result.exported()).isEqualTo(1);
        assertThat(Files.readString(destination.resolve("safe").resolve("legacy.fb2"))).isEqualTo("FB2");
    }

    @Test
    void supportedFormatsAreDerivedFromConversionCapabilityMatrix() {
        ConvertBookUseCase conversion = mock(ConvertBookUseCase.class);
        when(conversion.capabilityMatrix()).thenReturn(List.of(
                new ConvertBookUseCase.CapabilityView("epub-provider", true,
                        new BookConversionCapability(java.util.Set.of("fb2"), "epub", ".epub")),
                new ConvertBookUseCase.CapabilityView("offline-pdf", false,
                        new BookConversionCapability(java.util.Set.of("fb2"), "pdf", ".pdf"))
        ));
        ExportToDeviceUseCase useCase = new ExportToDeviceUseCase(mock(BookQueryRepository.class), List.of(),
                mock(BookResourcePort.class), mock(ApplicationSettingsPort.class), mock(BookActionProfileService.class),
                mock(BookActionExecutionService.class), mock(ExportHistoryService.class), mock(ExportCompletionService.class), conversion);

        assertThat(useCase.supportedFormats()).containsExactly(ExportRequest.ExportFormat.EPUB);
        verify(conversion).capabilityMatrix();
    }

    @Test
    void supportedFormatsForBooksIncludesLocalAlternativeArtifact() {
        BookId id = BookId.generate();
        BookArtifact fb2 = artifact("fb2", "book.fb2", "fb2", new byte[]{1});
        BookArtifact epub = artifact("epub", "book.epub", "epub", new byte[]{1});
        Book book = Book.builder().id(id).title("Book").file(fb2.getFile())
                .artifacts(List.of(fb2, epub)).local(true).build();
        BookQueryRepository books = mock(BookQueryRepository.class);
        when(books.findById(id)).thenReturn(Optional.of(book));
        ExportToDeviceUseCase useCase = new ExportToDeviceUseCase(books, List.of(), mock(BookResourcePort.class),
                mock(ApplicationSettingsPort.class), mock(BookActionProfileService.class),
                mock(BookActionExecutionService.class), mock(ExportHistoryService.class), mock(ExportCompletionService.class), mock(ConvertBookUseCase.class));

        assertThat(useCase.supportedFormatsForBooks(List.of(id)))
                .containsExactlyInAnyOrder(ExportRequest.ExportFormat.FB2, ExportRequest.ExportFormat.EPUB);
    }

    private BookArtifact artifact(String id, String fileName, String format, byte[] ignored) {
        return BookArtifact.builder().id(id).format(format).local(true)
                .file(new BookFile(fileName, "", "", Math.max(1, ignored.length), temp.toString())).build();
    }
}
