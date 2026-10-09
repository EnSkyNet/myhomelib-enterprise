package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookConversionUiServiceBatchTest {

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

        verify(background, times(2)).submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any());
        assertThat(operations.snapshot()).hasSize(1);
        assertThat(operations.snapshot().getFirst().cancellable()).isTrue();

        first.complete(null);

        verify(background, times(3)).submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
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

        String operationId = operations.snapshot().getFirst().operationId();
        assertThat(operations.requestCancellation(operationId)).isTrue();
        first.complete(null);

        verify(background, times(2)).submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any());
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
