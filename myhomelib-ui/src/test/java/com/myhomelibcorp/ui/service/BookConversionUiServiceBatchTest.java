package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import javafx.application.Platform;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.myhomelibcorp.application.progress.OperationStage;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookConversionUiServiceBatchTest {
    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyRunning) { started.countDown(); }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void awaitTerminal(OperationCenterService operations) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && operations.snapshot().stream().anyMatch(e -> e.active())) {
            Thread.sleep(5);
        }
        assertThat(operations.snapshot()).allMatch(e -> !e.active());
    }


    @Test
    void batchConversionKeepsAtMostTwoExternalConversionsInFlight() throws Exception {
        ConvertBookUseCase convertBook = mock(ConvertBookUseCase.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        DialogService dialogs = mock(DialogService.class);
        OperationCenterService operations = new OperationCenterService();
        ApplicationState applicationState = mock(ApplicationState.class);
        CompletableFuture<ConvertBookUseCase.Result> first = new CompletableFuture<>();
        CompletableFuture<ConvertBookUseCase.Result> second = new CompletableFuture<>();
        CompletableFuture<ConvertBookUseCase.Result> third = new CompletableFuture<>();

        when(background.submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any()))
                .thenReturn(first, second, third);

        BookConversionUiService service = new BookConversionUiService(
                convertBook, background, dialogs, operations, applicationState, mock(FileChooserService.class));
        List<BookId> selected = List.of(BookId.generate(), BookId.generate(), BookId.generate());

        Method method = BookConversionUiService.class.getDeclaredMethod(
                "runConversions", List.class, String.class, Path.class, String.class);
        method.setAccessible(true);
        method.invoke(service, selected, "epub", Path.of("target"), "collection-1");

        verify(background, timeout(3000).times(2)).submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any());
        assertThat(operations.snapshot()).hasSize(1);
        assertThat(operations.snapshot().getFirst().cancellable()).isTrue();

        // Each lane advances by index stride; either of the two initial futures may own
        // the final index depending on virtual-thread scheduling.
        first.complete(null);
        second.complete(null);

        verify(background, timeout(3000).times(3)).submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any());
    }

    @Test
    void cancellationDoesNotStartQueuedBatchConversions() throws Exception {
        ConvertBookUseCase convertBook = mock(ConvertBookUseCase.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        DialogService dialogs = mock(DialogService.class);
        OperationCenterService operations = new OperationCenterService();
        ApplicationState applicationState = mock(ApplicationState.class);
        CompletableFuture<ConvertBookUseCase.Result> first = new CompletableFuture<>();
        CompletableFuture<ConvertBookUseCase.Result> second = new CompletableFuture<>();

        when(background.submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any()))
                .thenReturn(first, second);

        BookConversionUiService service = new BookConversionUiService(
                convertBook, background, dialogs, operations, applicationState, mock(FileChooserService.class));
        List<BookId> selected = List.of(BookId.generate(), BookId.generate(), BookId.generate());

        Method method = BookConversionUiService.class.getDeclaredMethod(
                "runConversions", List.class, String.class, Path.class, String.class);
        method.setAccessible(true);
        method.invoke(service, selected, "epub", Path.of("target"), "collection-1");

        // Lane initialization is now asynchronous. First ensure the two allowed conversions
        // really started before simulating a user cancellation; queued work must not start.
        verify(background, timeout(3000).times(2)).submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any());
        String operationId = operations.snapshot().getFirst().operationId();
        assertThat(operations.requestCancellation(operationId)).isTrue();
        first.complete(null);

        verify(background, timeout(3000).times(2)).submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any());
    }

    @Test
    void batchStreamsProgressAndPreservesPartialFailureWithoutRetainingPerBookResults() throws Exception {
        ConvertBookUseCase convertBook = mock(ConvertBookUseCase.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        DialogService dialogs = mock(DialogService.class);
        OperationCenterService operations = new OperationCenterService();
        ApplicationState state = mock(ApplicationState.class);
        CompletableFuture<ConvertBookUseCase.Result> first = new CompletableFuture<>();
        CompletableFuture<ConvertBookUseCase.Result> second = new CompletableFuture<>();
        CompletableFuture<ConvertBookUseCase.Result> third = new CompletableFuture<>();
        when(background.submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any()))
                .thenReturn(first, second, third);
        BookConversionUiService service = new BookConversionUiService(
                convertBook, background, dialogs, operations, state, mock(FileChooserService.class));
        List<BookId> selected = List.of(BookId.generate(), BookId.generate(), BookId.generate());
        Method method = BookConversionUiService.class.getDeclaredMethod(
                "runConversions", List.class, String.class, Path.class, String.class);
        method.setAccessible(true);
        method.invoke(service, selected, "epub", Path.of("target"), "collection-1");
        verify(background, timeout(3000).times(2)).submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any());
        assertThat(operations.snapshot().getFirst().processed()).isZero();
        first.complete(null);
        assertThat(operations.snapshot().getFirst().processed()).isBetween(1L, 2L);
        second.completeExceptionally(new IllegalStateException("test converter failure"));
        verify(background, timeout(3000).times(3)).submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any());
        third.complete(null);
        awaitTerminal(operations);
        var finalEntry = operations.snapshot().getFirst();
        assertThat(finalEntry.stage()).isEqualTo(OperationStage.FAILED);
        assertThat(finalEntry.processed()).isEqualTo(3L);
        assertThat(finalEntry.errors()).isEqualTo(1L);
        org.mockito.Mockito.verify(dialogs, org.mockito.Mockito.never()).showInfo(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void formatDiscoveryRunsOffTheJavaFxThreadAndDoesNotBlockTheSelectionHandler() throws Exception {
        ConvertBookUseCase convertBook = mock(ConvertBookUseCase.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        DialogService dialogs = mock(DialogService.class);
        OperationCenterService operations = new OperationCenterService();
        ApplicationState state = mock(ApplicationState.class);
        when(state.getCurrentLibraryCollectionId()).thenReturn("collection-1");
        BookId selected = BookId.generate();
        java.util.concurrent.atomic.AtomicReference<Callable<List<String>>> query = new java.util.concurrent.atomic.AtomicReference<>();
        CompletableFuture<List<String>> pending = new CompletableFuture<>();
        when(background.submitCollectionScoped(eq(LibraryOperationType.QUERY),
                org.mockito.ArgumentMatchers.<Callable<List<String>>>any()))
                .thenAnswer(invocation -> {
                    query.set(invocation.getArgument(1));
                    return pending;
                });
        BookConversionUiService service = new BookConversionUiService(
                convertBook, background, dialogs, operations, state, mock(FileChooserService.class));
        service.show(null, null, List.of(selected));
        assertThat(query.get()).isNotNull();
        org.mockito.Mockito.verifyNoInteractions(convertBook);
        assertThat(operations.snapshot()).hasSize(1);
        assertThat(operations.snapshot().getFirst().active()).isTrue();
        // The queries are executed only when the background executor runs the submitted callable.
        assertThat(Platform.isFxApplicationThread()).isFalse();
        assertThat(query.get().call()).isEmpty();
        pending.complete(List.of());
        awaitTerminal(operations);
        assertThat(operations.snapshot().getFirst().stage()).isEqualTo(OperationStage.COMPLETED);
        org.mockito.Mockito.verify(dialogs, timeout(3000)).showWarning(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchOffersOnlyFormatsAvailableForEverySelectedBook() throws Exception {
        ConvertBookUseCase convertBook = mock(ConvertBookUseCase.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        DialogService dialogs = mock(DialogService.class);
        OperationCenterService operations = new OperationCenterService();
        ApplicationState applicationState = mock(ApplicationState.class);
        BookConversionUiService service = new BookConversionUiService(
                convertBook, background, dialogs, operations, applicationState, mock(FileChooserService.class));
        BookId first = BookId.generate();
        BookId second = BookId.generate();

        var epub = new ConvertBookUseCase.CapabilityView("calibre", true,
                BookConversionCapability.anySource("epub", ".epub"));
        var pdf = new ConvertBookUseCase.CapabilityView("calibre", true,
                BookConversionCapability.anySource("pdf", ".pdf"));
        when(convertBook.capabilityMatrix(first, "collection-1")).thenReturn(List.of(epub, pdf));
        when(convertBook.capabilityMatrix(second, "collection-1")).thenReturn(List.of(epub));

        Method method = BookConversionUiService.class.getDeclaredMethod(
                "commonFormats", List.class, String.class);
        method.setAccessible(true);
        List<String> formats = (List<String>) method.invoke(service, List.of(first, second), "collection-1");

        assertThat(formats).containsExactly("epub");
    }
}
