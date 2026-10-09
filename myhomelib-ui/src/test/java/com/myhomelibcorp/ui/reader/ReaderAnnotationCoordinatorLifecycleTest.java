package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReaderAnnotationCoordinatorLifecycleTest {

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
    void queuedFocusDoesNotRunAfterCompletionLosesWorkspaceOwnership() throws Exception {
        ReaderAnnotationCoordinator coordinator = new ReaderAnnotationCoordinator(
                mock(com.myhomelibcorp.application.annotation.AnnotationService.class),
                mock(com.myhomelibcorp.ui.annotation.AnnotationEditorDialog.class),
                mock(ReaderAnnotationPopover.class),
                mock(com.myhomelibcorp.ui.service.UiBackgroundExecutor.class),
                mock(com.myhomelibcorp.ui.service.DialogService.class),
                mock(com.myhomelibcorp.ui.service.LocalizationService.class),
                new ApplicationState());

        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger focusCalls = new AtomicInteger();
        ReaderAnnotationCoordinator.Context context = new ReaderAnnotationCoordinator.Context(
                null, "collection", "book", "artifact", null, Set.of(), current::get,
                () -> { },
                () -> current.set(false),
                ignored -> { },
                () -> { },
                focusCalls::incrementAndGet);

        Method complete = ReaderAnnotationCoordinator.class.getDeclaredMethod(
                "complete", ReaderAnnotationCoordinator.Context.class, boolean.class, String.class);
        complete.setAccessible(true);
        complete.invoke(coordinator, context, false, "status.key");

        runFxAndWait(() -> { });
        runFxAndWait(() -> { });

        assertThat(current).isFalse();
        assertThat(focusCalls).hasValue(0);
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
