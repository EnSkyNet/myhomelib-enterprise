package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression coverage for JavaFX controls whose logical content is not always a normal Parent child. */
class LocalizationServiceFxTraversalTest {

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
    void translatesControlOwnedContentPromptTextsPlaceholdersMenusAndGraphics() throws Exception {
        LocalizationService i18n = localization(Map.ofEntries(
                Map.entry("ui.test.section", "Розділ"),
                Map.entry("ui.test.inside", "Усередині"),
                Map.entry("ui.test.prompt", "Підказка"),
                Map.entry("ui.test.toolbar", "Панель"),
                Map.entry("ui.test.split", "Поділ"),
                Map.entry("ui.test.placeholder", "Порожньо"),
                Map.entry("ui.test.column", "Колонка"),
                Map.entry("ui.test.tab", "Вкладка"),
                Map.entry("ui.test.menu", "Меню"),
                Map.entry("ui.test.item", "Пункт"),
                Map.entry("ui.test.tooltip", "Підказка кнопки"),
                Map.entry("ui.test.accessible", "Доступна назва"),
                Map.entry("ui.test.graphic", "Графіка"),
                Map.entry("ui.test.dialog.header", "Заголовок"),
                Map.entry("ui.test.dialog.content", "Вміст")
        ));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Label inside = new Label("ui.test.inside");
                TitledPane titled = new TitledPane("ui.test.section", new VBox(inside));
                ScrollPane scroll = new ScrollPane(new VBox(titled));

                ComboBox<String> combo = new ComboBox<>();
                combo.setPromptText("ui.test.prompt");
                DatePicker date = new DatePicker();
                date.setPromptText("ui.test.prompt");

                Button toolButton = new Button("ui.test.toolbar");
                ToolBar toolBar = new ToolBar(toolButton);
                SplitPane split = new SplitPane(new Label("ui.test.split"));

                ListView<String> list = new ListView<>();
                list.setPlaceholder(new Label("ui.test.placeholder"));
                TableView<String> table = new TableView<>();
                table.setPlaceholder(new Label("ui.test.placeholder"));
                TableColumn<String, String> column = new TableColumn<>("ui.test.column");
                table.getColumns().add(column);
                TreeTableView<String> treeTable = new TreeTableView<>();
                treeTable.setPlaceholder(new Label("ui.test.placeholder"));
                TreeTableColumn<String, String> treeColumn = new TreeTableColumn<>("ui.test.column");
                treeTable.getColumns().add(treeColumn);

                Tab tab = new Tab("ui.test.tab", new Label("ui.test.inside"));
                tab.setContextMenu(new ContextMenu(new MenuItem("ui.test.item")));
                TabPane tabs = new TabPane(tab);

                Menu menu = new Menu("ui.test.menu");
                menu.getItems().add(new MenuItem("ui.test.item"));
                MenuBar menuBar = new MenuBar(menu);
                MenuButton menuButton = new MenuButton("ui.test.menu", null, new MenuItem("ui.test.item"));

                Button decorated = new Button("ui.test.inside", new Text("ui.test.graphic"));
                decorated.setAccessibleText("ui.test.accessible");
                decorated.setTooltip(new Tooltip("ui.test.tooltip"));
                decorated.setContextMenu(new ContextMenu(new MenuItem("ui.test.item")));

                DialogPane dialogPane = new DialogPane();
                dialogPane.setHeaderText("ui.test.dialog.header");
                dialogPane.setContentText("ui.test.dialog.content");
                dialogPane.setExpandableContent(new Label("ui.test.inside"));

                VBox root = new VBox(scroll, combo, date, toolBar, split, list, table, treeTable,
                        tabs, menuBar, menuButton, decorated, dialogPane);
                i18n.apply(root);

                assertThat(titled.getText()).isEqualTo("Розділ");
                assertThat(inside.getText()).isEqualTo("Усередині");
                assertThat(combo.getPromptText()).isEqualTo("Підказка");
                assertThat(date.getPromptText()).isEqualTo("Підказка");
                assertThat(toolButton.getText()).isEqualTo("Панель");
                assertThat(((Label) split.getItems().getFirst()).getText()).isEqualTo("Поділ");
                assertThat(((Label) list.getPlaceholder()).getText()).isEqualTo("Порожньо");
                assertThat(((Label) table.getPlaceholder()).getText()).isEqualTo("Порожньо");
                assertThat(column.getText()).isEqualTo("Колонка");
                assertThat(((Label) treeTable.getPlaceholder()).getText()).isEqualTo("Порожньо");
                assertThat(treeColumn.getText()).isEqualTo("Колонка");
                assertThat(tab.getText()).isEqualTo("Вкладка");
                assertThat(((Label) tab.getContent()).getText()).isEqualTo("Усередині");
                assertThat(tab.getContextMenu().getItems().getFirst().getText()).isEqualTo("Пункт");
                assertThat(menu.getText()).isEqualTo("Меню");
                assertThat(menu.getItems().getFirst().getText()).isEqualTo("Пункт");
                assertThat(menuButton.getItems().getFirst().getText()).isEqualTo("Пункт");
                assertThat(decorated.getTooltip().getText()).isEqualTo("Підказка кнопки");
                assertThat(decorated.getAccessibleText()).isEqualTo("Доступна назва");
                assertThat(((Text) decorated.getGraphic()).getText()).isEqualTo("Графіка");
                assertThat(decorated.getContextMenu().getItems().getFirst().getText()).isEqualTo("Пункт");
                assertThat(dialogPane.getHeaderText()).isEqualTo("Заголовок");
                assertThat(dialogPane.getContentText()).isEqualTo("Вміст");
                assertThat(((Label) dialogPane.getExpandableContent()).getText()).isEqualTo("Усередині");
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static LocalizationService localization(Map<String, String> translations) {
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        LanguageCatalogService catalogs = mock(LanguageCatalogService.class);
        when(catalogs.hasLanguage(anyString())).thenReturn(true);
        when(catalogs.fallbackLanguage()).thenReturn("uk");
        when(catalogs.translations(anyString())).thenReturn(Optional.of(translations));
        when(settings.get("ui.language", "uk")).thenReturn("uk");
        return new LocalizationService(settings, catalogs);
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
