package com.myhomelibcorp.ui.service;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
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

/** Runtime CSS regression for the dark-theme contrast issues reported in list/tree selection and selection actions. */
class DarkThemeSelectionFxTest {

    @BeforeAll
    static void startFx() throws Exception {
        assumeDisplayReachable();
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
    void darkThemeKeepsSelectedAuthorAndSelectedBooksToolbarReadable() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            Stage stage = null;
            try {
                ListView<String> authors = new ListView<>();
                authors.getItems().addAll("Автор 1", "Автор 2");
                authors.getSelectionModel().select(0);

                TreeItem<String> rootItem = new TreeItem<>("Автори");
                rootItem.getChildren().add(new TreeItem<>("Вибраний автор"));
                rootItem.setExpanded(true);
                TreeView<String> authorTree = new TreeView<>(rootItem);
                authorTree.getSelectionModel().select(1);

                Label selectedCount = new Label("Вибрано книг: 2");
                FlowPane selectionToolbar = new FlowPane(selectedCount);
                selectionToolbar.getStyleClass().add("selection-toolbar");

                VBox root = new VBox(authors, authorTree, selectionToolbar);
                root.setStyle("""
                        -mhl-background: #202124;
                        -mhl-panel: #2b2d31;
                        -mhl-text: #e8eaed;
                        -mhl-muted-text: #aeb4bc;
                        -mhl-accent: #8ab4f8;
                        -mhl-border: #4a4d52;
                        -mhl-book-row: #25272b;
                        -mhl-on-accent: #111111;
                        """);
                stage = new Stage();
                Scene scene = new Scene(root, 560, 420);
                scene.getStylesheets().add(getClass().getResource("/css/app-theme-base.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                root.applyCss();
                root.layout();

                ListCell<?> selectedListCell = root.lookupAll(".list-cell").stream()
                        .filter(node -> node instanceof ListCell<?> cell && cell.isSelected() && !cell.isEmpty())
                        .map(node -> (ListCell<?>) node)
                        .findFirst().orElseThrow();
                TreeCell<?> selectedTreeCell = root.lookupAll(".tree-cell").stream()
                        .filter(node -> node instanceof TreeCell<?> cell && cell.isSelected() && !cell.isEmpty())
                        .map(node -> (TreeCell<?>) node)
                        .findFirst().orElseThrow();

                assertReadableSelection(selectedListCell, selectedListCell.getTextFill());
                assertReadableSelection(selectedTreeCell, selectedTreeCell.getTextFill());
                Color toolbarBackground = firstBackground(selectionToolbar);
                Color toolbarText = (Color) selectedCount.getTextFill();
                assertThat(contrastRatio(toolbarBackground, toolbarText))
                        .as("selected-book count contrast")
                        .isGreaterThanOrEqualTo(4.5);
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

    private static void assertReadableSelection(Node node, javafx.scene.paint.Paint textPaint) {
        Color background = firstBackground(node);
        assertThat(textPaint).isInstanceOf(Color.class);
        Color text = (Color) textPaint;
        assertThat(background).as("selected background").isEqualTo(Color.web("#8ab4f8"));
        assertThat(text).as("selected text").isEqualTo(Color.web("#111111"));
        assertThat(contrastRatio(background, text)).isGreaterThanOrEqualTo(4.5);
    }

    private static Color firstBackground(Node node) {
        if (!(node instanceof javafx.scene.layout.Region region) || region.getBackground() == null) {
            throw new AssertionError("Node has no CSS background: " + node);
        }
        return region.getBackground().getFills().stream()
                .map(fill -> fill.getFill())
                .filter(Color.class::isInstance)
                .map(Color.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node has no solid CSS background: " + node));
    }

    private static double contrastRatio(Color a, Color b) {
        double lighter = Math.max(luminance(a), luminance(b));
        double darker = Math.min(luminance(a), luminance(b));
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

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
