package com.myhomelibcorp.ui.service;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.FlowPane;
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
import static org.mockito.Mockito.mock;

class MainToolbarWrapFxTest {

    @BeforeAll
    static void startFx() throws Exception {
        assumeDisplayReachable();
        if (Platform.isFxApplicationThread()) return;
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        } catch (UnsupportedOperationException noDisplay) {
            Assumptions.abort("JavaFX runtime is not reachable: " + noDisplay.getMessage());
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void toolbarWrapsIntoTwoRowsAtMinimumSupportedWindowWidth() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            Stage stage = null;
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/MainView.fxml"));
                loader.setControllerFactory(type -> mock(type));
                Parent root = loader.load();
                stage = new Stage();
                Scene scene = new Scene(root, 800, 700);
                scene.getStylesheets().add(getClass().getResource("/css/app-theme-base.css").toExternalForm());
                stage.setScene(scene);
                stage.setWidth(800);
                stage.setHeight(700);
                stage.show();
                root.applyCss();
                root.layout();

                FlowPane toolbar = (FlowPane) root.lookup("#mainToolbar");
                assertThat(toolbar).isNotNull();
                java.util.List<Double> rowY = new java.util.ArrayList<>();
                toolbar.getChildren().stream()
                        .filter(node -> node.isManaged() && node.isVisible() && !(node instanceof javafx.scene.control.Separator))
                        .map(node -> node.getBoundsInParent().getMinY())
                        .sorted()
                        .forEach(y -> {
                            if (rowY.isEmpty() || Math.abs(y - rowY.get(rowY.size() - 1)) > 8.0) rowY.add(y);
                        });

                assertThat(rowY)
                        .as("toolbar rows at 800px minimum supported window width")
                        .hasSize(2);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                if (stage != null) stage.close();
                done.countDown();
            }
        });
        assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) throw new AssertionError(failure.get());
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
