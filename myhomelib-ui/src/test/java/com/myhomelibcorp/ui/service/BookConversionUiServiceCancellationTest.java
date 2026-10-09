package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookConversionUiServiceCancellationTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void conversionRegistersCancellableOperationThatSignalsTheRequestToken() throws Exception {
        ConvertBookUseCase convertBook = mock(ConvertBookUseCase.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        DialogService dialogs = mock(DialogService.class);
        OperationCenterService operations = new OperationCenterService();
        ApplicationState applicationState = mock(ApplicationState.class);
        AtomicReference<Callable<ConvertBookUseCase.Result>> submitted = new AtomicReference<>();
        CompletableFuture<ConvertBookUseCase.Result> pending = new CompletableFuture<>();

        when(background.submitCollectionScopedOperation(eq(LibraryOperationType.CONVERSION),
                org.mockito.ArgumentMatchers.<Callable<ConvertBookUseCase.Result>>any()))
                .thenAnswer(invocation -> {
                    submitted.set(invocation.getArgument(1));
                    return pending;
                });

        BookConversionUiService service = new BookConversionUiService(
                convertBook, background, dialogs, operations, applicationState, mock(FileChooserService.class));
        BookDto book = BookDto.builder().id(BookId.generate().asString()).title("Long conversion").build();
        invokeRunConversion(service, book, "epub", Path.of("target"), "collection-1");

        var entry = operations.snapshot().getFirst();
        assertThat(entry.cancellable()).isTrue();

        Method requestCancellation = OperationCenterService.class.getMethod("requestCancellation", String.class);
        assertThat(requestCancellation.invoke(operations, entry.operationId())).isEqualTo(Boolean.TRUE);

        when(convertBook.execute(any(ConvertBookUseCase.Request.class), eq("collection-1")))
                .thenAnswer(invocation -> {
                    ConvertBookUseCase.Request request = invocation.getArgument(0);
                    assertThat(request.cancelled().getAsBoolean()).isTrue();
                    throw new CancellationException("cancelled by user");
                });

        assertThatThrownBy(() -> submitted.get().call())
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("cancelled by user");

        pending.completeExceptionally(new CancellationException("cancelled by user"));
        runFxAndWait(() -> { });
        var terminal = operations.snapshot().getFirst();
        assertThat(terminal.stage()).isEqualTo(com.myhomelibcorp.application.progress.OperationStage.CANCELLED);
        assertThat(terminal.active()).isFalse();
        org.mockito.Mockito.verify(dialogs, org.mockito.Mockito.never()).showError(any(), any());
    }

    private static void runFxAndWait(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { action.run(); } finally { done.countDown(); }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private static void invokeRunConversion(BookConversionUiService service, BookDto book,
                                            String format, Path output, String collectionId) throws Exception {
        Method method = BookConversionUiService.class.getDeclaredMethod(
                "runConversion", BookDto.class, String.class, Path.class, String.class);
        method.setAccessible(true);
        method.invoke(service, book, format, output, collectionId);
    }
}
