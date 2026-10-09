package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.ReaderAnnotationOverlay;
import com.myhomelibcorp.reader.api.ReaderAnnotationState;
import com.myhomelibcorp.reader.api.ReaderAnnotationType;
import javafx.application.Platform;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.util.Callback;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class ReaderAnnotationSidebarUnicodeTest {

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
    void annotationSidebarPreviewDoesNotSplitSurrogatePair() throws Exception {
        AtomicReference<String> rendered = new AtomicReference<>();
        runFxAndWait(() -> {
            try {
                NewReaderWorkspaceController controller = mock(NewReaderWorkspaceController.class, CALLS_REAL_METHODS);
                ListView<Object> list = new ListView<>();
                setField(controller, "annotationSidebarList", list);
                invokeNoArgs(controller, "configureReaderSidebar");

                String body = "a".repeat(146) + "😀tail";
                ReaderAnnotationOverlay overlay = new ReaderAnnotationOverlay(
                        "annotation", 0, 1, "#FFF59D", ReaderAnnotationType.NOTE,
                        body, "quote", Set.of(), "Chapter", ReaderAnnotationState.RESOLVED, Instant.EPOCH);
                Object row = annotationSidebarRow("Chapter", overlay, false);

                @SuppressWarnings("rawtypes")
                Callback factory = list.getCellFactory();
                ListCell<?> cell = (ListCell<?>) factory.call(list);
                invokeUpdateItem(cell, row, false);
                rendered.set(cell.getText());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(rendered.get()).isNotNull();
        assertThat(hasUnpairedSurrogate(rendered.get())).isFalse();
    }

    private static Object annotationSidebarRow(String chapter, ReaderAnnotationOverlay overlay, boolean header) throws Exception {
        Class<?> rowClass = Class.forName("com.myhomelibcorp.ui.reader.NewReaderWorkspaceController$AnnotationSidebarRow");
        Constructor<?> constructor = rowClass.getDeclaredConstructor(String.class, ReaderAnnotationOverlay.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(chapter, overlay, header);
    }

    private static void invokeUpdateItem(ListCell<?> cell, Object row, boolean empty) throws Exception {
        Method candidate = null;
        for (Method method : cell.getClass().getDeclaredMethods()) {
            if (!method.getName().equals("updateItem") || method.getParameterCount() != 2) continue;
            Class<?> first = method.getParameterTypes()[0];
            if (first == Object.class || first.isInstance(row)) {
                candidate = method;
                if (first != Object.class) break;
            }
        }
        if (candidate == null) throw new NoSuchMethodException("updateItem");
        candidate.setAccessible(true);
        candidate.invoke(cell, row, empty);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = NewReaderWorkspaceController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invokeNoArgs(Object target, String name) throws Exception {
        Method method = NewReaderWorkspaceController.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
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
