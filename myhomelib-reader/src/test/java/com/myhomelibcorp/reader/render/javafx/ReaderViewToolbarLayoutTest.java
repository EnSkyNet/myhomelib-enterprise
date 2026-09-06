package com.myhomelibcorp.reader.render.javafx;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Runtime regression for the Reader toolbar geometry reported on Windows. */
class ReaderViewToolbarLayoutTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        assumeDisplayReachable();
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (IllegalStateException alreadyStarted) {
            // Toolkit already belongs to another test in this Surefire JVM.
        } catch (UnsupportedOperationException noDisplay) {
            Assumptions.abort("JavaFX DISPLAY is not reachable: " + noDisplay.getMessage());
        }
    }

    @AfterAll
    static void shutdownToolkit() {
        // Do not call Platform.exit(): other JavaFX tests may share this Surefire JVM.
    }

    @Test
    void hidingAndRestoringToolbarRestoresCanvasGeometry() throws Exception {
        AtomicReference<ReaderView> viewRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        runFx(() -> {
            ReaderView view = new ReaderView();
            Stage stage = new Stage();
            stage.setScene(new Scene(view, 1200, 800));
            stage.show();
            view.applyCss();
            view.layout();
            viewRef.set(view);
            stageRef.set(stage);
        });

        ReaderView view = viewRef.get();
        double shownCanvasHeight = fxValue(() -> view.getCanvas().getHeight());
        double toolbarHeight = fxValue(() -> view.getToolbar().getHeight());
        assertThat(toolbarHeight).isGreaterThan(30.0);

        invokeToggle(view);
        drainFx();
        double hiddenCanvasHeight = fxValue(() -> view.getCanvas().getHeight());
        assertThat(fxValue(() -> view.getToolbar().isVisible() ? 1.0 : 0.0)).isZero();
        assertThat(fxValue(() -> view.getToolbar().isManaged() ? 1.0 : 0.0)).isZero();
        assertThat(hiddenCanvasHeight).isGreaterThan(shownCanvasHeight + 20.0);

        invokeToggle(view);
        drainFx();
        double restoredCanvasHeight = fxValue(() -> view.getCanvas().getHeight());
        assertThat(fxValue(() -> view.getToolbar().isVisible() ? 1.0 : 0.0)).isEqualTo(1.0);
        assertThat(fxValue(() -> view.getToolbar().isManaged() ? 1.0 : 0.0)).isEqualTo(1.0);
        assertThat(restoredCanvasHeight).isCloseTo(shownCanvasHeight, org.assertj.core.data.Offset.offset(1.0));

        runFx(() -> {
            view.dispose();
            stageRef.get().close();
        });
    }

    private static void invokeToggle(ReaderView view) throws Exception {
        Method toggle = ReaderView.class.getDeclaredMethod("toggleToolbarVisibility");
        toggle.setAccessible(true);
        runFx(() -> {
            try { toggle.invoke(view); }
            catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        });
    }

    private static void drainFx() throws Exception {
        // Toggle schedules one runLater callback. Two barriers also cover the layout/render pulse.
        runFx(() -> { });
        runFx(() -> { });
    }

    private static double fxValue(java.util.concurrent.Callable<Double> supplier) throws Exception {
        AtomicReference<Double> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        runFx(() -> {
            try { result.set(supplier.call()); }
            catch (Throwable t) { error.set(t); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    private static void runFx(Runnable action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); }
            catch (Throwable t) { error.set(t); }
            finally { done.countDown(); }
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) throw new AssertionError(error.get());
    }
    /** Avoid poisoning the JavaFX singleton when CI exposes a stale/unreachable DISPLAY. */
    private static void assumeDisplayReachable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) return;

        String display = System.getenv("DISPLAY");
        Assumptions.assumeTrue(display != null && !display.isBlank(),
                "JavaFX runtime test requires DISPLAY on Linux");
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        } catch (Throwable unreachableDisplay) {
            Assumptions.abort("JavaFX DISPLAY is not reachable: " + unreachableDisplay.getMessage());
        }
    }

}
