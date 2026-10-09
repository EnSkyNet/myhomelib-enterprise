package com.myhomelibcorp.ui.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.group.AddBookToGroupUseCase;
import com.myhomelibcorp.application.usecase.group.LoadGroupsUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.presenter.CoverPresenter;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.ui.service.ClassicLibraryActionsService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BookWorkspaceControllerAsyncTest {

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
    void completedDownloadDoesNotReloadBookAfterWorkspaceMovedToAnotherBook() throws Exception {
        BookDownloadCoordinator downloads = mock(BookDownloadCoordinator.class);
        UiBackgroundExecutor executor = mock(UiBackgroundExecutor.class);
        ApplicationState appState = mock(ApplicationState.class);
        when(appState.getCurrentLibraryCollectionId()).thenReturn("collection-1");

        CompletableFuture<BookDownloadCoordinator.BatchDownloadResult> downloadFuture = new CompletableFuture<>();
        when(downloads.downloadBatch(any(), any())).thenReturn(downloadFuture);
        @SuppressWarnings("unchecked")
        Future<Object> pendingLoad = mock(Future.class);
        when(executor.submitCollectionScopedCancellable(any(), any())).thenReturn(pendingLoad);

        BookWorkspaceController controller = new BookWorkspaceController(
                mock(LoadBookByIdUseCase.class),
                mock(LoadGroupsUseCase.class),
                mock(AddBookToGroupUseCase.class),
                mock(CoverPresenter.class),
                mock(NavigationService.class),
                mock(BookViewModelMapper.class),
                mock(DialogService.class),
                downloads,
                mock(BookSaver.class),
                mock(ClassicLibraryActionsService.class),
                executor,
                appState);

        BookDto first = BookDto.builder().id("11111111-1111-1111-1111-111111111111").title("A").build();
        setField(controller, "currentBook", first);

        runFxAndWait(() -> invokeUnchecked(controller, "onDownload"));
        controller.setBookId(BookId.fromString("22222222-2222-2222-2222-222222222222"));
        verify(executor, times(1)).submitCollectionScopedCancellable(any(), any());

        downloadFuture.complete(new BookDownloadCoordinator.BatchDownloadResult(1, 1, 0, 0, List.of()));
        runFxAndWait(() -> { });

        verify(executor, times(1)).submitCollectionScopedCancellable(any(), any());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = BookWorkspaceController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invokeUnchecked(Object target, String name) {
        try {
            Method method = BookWorkspaceController.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
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
