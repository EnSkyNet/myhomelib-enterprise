package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchDialogControllerFxTest {

    @BeforeAll
    static void startJavaFx() throws Exception {
        if (Platform.isFxApplicationThread()) return;
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void resultContextTruncationDoesNotSplitSurrogatePair() throws Exception {
        UiBackgroundExecutor executor = mock(UiBackgroundExecutor.class);
        LocalizationService i18n = mock(LocalizationService.class);
        SearchDialogController controller = new SearchDialogController(executor, i18n);

        runFxAndWait(() -> {
            try {
                TextField searchField = new TextField();
                ListView<com.myhomelibcorp.reader.service.ReaderSearchService.SearchResult> results = new ListView<>();
                setField(controller, "searchField", searchField);
                setField(controller, "searchButton", new Button());
                setField(controller, "statusLabel", new Label());
                setField(controller, "resultsListView", results);
                controller.initialize();

                ListCell<com.myhomelibcorp.reader.service.ReaderSearchService.SearchResult> cell =
                        results.getCellFactory().call(results);
                String context = "a".repeat(79) + "😀" + "tail";
                var item = new com.myhomelibcorp.reader.service.ReaderSearchService.SearchResult(0, 0, 0, context);
                Method updateItem = cell.getClass().getDeclaredMethod(
                        "updateItem", com.myhomelibcorp.reader.service.ReaderSearchService.SearchResult.class, boolean.class);
                updateItem.setAccessible(true);
                updateItem.invoke(cell, item, false);

                String displayed = cell.getText();
                assertThat(displayed).isNotNull();
                assertThat(Character.isHighSurrogate(displayed.charAt(displayed.length() - 4))).isFalse();
                assertThat(hasUnpairedSurrogate(displayed)).isFalse();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void blankSearchInvalidatesAndCancelsInFlightSearch() throws Exception {
        UiBackgroundExecutor executor = mock(UiBackgroundExecutor.class);
        LocalizationService i18n = mock(LocalizationService.class);
        when(i18n.text("ui.reader.search.enter_text")).thenReturn("enter");
        SearchDialogController controller = new SearchDialogController(executor, i18n);

        TrackingFuture inFlight = new TrackingFuture();
        setField(controller, "currentDocument", mock(ReaderDocument.class));
        setField(controller, "searchTask", inFlight);

        runFxAndWait(() -> {
            try {
                TextField searchField = new TextField("   ");
                ListView<?> results = new ListView<>();
                results.getItems().add(null);
                setField(controller, "searchField", searchField);
                setField(controller, "statusLabel", new Label());
                setField(controller, "resultsListView", results);

                long before = generation(controller);
                invoke(controller, "performSearch");

                assertThat(generation(controller)).isGreaterThan(before);
                assertThat(inFlight.cancelled.get()).isTrue();
                assertThat(results.getItems()).isEmpty();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
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

    private static long generation(SearchDialogController controller) throws Exception {
        Field field = SearchDialogController.class.getDeclaredField("searchGeneration");
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicLong) field.get(controller)).get();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = SearchDialogController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = SearchDialogController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
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

    private static final class TrackingFuture implements Future<Object> {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override public boolean cancel(boolean mayInterruptIfRunning) { return cancelled.compareAndSet(false, true); }
        @Override public boolean isCancelled() { return cancelled.get(); }
        @Override public boolean isDone() { return cancelled.get(); }
        @Override public Object get() { throw new UnsupportedOperationException(); }
        @Override public Object get(long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
    }
}
