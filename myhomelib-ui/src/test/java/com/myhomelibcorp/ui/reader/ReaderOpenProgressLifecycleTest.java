package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.render.javafx.ReaderView;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiAsyncRequestToken;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.StatusBarViewModel;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.*;

class ReaderOpenProgressLifecycleTest {

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
    void staleOpenProgressDoesNotMutateStatusAfterControllerIsDisposed() throws Exception {
        NewReaderWorkspaceController controller = mock(NewReaderWorkspaceController.class, CALLS_REAL_METHODS);
        ApplicationState appState = mock(ApplicationState.class);
        StatusBarViewModel statusBar = mock(StatusBarViewModel.class);
        when(appState.getStatusBar()).thenReturn(statusBar);
        setField(controller, "appState", appState);
        setField(controller, "openGeneration", new AtomicLong());
        setField(controller, "isDisposed", true);

        invokeProgress(controller, new UiAsyncRequestToken(0L, null), 0.6, "stale progress");
        runFxAndWait(() -> { });

        verify(statusBar, never()).setStatusText("stale progress");
    }

    @Test
    void staleOpenProgressDoesNotMutateStatusAfterNewerOpenStarts() throws Exception {
        NewReaderWorkspaceController controller = mock(NewReaderWorkspaceController.class, CALLS_REAL_METHODS);
        ApplicationState appState = mock(ApplicationState.class);
        StatusBarViewModel statusBar = mock(StatusBarViewModel.class);
        UiBackgroundExecutor executor = mock(UiBackgroundExecutor.class);
        ReaderView readerView = mock(ReaderView.class);
        ReaderEngine engine = mock(ReaderEngine.class);
        LoadBookByIdUseCase loader = mock(LoadBookByIdUseCase.class);
        com.myhomelibcorp.ui.service.LocalizationService i18n = mock(com.myhomelibcorp.ui.service.LocalizationService.class);

        when(appState.getStatusBar()).thenReturn(statusBar);
        when(appState.getCurrentLibraryCollectionId()).thenReturn("collection-a");
        when(readerView.getEngine()).thenReturn(engine);
        when(loader.execute(any(BookId.class))).thenReturn(Optional.empty());
        when(i18n.text("ui.reader.progress.loading_metadata")).thenReturn("loading metadata");
        when(i18n.format(anyString(), any())).thenReturn("missing");

        List<Callable<?>> tasks = new ArrayList<>();
        Future<?> firstFuture = mock(Future.class);
        Future<?> secondFuture = mock(Future.class);
        when(firstFuture.isDone()).thenReturn(false);
        when(secondFuture.isDone()).thenReturn(false);
        when(executor.submitCollectionScopedCancellable(any(), any())).thenAnswer(invocation -> {
            tasks.add(invocation.getArgument(1));
            return tasks.size() == 1 ? firstFuture : secondFuture;
        });

        setField(controller, "appState", appState);
        setField(controller, "openGeneration", new AtomicLong());
        setField(controller, "readerView", readerView);
        setField(controller, "uiBackgroundExecutor", executor);
        setField(controller, "loadBookByIdUseCase", loader);
        setField(controller, "i18n", i18n);

        invokeBeginOpen(controller, BookId.fromString("11111111-1111-1111-1111-111111111111"));
        invokeBeginOpen(controller, BookId.fromString("22222222-2222-2222-2222-222222222222"));
        assertThat(tasks).hasSize(2);

        tasks.getFirst().call();
        runFxAndWait(() -> { });

        verify(statusBar, never()).setStatusText("loading metadata");
    }

    private static void invokeBeginOpen(NewReaderWorkspaceController controller, BookId bookId) throws Exception {
        Method method = NewReaderWorkspaceController.class.getDeclaredMethod(
                "beginBookOpen", BookId.class, String.class, boolean.class);
        method.setAccessible(true);
        method.invoke(controller, bookId, null, false);
    }

    private static void invokeProgress(NewReaderWorkspaceController controller, UiAsyncRequestToken requestToken,
                                       double progress, String status) throws Exception {
        Method method = NewReaderWorkspaceController.class.getDeclaredMethod(
                "showDownloadProgress", UiAsyncRequestToken.class, double.class, String.class);
        method.setAccessible(true);
        method.invoke(controller, requestToken, progress, status);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = NewReaderWorkspaceController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void runFxAndWait(Runnable action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
