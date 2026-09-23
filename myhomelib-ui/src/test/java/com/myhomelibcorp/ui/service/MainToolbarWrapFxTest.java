package com.myhomelibcorp.ui.service;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        Platform.setImplicitExit(false);
    }

    @Test
    void toolbarStaysSingleRowAndSearchCanShrinkAtMinimumSupportedWindowWidth() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            Stage stage = null;
            try {
                for (String language : List.of("uk", "en", "bg")) {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/MainView.fxml"));
                    loader.setControllerFactory(type -> mock(type));
                    Parent root = loader.load();
                    applyBundledTranslations(root, language);
                    stage = new Stage();
                    Scene scene = new Scene(root, 800, 700);
                    scene.getStylesheets().add(getClass().getResource("/css/app-theme-base.css").toExternalForm());
                    stage.setScene(scene);
                    stage.setWidth(800);
                    stage.setHeight(700);
                    stage.show();
                    root.applyCss();
                    root.layout();

                    HBox toolbar = (HBox) root.lookup("#mainToolbar");
                    TextField search = (TextField) root.lookup("#searchField");
                    assertThat(toolbar).as("toolbar for %s", language).isNotNull();
                    assertThat(search).as("search for %s", language).isNotNull();
                    assertThat(toolbar.getHeight()).as("toolbar height for %s", language).isLessThan(55.0);
                    assertThat(search.getWidth()).isGreaterThanOrEqualTo(search.getMinWidth());
                    assertThat(search.getWidth()).isLessThanOrEqualTo(search.getMaxWidth());
                    assertThat(toolbar.getChildren()).allSatisfy(node ->
                            assertThat(Math.abs(node.getBoundsInParent().getCenterY() - toolbar.getHeight() / 2.0))
                                    .as("vertical alignment for %s: %s", language, node)
                                    .isLessThan(22.0));
                    stage.close();
                    stage = null;
                }
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

    private static void applyBundledTranslations(Parent root, String language) throws Exception {
        try (var input = MainToolbarWrapFxTest.class.getResourceAsStream("/lang/default/" + language + ".json")) {
            assertThat(input).isNotNull();
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            @SuppressWarnings("unchecked")
            Map<String, String> translations = new com.fasterxml.jackson.databind.ObjectMapper()
                    .convertValue(json.get("translations"), Map.class);
            translateNode(root, translations);
        }
    }

    private static void translateNode(javafx.scene.Node node, Map<String, String> translations) {
        if (node instanceof Labeled labeled) {
            labeled.setText(translations.getOrDefault(labeled.getText(), labeled.getText()));
        }
        if (node instanceof TextInputControl input) {
            input.setPromptText(translations.getOrDefault(input.getPromptText(), input.getPromptText()));
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> translateNode(child, translations));
        }
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
