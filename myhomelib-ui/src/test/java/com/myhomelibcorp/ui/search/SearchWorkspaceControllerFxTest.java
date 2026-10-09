package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.application.annotation.AnnotationManagerService;
import com.myhomelibcorp.application.content.search.ContentSearchService;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.application.search.SearchService;
import com.myhomelibcorp.application.usecase.search.BuildSmartCollectionSearchRequestUseCase;
import com.myhomelibcorp.application.usecase.search.LoadSavedSearchesUseCase;
import com.myhomelibcorp.application.usecase.search.SaveSearchUseCase;
import com.myhomelibcorp.ui.filter.BookFilterDialogService;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.navigation.WorkspaceManager;
import com.myhomelibcorp.ui.service.*;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SearchWorkspaceControllerFxTest {

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
    void savedSearchDefaultNameDoesNotSplitSurrogatePair() throws Exception {
        DialogService dialogs = mock(DialogService.class);
        ArgumentCaptor<String> defaultName = ArgumentCaptor.forClass(String.class);
        when(dialogs.showTextInput(anyString(), anyString(), anyString(), defaultName.capture()))
                .thenReturn(Optional.empty());
        LocalizationService i18n = mock(LocalizationService.class);
        when(i18n.text(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        SearchWorkspaceController controller = new SearchWorkspaceController(
                mock(SearchService.class),
                mock(NavigationService.class),
                mock(ApplicationState.class),
                mock(FxmlLoaderFactory.class),
                dialogs,
                mock(SaveSearchUseCase.class),
                mock(LoadSavedSearchesUseCase.class),
                mock(BuildSmartCollectionSearchRequestUseCase.class),
                mock(UiBackgroundExecutor.class),
                mock(BookFilterStateService.class),
                mock(BookFilterDialogService.class),
                i18n,
                mock(BookSelectionService.class),
                mock(NavigationPanelController.class),
                mock(MainLayoutService.class),
                mock(AnnotationManagerService.class),
                mock(ContentSearchService.class),
                mock(WorkspaceManager.class));

        runFxAndWait(() -> {
            try {
                setField(controller, "searchField", new TextField("a".repeat(29) + "😀tail"));
                invoke(controller, "onSaveSearch");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        String displayed = defaultName.getValue();
        assertThat(displayed).isNotNull();
        assertThat(hasUnpairedSurrogate(displayed)).isFalse();
    }


    @Test
    void cancellingContentSearchCancelsSubmittedFuture() throws Exception {
        UiBackgroundExecutor executor = mock(UiBackgroundExecutor.class);
        ApplicationState appState = mock(ApplicationState.class);
        when(appState.getCurrentLibraryCollectionId()).thenReturn("collection-1");
        ContentSearchService contentSearchService = mock(ContentSearchService.class);
        TrackingFuture pending = new TrackingFuture();
        when(executor.submitCollectionScopedCancellable(any(), any())).thenReturn(pending);

        SearchWorkspaceController controller = new SearchWorkspaceController(
                mock(SearchService.class),
                mock(NavigationService.class),
                appState,
                mock(FxmlLoaderFactory.class),
                mock(DialogService.class),
                mock(SaveSearchUseCase.class),
                mock(LoadSavedSearchesUseCase.class),
                mock(BuildSmartCollectionSearchRequestUseCase.class),
                executor,
                mock(BookFilterStateService.class),
                mock(BookFilterDialogService.class),
                mock(LocalizationService.class),
                mock(BookSelectionService.class),
                mock(NavigationPanelController.class),
                mock(MainLayoutService.class),
                mock(AnnotationManagerService.class),
                contentSearchService,
                mock(WorkspaceManager.class));

        invoke(controller, "performContentSearch",
                new Class<?>[]{String.class, SearchWorkspaceController.SearchScope.class},
                "needle", SearchWorkspaceController.SearchScope.CONTENTS);
        invoke(controller, "cancelContentSearch");

        assertThat(pending.cancelled).isTrue();
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = SearchWorkspaceController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name) throws Exception {
        invoke(target, name, new Class<?>[0]);
    }

    private static void invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = SearchWorkspaceController.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static final class TrackingFuture implements Future<Object> {
        private boolean cancelled;

        @Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return cancelled; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
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
