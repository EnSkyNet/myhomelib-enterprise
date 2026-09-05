package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.application.action.BookActionExecutionService;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.ExportHistoryService;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.book.Book;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExportToDeviceUseCaseCrashSafetyTest {

    @TempDir
    Path temp;

    @Test
    void failedConverterCannotDestroyExistingOverwriteTargetAndLeavesNoPartialFile() throws Exception {
        Fixture f = new Fixture(temp);
        Path target = f.target();
        Files.createDirectories(target.getParent());
        Files.writeString(target, "OLD", StandardCharsets.UTF_8);
        doAnswer(invocation -> {
            Path staged = invocation.getArgument(2);
            Files.writeString(staged, "PARTIAL", StandardCharsets.UTF_8);
            throw new IllegalStateException("converter crashed");
        }).when(f.converter).convert(eq(f.book), any(), any(Path.class));

        ExportToDeviceUseCase.ExportResult result = f.useCase.execute(f.request(), new AtomicBoolean(false), p -> { });

        assertEquals(0, result.exported());
        assertEquals(1, result.failed());
        assertEquals("OLD", Files.readString(target));
        try (var stream = Files.list(target.getParent())) {
            assertTrue(stream.noneMatch(path -> path.getFileName().toString().startsWith(".mhl-export-")));
        }
    }

    @Test
    void successfulConverterAtomicallyReplacesExistingTarget() throws Exception {
        Fixture f = new Fixture(temp);
        Path target = f.target();
        Files.createDirectories(target.getParent());
        Files.writeString(target, "OLD", StandardCharsets.UTF_8);
        doAnswer(invocation -> {
            Path staged = invocation.getArgument(2);
            Files.writeString(staged, "NEW-CONTENT", StandardCharsets.UTF_8);
            return null;
        }).when(f.converter).convert(eq(f.book), any(), any(Path.class));

        ExportToDeviceUseCase.ExportResult result = f.useCase.execute(f.request(), new AtomicBoolean(false), p -> { });

        assertEquals(1, result.exported());
        assertEquals(0, result.failed());
        assertEquals("NEW-CONTENT", Files.readString(target));
        try (var stream = Files.list(target.getParent())) {
            assertTrue(stream.noneMatch(path -> path.getFileName().toString().startsWith(".mhl-export-")));
        }
    }

    private static final class Fixture {
        final BookId id = BookId.generate();
        final Book book;
        final BookQueryRepository books = mock(BookQueryRepository.class);
        final BookConverter converter = mock(BookConverter.class);
        final BookResourcePort resources = mock(BookResourcePort.class);
        final ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        final BookActionProfileService actions = mock(BookActionProfileService.class);
        final BookActionExecutionService actionExecution = mock(BookActionExecutionService.class);
        final ExportHistoryService history = mock(ExportHistoryService.class);
        final ExportToDeviceUseCase useCase;
        final Path destination;

        Fixture(Path temp) {
            destination = temp.resolve("export");
            book = Book.builder()
                    .id(id)
                    .title("Book")
                    .file(new BookFile("source.fb2", "", "", 16, temp.toString()))
                    .local(true)
                    .build();
            when(books.findById(id)).thenReturn(Optional.of(book));
            when(resources.locateBookFile(book)).thenReturn(Optional.of(temp.resolve("source.fb2")));
            when(resources.readBookData(book)).thenAnswer(ignored -> Optional.of(
                    new ByteArrayInputStream("SOURCE".getBytes(StandardCharsets.UTF_8))));
            when(converter.isAvailable()).thenReturn(true);
            when(converter.supports(book)).thenReturn(true);
            when(converter.getTargetExtension()).thenReturn(".fb2");
            when(converter.getFormatName()).thenReturn("FB2");
            when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
            useCase = new ExportToDeviceUseCase(books, List.of(converter), resources, settings,
                    actions, actionExecution, history);
        }

        ExportRequest request() {
            return ExportRequest.builder()
                    .bookIds(List.of(id))
                    .destinationFolder(destination)
                    .format(ExportRequest.ExportFormat.FB2)
                    .collisionPolicy(ExportRequest.CollisionPolicy.OVERWRITE)
                    .customFileNameTemplate("book")
                    .subfolderTemplate("safe")
                    .build();
        }

        Path target() {
            return destination.resolve("safe").resolve("book.fb2");
        }
    }
}
