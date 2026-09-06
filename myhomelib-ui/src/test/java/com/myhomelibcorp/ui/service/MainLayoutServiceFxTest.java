package com.myhomelibcorp.ui.service;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Runtime regression: a sidebar hidden from Reader must be restorable. */
class MainLayoutServiceFxTest {

    @BeforeAll
    static void startToolkit() throws Exception {
        assumeDisplayReachable();
        try {
            CountDownLatch started = new CountDownLatch(1);
            Platform.startup(started::countDown);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Platform.setImplicitExit(false);
        } catch (IllegalStateException ignored) {
            // Toolkit already belongs to another test in this Surefire JVM.
            Platform.setImplicitExit(false);
        } catch (UnsupportedOperationException noDisplay) {
            Assumptions.abort("JavaFX DISPLAY is not reachable: " + noDisplay.getMessage());
        }
    }

    @Test
    void rightSidebarCanBeHiddenAndRestoredWithActualNodes() throws Exception {
        AtomicReference<MainLayoutService> serviceRef = new AtomicReference<>();
        AtomicReference<StackPane> rightRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        runFx(() -> {
            MainLayoutService service = new MainLayoutService();
            StackPane left = new StackPane();
            left.setPrefWidth(220);
            StackPane right = new StackPane();
            right.setPrefWidth(320);
            BorderPane root = new BorderPane(new StackPane(), null, right, null, left);
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1200, 700));
            stage.show();
            root.applyCss();
            root.layout();
            service.registerSidebars(left, right);
            serviceRef.set(service);
            rightRef.set(right);
            stageRef.set(stage);
        });

        MainLayoutService service = serviceRef.get();
        StackPane right = rightRef.get();
        assertThat(fx(() -> right.isVisible())).isTrue();
        assertThat(fx(() -> right.isManaged())).isTrue();

        runFx(service::toggleRightSidebar);
        drainFx();
        assertThat(fx(() -> right.isVisible())).isFalse();
        assertThat(fx(() -> right.isManaged())).isFalse();

        runFx(service::toggleRightSidebar);
        drainFx();
        assertThat(fx(() -> right.isVisible())).isTrue();
        assertThat(fx(() -> right.isManaged())).isTrue();

        runFx(stageRef.get()::close);
    }

    @Test
    void restoringRightSidebarKeepsAllBorderPaneRegionsInsideSceneWidth() throws Exception {
        AtomicReference<MainLayoutService> serviceRef = new AtomicReference<>();
        AtomicReference<BorderPane> rootRef = new AtomicReference<>();
        AtomicReference<StackPane> centerRef = new AtomicReference<>();
        AtomicReference<StackPane> rightRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        runFx(() -> {
            MainLayoutService service = new MainLayoutService();
            StackPane left = new StackPane();
            left.setPrefWidth(250);
            left.setMinWidth(250);
            StackPane right = new StackPane();
            right.setPrefWidth(320);
            right.setMinWidth(220);
            right.setMaxWidth(350);

            StackPane center = new StackPane();
            // Simulate a wide active workspace (tables/search controls). The center
            // container must be allowed to shrink when a sidebar is restored.
            StackPane wideWorkspace = new StackPane();
            wideWorkspace.setMinWidth(650);
            center.getChildren().add(wideWorkspace);

            BorderPane root = new BorderPane(center, null, right, null, left);
            Stage stage = new Stage();
            // MainController registers the sidebars while FXML is loading, before
            // the primary Stage is shown. Mirror that production ordering here.
            service.registerSidebars(left, right);
            stage.setScene(new Scene(root, 1100, 700));
            stage.show();
            root.applyCss();
            root.layout();

            serviceRef.set(service);
            rootRef.set(root);
            centerRef.set(center);
            rightRef.set(right);
            stageRef.set(stage);
        });

        MainLayoutService service = serviceRef.get();
        double initialRootWidth = fxDouble(() -> rootRef.get().getWidth());
        for (int i = 0; i < 3; i++) {
            runFx(service::toggleRightSidebar);
            drainFx();
            runFx(service::toggleRightSidebar);
            drainFx();
        }

        double rootWidth = fxDouble(() -> rootRef.get().getWidth());
        assertThat(rootWidth).isCloseTo(initialRootWidth, org.assertj.core.data.Offset.offset(0.5));
        double centerLayoutRight = fxDouble(() -> centerRef.get().getLayoutX() + centerRef.get().getWidth());
        double rightLayoutX = fxDouble(() -> rightRef.get().getLayoutX());
        double rightLayoutEdge = fxDouble(() -> rightRef.get().getLayoutX() + rightRef.get().getWidth());

        assertThat(rightLayoutEdge).isLessThanOrEqualTo(rootWidth + 0.5);
        assertThat(centerLayoutRight).isLessThanOrEqualTo(rightLayoutX + 0.5);

        runFx(stageRef.get()::close);
    }

    @Test
    void alternatingLeftAndRightSidebarCyclesNeverGrowPastSceneBounds() throws Exception {
        AtomicReference<MainLayoutService> serviceRef = new AtomicReference<>();
        AtomicReference<BorderPane> rootRef = new AtomicReference<>();
        AtomicReference<StackPane> leftRef = new AtomicReference<>();
        AtomicReference<StackPane> centerRef = new AtomicReference<>();
        AtomicReference<StackPane> rightRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        runFx(() -> {
            MainLayoutService service = new MainLayoutService();
            StackPane left = new StackPane();
            left.setPrefWidth(250);
            left.setMinWidth(220);
            StackPane right = new StackPane();
            right.setPrefWidth(320);
            right.setMinWidth(220);
            StackPane center = new StackPane();
            StackPane wideWorkspace = new StackPane();
            wideWorkspace.setMinWidth(760);
            center.getChildren().add(wideWorkspace);

            BorderPane root = new BorderPane(center, null, right, null, left);
            service.registerSidebars(left, right);
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1100, 700));
            stage.show();
            root.applyCss();
            root.layout();

            serviceRef.set(service);
            rootRef.set(root);
            leftRef.set(left);
            centerRef.set(center);
            rightRef.set(right);
            stageRef.set(stage);
        });

        MainLayoutService service = serviceRef.get();
        double initialWidth = fxDouble(() -> rootRef.get().getWidth());
        for (int i = 0; i < 3; i++) {
            runFx(service::toggleLeftSidebar);
            drainFx();
            runFx(service::toggleLeftSidebar);
            drainFx();
            runFx(service::toggleRightSidebar);
            drainFx();
            runFx(service::toggleRightSidebar);
            drainFx();
            runFx(() -> {
                service.setLeftSidebarVisible(false);
                service.setRightSidebarVisible(false);
            });
            drainFx();
            runFx(() -> {
                service.setLeftSidebarVisible(true);
                service.setRightSidebarVisible(true);
            });
            drainFx();
        }

        double rootWidth = fxDouble(() -> rootRef.get().getWidth());
        double leftEdge = fxDouble(() -> leftRef.get().getLayoutX());
        double leftRight = fxDouble(() -> leftRef.get().getLayoutX() + leftRef.get().getWidth());
        double centerLeft = fxDouble(() -> centerRef.get().getLayoutX());
        double centerRight = fxDouble(() -> centerRef.get().getLayoutX() + centerRef.get().getWidth());
        double rightLeft = fxDouble(() -> rightRef.get().getLayoutX());
        double rightEdge = fxDouble(() -> rightRef.get().getLayoutX() + rightRef.get().getWidth());

        assertThat(rootWidth).isCloseTo(initialWidth, org.assertj.core.data.Offset.offset(0.5));
        assertThat(leftEdge).isGreaterThanOrEqualTo(-0.5);
        assertThat(leftRight).isLessThanOrEqualTo(centerLeft + 0.5);
        assertThat(centerRight).isLessThanOrEqualTo(rightLeft + 0.5);
        assertThat(rightEdge).isLessThanOrEqualTo(rootWidth + 0.5);
        assertThat(fx(() -> leftRef.get().isVisible() && leftRef.get().isManaged())).isTrue();
        assertThat(fx(() -> rightRef.get().isVisible() && rightRef.get().isManaged())).isTrue();

        runFx(stageRef.get()::close);
    }

    private static double fxDouble(java.util.concurrent.Callable<Double> supplier) throws Exception {
        AtomicReference<Double> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        runFx(() -> {
            try { result.set(supplier.call()); }
            catch (Throwable t) { error.set(t); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    private static boolean fx(java.util.concurrent.Callable<Boolean> supplier) throws Exception {
        AtomicReference<Boolean> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        runFx(() -> {
            try { result.set(supplier.call()); }
            catch (Throwable t) { error.set(t); }
        });
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    private static void drainFx() throws Exception {
        runFx(() -> { });
        runFx(() -> { });
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
