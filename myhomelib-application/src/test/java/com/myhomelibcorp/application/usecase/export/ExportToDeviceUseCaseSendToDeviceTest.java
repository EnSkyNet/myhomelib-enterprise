package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.application.action.BookActionExecutionService;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.ExportCollisionDecision;
import com.myhomelibcorp.application.export.ExportCompletionService;
import com.myhomelibcorp.application.export.ExportHistoryService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ExportToDeviceUseCaseSendToDeviceTest {

    @TempDir Path temp;

    @Test
    void ejectSafeCompletionRunsOnlyAfterCommittedTargetIsReadable() throws Exception {
        Fixture f = new Fixture(temp, "One");
        ExportRequest request = f.request(ExportRequest.CollisionPolicy.RENAME, false,
                ExportRequest.CompletionPolicy.EJECT_SAFE);

        doAnswer(invocation -> {
            Path target = invocation.getArgument(0);
            assertThat(Files.isRegularFile(target)).isTrue();
            assertThat(Files.readString(target)).isEqualTo("One");
            return null;
        }).when(f.completion).complete(any(Path.class), eq(ExportRequest.CompletionPolicy.EJECT_SAFE));

        ExportToDeviceUseCase.ExportResult result = f.useCase.execute(request);

        assertThat(result.exported()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(f.completion).complete(f.destination.resolve("safe/One.fb2"), ExportRequest.CompletionPolicy.EJECT_SAFE);
    }

    @Test
    void defaultCollisionPolicyRenamesAndNeverOverwritesExistingFile() throws Exception {
        Fixture f = new Fixture(temp, "One");
        Path existing = f.destination.resolve("safe/One.fb2");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "OLD", StandardCharsets.UTF_8);

        ExportRequest request = f.request(null, false, null);
        ExportToDeviceUseCase.ExportResult result = f.useCase.execute(request);

        assertThat(result.exported()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(Files.readString(existing)).isEqualTo("OLD");
        assertThat(Files.readString(f.destination.resolve("safe/One (1).fb2"))).isEqualTo("One");
    }

    @Test
    void askCollisionCanSkipWithoutTouchingExistingFile() throws Exception {
        Fixture f = new Fixture(temp, "One");
        Path existing = f.destination.resolve("safe/One.fb2");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "OLD", StandardCharsets.UTF_8);

        ExportToDeviceUseCase.ExportResult result = f.useCase.execute(
                f.request(ExportRequest.CollisionPolicy.ASK, false, null), new AtomicBoolean(false), p -> { },
                context -> ExportCollisionDecision.SKIP);

        assertThat(result.exported()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(Files.readString(existing)).isEqualTo("OLD");
        verifyNoInteractions(f.completion);
    }

    @Test
    void cancellationAfterFirstProgressStopsBeforeSecondBookAndLeavesConsistentBatch() throws Exception {
        BookId firstId = BookId.generate();
        BookId secondId = BookId.generate();
        Book first = book(firstId, "First", 5);
        Book second = book(secondId, "Second", 6);
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookResourcePort resources = mock(BookResourcePort.class);
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        ExportCompletionService completion = mock(ExportCompletionService.class);
        when(books.findById(firstId)).thenReturn(Optional.of(first));
        when(books.findById(secondId)).thenReturn(Optional.of(second));
        when(resources.locateBookFile(any(Book.class))).thenReturn(Optional.of(temp.resolve("source.fb2")));
        when(resources.readBookData(first)).thenReturn(Optional.of(new ByteArrayInputStream("FIRST".getBytes(StandardCharsets.UTF_8))));
        when(resources.readBookData(second)).thenReturn(Optional.of(new ByteArrayInputStream("SECOND".getBytes(StandardCharsets.UTF_8))));
        when(settings.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        ExportToDeviceUseCase useCase = new ExportToDeviceUseCase(books, List.of(), resources, settings,
                mock(BookActionProfileService.class), mock(BookActionExecutionService.class),
                mock(ExportHistoryService.class), completion, mock(ConvertBookUseCase.class));

        Path destination = temp.resolve("batch");
        ExportRequest request = ExportRequest.builder()
                .bookIds(List.of(firstId, secondId))
                .destinationFolder(destination)
                .format(ExportRequest.ExportFormat.FB2)
                .collisionPolicy(ExportRequest.CollisionPolicy.RENAME)
                .customFileNameTemplate("%t")
                .subfolderTemplate("safe")
                .build();
        AtomicBoolean cancel = new AtomicBoolean(false);
        List<ExportToDeviceUseCase.ExportProgress> progress = new ArrayList<>();

        ExportToDeviceUseCase.ExportResult result = useCase.execute(request, cancel, event -> {
            progress.add(event);
            if (event.processed() == 1) cancel.set(true);
        });

        assertThat(result.exported()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.cancelled()).isTrue();
        assertThat(progress).extracting(ExportToDeviceUseCase.ExportProgress::processed).containsExactly(1);
        assertThat(Files.readString(destination.resolve("safe/First.fb2"))).isEqualTo("FIRST");
        assertThat(destination.resolve("safe/Second.fb2")).doesNotExist();
        verify(books, never()).findById(secondId);
    }

    @Test
    void completionFailurePreventsSuccessClaimAndLeavesNoTemporaryFile() throws Exception {
        Fixture f = new Fixture(temp, "One");
        ExportRequest request = f.request(ExportRequest.CollisionPolicy.RENAME, false,
                ExportRequest.CompletionPolicy.EJECT_SAFE);
        doThrow(new java.io.IOException("flush failed")).when(f.completion)
                .complete(any(Path.class), eq(ExportRequest.CompletionPolicy.EJECT_SAFE));

        ExportToDeviceUseCase.ExportResult result = f.useCase.execute(request);

        assertThat(result.exported()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.errors()).anySatisfy(error -> assertThat(error).contains("flush failed"));
        try (var files = Files.list(f.destination.resolve("safe"))) {
            assertThat(files.noneMatch(path -> path.getFileName().toString().startsWith(".mhl-export-"))).isTrue();
        }
    }

    private Book book(BookId id, String title, long size) {
        return Book.builder().id(id).title(title)
                .file(new BookFile(title.toLowerCase() + ".fb2", "", "", size, temp.toString()))
                .local(true).build();
    }

    private final class Fixture {
        final BookId id = BookId.generate();
        final Book book;
        final BookQueryRepository books = mock(BookQueryRepository.class);
        final BookResourcePort resources = mock(BookResourcePort.class);
        final ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        final ExportCompletionService completion = mock(ExportCompletionService.class);
        final ExportToDeviceUseCase useCase;
        final Path destination;

        Fixture(Path temp, String title) {
            destination = temp.resolve("device");
            book = book(id, title, title.length());
            when(books.findById(id)).thenReturn(Optional.of(book));
            when(resources.locateBookFile(book)).thenReturn(Optional.of(temp.resolve("source.fb2")));
            when(resources.readBookData(book)).thenReturn(Optional.of(
                    new ByteArrayInputStream(title.getBytes(StandardCharsets.UTF_8))));
            when(settings.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
            when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
            useCase = new ExportToDeviceUseCase(books, List.of(), resources, settings,
                    mock(BookActionProfileService.class), mock(BookActionExecutionService.class),
                    mock(ExportHistoryService.class), completion, mock(ConvertBookUseCase.class));
        }

        ExportRequest request(ExportRequest.CollisionPolicy collisionPolicy, boolean overwriteExisting,
                              ExportRequest.CompletionPolicy completionPolicy) {
            return ExportRequest.builder()
                    .bookIds(List.of(id))
                    .destinationFolder(destination)
                    .format(ExportRequest.ExportFormat.FB2)
                    .collisionPolicy(collisionPolicy)
                    .overwriteExisting(overwriteExisting)
                    .completionPolicy(completionPolicy)
                    .customFileNameTemplate("%t")
                    .subfolderTemplate("safe")
                    .build();
        }
    }
}
