package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.render.javafx.ReaderView;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class NewReaderSidebarSearchFxTest {

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
    void sidebarResultTruncationDoesNotSplitSurrogatePair() throws Exception {
        NewReaderWorkspaceController controller = newController();
        runFxAndWait(() -> {
            try {
                ListView<com.myhomelibcorp.reader.service.ReaderSearchService.SearchResult> results = new ListView<>();
                setField(controller, "readerSidebarSearchResults", results);
                invoke(controller, "configureReaderSidebar");

                ListCell<com.myhomelibcorp.reader.service.ReaderSearchService.SearchResult> cell =
                        results.getCellFactory().call(results);
                String context = "a".repeat(156) + "😀tail";
                var item = new com.myhomelibcorp.reader.service.ReaderSearchService.SearchResult(0, 0, 0, context);
                var method = cell.getClass().getDeclaredMethod(
                        "updateItem", com.myhomelibcorp.reader.service.ReaderSearchService.SearchResult.class, boolean.class);
                method.setAccessible(true);
                method.invoke(cell, item, false);

                assertThat(hasUnpairedSurrogate(cell.getText())).isFalse();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void blankSidebarSearchCancelsSubmittedSearchAndClearsResults() throws Exception {
        NewReaderWorkspaceController controller = newController();
        ReaderView readerView = mock(ReaderView.class);
        ReaderEngine engine = mock(ReaderEngine.class);
        when(readerView.isBookOpen()).thenReturn(true);
        when(readerView.getEngine()).thenReturn(engine);
        when(engine.getCurrentDocument()).thenReturn(mock(ReaderDocument.class));
        TrackingFuture inFlight = new TrackingFuture();
        UiBackgroundExecutor executor = field(controller, "uiBackgroundExecutor", UiBackgroundExecutor.class);
        doReturn(inFlight).when(executor).submitCancellable(Mockito.any());

        setField(controller, "readerView", readerView);

        runFxAndWait(() -> {
            try {
                TextField query = new TextField("needle");
                Label status = new Label();
                ListView<com.myhomelibcorp.reader.service.ReaderSearchService.SearchResult> results = new ListView<>();
                setField(controller, "readerSidebarSearchField", query);
                setField(controller, "readerSidebarSearchStatus", status);
                setField(controller, "readerSidebarSearchResults", results);

                controller.performSidebarSearch();
                results.getItems().add(new com.myhomelibcorp.reader.service.ReaderSearchService.SearchResult(0, 0, 0, "old"));
                query.setText("  ");
                controller.performSidebarSearch();

                assertThat(inFlight.cancelled.get()).isTrue();
                assertThat(results.getItems()).isEmpty();
                assertThat(status.getText()).isEqualTo("enter");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void rejectedSidebarSearchShowsBusyStatus() throws Exception {
        NewReaderWorkspaceController controller = newController();
        ReaderView readerView = mock(ReaderView.class);
        ReaderEngine engine = mock(ReaderEngine.class);
        when(readerView.isBookOpen()).thenReturn(true);
        when(readerView.getEngine()).thenReturn(engine);
        when(engine.getCurrentDocument()).thenReturn(mock(ReaderDocument.class));
        UiBackgroundExecutor executor = field(controller, "uiBackgroundExecutor", UiBackgroundExecutor.class);
        doThrow(new RejectedExecutionException("full")).when(executor).submitCancellable(Mockito.any());
        setField(controller, "readerView", readerView);

        runFxAndWait(() -> {
            try {
                Label status = new Label();
                setField(controller, "readerSidebarSearchField", new TextField("needle"));
                setField(controller, "readerSidebarSearchStatus", status);
                setField(controller, "readerSidebarSearchResults", new ListView<>());

                controller.performSidebarSearch();

                assertThat(status.getText()).isEqualTo("busy");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static NewReaderWorkspaceController newController() throws Exception {
        Constructor<?> constructor = NewReaderWorkspaceController.class.getDeclaredConstructors()[0];
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            args[i] = Mockito.mock(parameterTypes[i]);
            if (parameterTypes[i] == LocalizationService.class) {
                LocalizationService i18n = (LocalizationService) args[i];
                when(i18n.text("ui.reader.search.enter_text")).thenReturn("enter");
                when(i18n.text("ui.reader.search.queue_busy")).thenReturn("busy");
            }
        }
        constructor.setAccessible(true);
        return (NewReaderWorkspaceController) constructor.newInstance(args);
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

    private static void invoke(Object target, String name) throws Exception {
        var method = NewReaderWorkspaceController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = NewReaderWorkspaceController.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = NewReaderWorkspaceController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void runFxAndWait(Runnable action) throws Exception {
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

    private static final class TrackingFuture implements Future<Object> {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return cancelled.compareAndSet(false, true); }
        @Override public boolean isCancelled() { return cancelled.get(); }
        @Override public boolean isDone() { return cancelled.get(); }
        @Override public Object get() { throw new UnsupportedOperationException(); }
        @Override public Object get(long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
    }
}
