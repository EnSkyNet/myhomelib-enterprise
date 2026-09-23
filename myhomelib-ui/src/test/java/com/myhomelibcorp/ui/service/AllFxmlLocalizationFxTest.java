package com.myhomelibcorp.ui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Loads every desktop FXML with the real FXMLLoader and proves that localization does not leave
 * stable ui./common. identifiers in any user-visible JavaFX text property.
 */
class AllFxmlLocalizationFxTest {

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
    void everyFxmlResolvesAllVisibleStableLocalizationKeys() throws Exception {
        List<Path> fxmlFiles = fxmlFiles();
        assertThat(fxmlFiles).hasSizeGreaterThanOrEqualTo(29);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                List<String> leaks = new ArrayList<>();
                for (String language : List.of("uk", "en", "bg")) {
                    LocalizationService i18n = localization(bundledTranslations(language));
                    for (Path fxml : fxmlFiles) {
                        URL location = fxml.toUri().toURL();
                        FXMLLoader loader = new FXMLLoader(location);
                        loader.setControllerFactory(type -> mock(type));
                        Object loaded = loader.load();
                        if (!(loaded instanceof Parent root)) {
                            leaks.add(language + "/" + fxml.getFileName() + ": root is not Parent: " + loaded);
                            continue;
                        }
                        i18n.apply(root);
                        collectLeaks(root, language + "/" + fxml.getFileName(), leaks,
                                Collections.newSetFromMap(new IdentityHashMap<>()),
                                Collections.newSetFromMap(new IdentityHashMap<>()));
                    }
                }
                assertThat(leaks)
                        .as("raw stable localization keys left visible after LocalizationService.apply(): %s", leaks)
                        .isEmpty();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static void collectLeaks(Node node, String source, List<String> leaks,
                                     Set<Node> visitedNodes, Set<MenuItem> visitedMenus) {
        if (node == null || !visitedNodes.add(node)) return;

        if (node instanceof Control control) {
            check(source, control.getClass().getSimpleName() + ".accessibleText", control.getAccessibleText(), leaks);
            Tooltip tooltip = control.getTooltip();
            if (tooltip != null) {
                check(source, control.getClass().getSimpleName() + ".tooltip", tooltip.getText(), leaks);
                collectLeaks(tooltip.getGraphic(), source, leaks, visitedNodes, visitedMenus);
            }
            ContextMenu menu = control.getContextMenu();
            if (menu != null) for (MenuItem item : menu.getItems()) collectMenuLeaks(item, source, leaks, visitedNodes, visitedMenus);
        }
        if (node instanceof Labeled labeled) {
            check(source, node.getClass().getSimpleName() + ".text", labeled.getText(), leaks);
            collectLeaks(labeled.getGraphic(), source, leaks, visitedNodes, visitedMenus);
        }
        if (node instanceof TextInputControl input) {
            check(source, node.getClass().getSimpleName() + ".promptText", input.getPromptText(), leaks);
        }
        if (node instanceof ComboBoxBase<?> combo) {
            check(source, node.getClass().getSimpleName() + ".promptText", combo.getPromptText(), leaks);
        }
        if (node instanceof Text text) check(source, "Text.text", text.getText(), leaks);

        if (node instanceof MenuBar menuBar) {
            for (Menu menu : menuBar.getMenus()) collectMenuLeaks(menu, source, leaks, visitedNodes, visitedMenus);
        }
        if (node instanceof MenuButton menuButton) {
            for (MenuItem item : menuButton.getItems()) collectMenuLeaks(item, source, leaks, visitedNodes, visitedMenus);
        }
        if (node instanceof TableView<?> table) {
            for (TableColumn<?, ?> column : table.getColumns()) collectColumnLeaks(column, source, leaks, visitedNodes);
            collectLeaks(table.getPlaceholder(), source, leaks, visitedNodes, visitedMenus);
        }
        if (node instanceof TreeTableView<?> table) {
            for (TreeTableColumn<?, ?> column : table.getColumns()) collectTreeColumnLeaks(column, source, leaks, visitedNodes);
            collectLeaks(table.getPlaceholder(), source, leaks, visitedNodes, visitedMenus);
        }
        if (node instanceof ListView<?> list) collectLeaks(list.getPlaceholder(), source, leaks, visitedNodes, visitedMenus);
        if (node instanceof TabPane pane) {
            for (Tab tab : pane.getTabs()) {
                check(source, "Tab.text", tab.getText(), leaks);
                collectLeaks(tab.getGraphic(), source, leaks, visitedNodes, visitedMenus);
                collectLeaks(tab.getContent(), source, leaks, visitedNodes, visitedMenus);
                if (tab.getContextMenu() != null) {
                    for (MenuItem item : tab.getContextMenu().getItems()) collectMenuLeaks(item, source, leaks, visitedNodes, visitedMenus);
                }
            }
        }
        if (node instanceof ScrollPane pane) collectLeaks(pane.getContent(), source, leaks, visitedNodes, visitedMenus);
        if (node instanceof TitledPane pane) collectLeaks(pane.getContent(), source, leaks, visitedNodes, visitedMenus);
        if (node instanceof SplitPane pane) for (Node item : pane.getItems()) collectLeaks(item, source, leaks, visitedNodes, visitedMenus);
        if (node instanceof ToolBar bar) for (Node item : bar.getItems()) collectLeaks(item, source, leaks, visitedNodes, visitedMenus);
        if (node instanceof Accordion accordion) for (TitledPane pane : accordion.getPanes()) collectLeaks(pane, source, leaks, visitedNodes, visitedMenus);
        if (node instanceof DialogPane pane) {
            check(source, "DialogPane.headerText", pane.getHeaderText(), leaks);
            check(source, "DialogPane.contentText", pane.getContentText(), leaks);
            collectLeaks(pane.getHeader(), source, leaks, visitedNodes, visitedMenus);
            collectLeaks(pane.getContent(), source, leaks, visitedNodes, visitedMenus);
            collectLeaks(pane.getExpandableContent(), source, leaks, visitedNodes, visitedMenus);
            collectLeaks(pane.getGraphic(), source, leaks, visitedNodes, visitedMenus);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collectLeaks(child, source, leaks, visitedNodes, visitedMenus);
        }
    }

    private static void collectMenuLeaks(MenuItem item, String source, List<String> leaks,
                                         Set<Node> visitedNodes, Set<MenuItem> visitedMenus) {
        if (item == null || !visitedMenus.add(item)) return;
        check(source, item.getClass().getSimpleName() + ".text", item.getText(), leaks);
        collectLeaks(item.getGraphic(), source, leaks, visitedNodes, visitedMenus);
        if (item instanceof Menu menu) {
            for (MenuItem child : menu.getItems()) collectMenuLeaks(child, source, leaks, visitedNodes, visitedMenus);
        }
    }

    private static void collectColumnLeaks(TableColumn<?, ?> column, String source, List<String> leaks, Set<Node> visitedNodes) {
        check(source, "TableColumn.text", column.getText(), leaks);
        Node graphic = column.getGraphic();
        if (graphic != null && containsStableKey(graphic instanceof Text t ? t.getText() : null)) {
            leaks.add(source + ": TableColumn.graphic=" + ((Text) graphic).getText());
        }
        for (TableColumn<?, ?> child : column.getColumns()) collectColumnLeaks(child, source, leaks, visitedNodes);
    }

    private static void collectTreeColumnLeaks(TreeTableColumn<?, ?> column, String source, List<String> leaks, Set<Node> visitedNodes) {
        check(source, "TreeTableColumn.text", column.getText(), leaks);
        Node graphic = column.getGraphic();
        if (graphic instanceof Text t) check(source, "TreeTableColumn.graphic", t.getText(), leaks);
        for (TreeTableColumn<?, ?> child : column.getColumns()) collectTreeColumnLeaks(child, source, leaks, visitedNodes);
    }

    private static void check(String source, String property, String value, List<String> leaks) {
        if (containsStableKey(value)) leaks.add(source + ": " + property + "=" + value);
    }

    private static boolean containsStableKey(String value) {
        return value != null && (value.startsWith("ui.") || value.startsWith("common."));
    }

    private static List<Path> fxmlFiles() throws Exception {
        URL view = AllFxmlLocalizationFxTest.class.getResource("/view");
        assertThat(view).isNotNull();
        Path dir = Paths.get(view.toURI());
        try (var stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".fxml"))
                    .sorted()
                    .toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> bundledTranslations(String language) throws Exception {
        try (var input = AllFxmlLocalizationFxTest.class.getResourceAsStream("/lang/default/" + language + ".json")) {
            assertThat(input).isNotNull();
            ObjectMapper mapper = new ObjectMapper();
            return mapper.convertValue(mapper.readTree(input).get("translations"), Map.class);
        }
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
        Assumptions.assumeTrue(display != null && !display.isBlank(), "JavaFX runtime test requires DISPLAY on Linux");
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        } catch (Throwable unreachableDisplay) {
            Assumptions.abort("JavaFX DISPLAY is not reachable: " + unreachableDisplay.getMessage());
        }
    }
}
