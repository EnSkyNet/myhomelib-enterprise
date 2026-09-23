package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.util.AppPaths;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.text.Text;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** UI localization backed entirely by standalone language catalog files. */
@Component
@RequiredArgsConstructor
public class LocalizationService {
    private final ApplicationSettingsPort settings;
    private final LanguageCatalogService languageCatalogs;
    private final Path languageFile = AppPaths.configDir().resolve("language.txt");
    private volatile String cachedLanguage;

    public String language() {
        String cached = cachedLanguage;
        if (cached != null && languageCatalogs.hasLanguage(cached)) {
            applyJvmLocale(cached);
            return cached;
        }

        String value = readLanguageFile();
        if (value == null) value = normalizeLanguage(settings.get("ui.language", languageCatalogs.fallbackLanguage()));
        if (!languageCatalogs.hasLanguage(value)) value = languageCatalogs.fallbackLanguage();

        cachedLanguage = value;
        applyJvmLocale(value);
        // Create/synchronize the plain-text selection file on the first run as well.
        writeLanguageFile(value);
        return value;
    }

    public void setLanguage(String language) {
        String value = normalizeLanguage(language);
        if (!languageCatalogs.hasLanguage(value)) value = languageCatalogs.fallbackLanguage();
        cachedLanguage = value;
        applyJvmLocale(value);
        settings.put("ui.language", value); // backward-compatible settings fallback
        writeLanguageFile(value);
    }

    /**
     * Re-scan Lang/*.json and return the languages currently available.
     * The generated config/available-languages.txt file is synchronized by the scan.
     */
    public Map<String, String> availableLanguages() {
        languageCatalogs.refresh();
        return languageCatalogs.availableLanguages();
    }

    /**
     * Resolves a stable UI localization key. Stable keys are the preferred API for
     * programmatic JavaFX text; {@link #tr(String)} remains for legacy/FXML source-text
     * translation during the incremental migration.
     */
    public String text(String key) {
        if (key == null) return null;
        String resolved = languageCatalogs.translations(language())
                .map(map -> map.get(key))
                .filter(value -> value != null && !value.isBlank())
                .orElse(null);
        if (resolved != null) return resolved;

        // Custom/outdated language files may lag behind a new UI key. Fall back to the
        // shipped base catalogue instead of leaking identifiers such as ui.reader.* into
        // the visible interface.
        resolved = languageCatalogs.translations(languageCatalogs.fallbackLanguage())
                .map(map -> map.get(key))
                .filter(value -> value != null && !value.isBlank())
                .orElse(null);
        if (resolved != null) return resolved;

        if (isStableKey(key)) {
            return languageCatalogs.translations(languageCatalogs.fallbackLanguage())
                    .map(map -> map.getOrDefault("common.translation.missing", ""))
                    .orElse("");
        }
        return key;
    }

    /**
     * Formats a stable localization key using Locale.ROOT.
     *
     * <p>The language catalogs contain both legacy printf placeholders ({@code %s}/{@code %d})
     * and MessageFormat placeholders ({@code {0}}, {@code {1}}). Supporting both here keeps the
     * catalog backward-compatible and prevents raw placeholders such as UiText.text("ui.main.scope.collection") from
     * leaking into the UI.</p>
     */
    public String format(String key, Object... args) {
        return formatPattern(text(key), args);
    }

    static String formatPattern(String pattern, Object... args) {
        if (pattern == null) return null;
        Object[] values = args == null ? new Object[0] : args;
        if (pattern.matches("(?s).*\\{\\d+(?:,[^}]*)?}.*")) {
            return new java.text.MessageFormat(pattern, java.util.Locale.ROOT).format(values);
        }
        return String.format(java.util.Locale.ROOT, pattern, values);
    }

    public String tr(String text) {
        if (text == null) return null;
        if (isStableKey(text)) return text(text);
        String translated = languageCatalogs.translations(language())
                .map(map -> map.get(text))
                .filter(value -> value != null && !value.isBlank())
                .orElse(null);
        if (translated != null) return translated;
        return languageCatalogs.translations(languageCatalogs.fallbackLanguage())
                .map(map -> map.getOrDefault(text, text))
                .orElse(text);
    }

    private static boolean isStableKey(String value) {
        return value.startsWith("ui.") || value.startsWith("common.");
    }

    /** Localized display label for a stable FB2 genre code. */
    public String genreName(String genreCode, String fallback) {
        return languageCatalogs.genreName(language(), genreCode, fallback);
    }

    /**
     * Hides a base/numeric fallback only when the same set already contains a more
     * specific extended genre from the same semantic parent group.
     */
    public boolean shouldDisplayGenre(String genreCode, java.util.Collection<String> siblingCodes) {
        return languageCatalogs.shouldDisplayGenre(language(), genreCode, siblingCodes);
    }

    /** User-facing diagnostics generated while scanning Lang/*.json. */
    public java.util.List<String> languageDiagnostics() {
        languageCatalogs.refresh();
        return languageCatalogs.diagnostics();
    }

    public Path languageDiagnosticsFile() {
        return languageCatalogs.diagnosticsFile();
    }

    private String readLanguageFile() {
        try {
            if (!Files.isRegularFile(languageFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
            Path safe = SafeFilePublication.requireSafeRegularFile(languageFile, "Selected language file");
            String hash = SafeFilePublication.sha256(safe, "Selected language file snapshot");
            byte[] bytes = SafeFilePublication.readOwnedBytes(safe, hash, 64 * 1024, "Selected language file");
            return new String(bytes, StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .findFirst()
                    .map(LocalizationService::normalizeLanguage)
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeLanguageFile(String value) {
        try {
            SafeFilePublication.createDirectoriesNoLinks(languageFile.getParent(), "Selected language directory");
            AtomicFileSupport.writeStringReplacing(languageFile,
                    "# MyHomeLib selected UI language. UTF-8.\n"
                            + "# Available languages are generated in available-languages.txt.\n"
                            + value + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // ApplicationSettingsPort remains the fallback if the file is read-only.
        }
    }

    private static void applyJvmLocale(String language) {
        Locale locale = Locale.forLanguageTag(normalizeLanguage(language));
        if (!locale.getLanguage().isBlank() && !Locale.getDefault().equals(locale)) {
            Locale.setDefault(locale);
        }
    }

    private static String normalizeLanguage(String language) {
        String normalized = LanguageCatalogService.normalizeCode(language);
        return normalized.isBlank() ? "uk" : normalized;
    }

    public void apply(Parent root) {
        if (root == null) return;
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        translateNode(root, visited);
    }

    /**
     * Translates both ordinary scene-graph children and nodes owned by JavaFX controls.
     * Several controls (for example ScrollPane, TitledPane, ToolBar and placeholders) keep
     * their logical content outside Parent#getChildrenUnmodifiable() until a skin is created.
     * Relying only on the scene-graph children therefore leaks stable keys such as
     * {@code ui.fxml.details.authors} into the visible UI.
     */
    private void translateNode(Node node, Set<Node> visited) {
        if (node == null || !visited.add(node)) return;

        if (node instanceof Control control) {
            if (control.getAccessibleText() != null && !control.getAccessibleText().isBlank()) {
                control.setAccessibleText(tr(control.getAccessibleText()));
            }
            Tooltip tooltip = control.getTooltip();
            if (tooltip != null && !tooltip.textProperty().isBound()) {
                tooltip.setText(tr(tooltip.getText()));
                translateNode(tooltip.getGraphic(), visited);
            }
            ContextMenu contextMenu = control.getContextMenu();
            if (contextMenu != null) {
                for (MenuItem item : contextMenu.getItems()) translateMenu(item, visited);
            }
        }
        if (node instanceof Labeled labeled) {
            if (!labeled.textProperty().isBound()) labeled.setText(tr(labeled.getText()));
            translateNode(labeled.getGraphic(), visited);
        }
        if (node instanceof TextInputControl input && !input.promptTextProperty().isBound()) {
            input.setPromptText(tr(input.getPromptText()));
        }
        if (node instanceof ComboBoxBase<?> combo && !combo.promptTextProperty().isBound()) {
            combo.setPromptText(tr(combo.getPromptText()));
        }
        if (node instanceof Text text && !text.textProperty().isBound()) {
            text.setText(tr(text.getText()));
        }
        if (node instanceof MenuBar menuBar) {
            for (Menu menu : menuBar.getMenus()) translateMenu(menu, visited);
        }
        if (node instanceof MenuButton menuButton) {
            for (MenuItem item : menuButton.getItems()) translateMenu(item, visited);
        }
        if (node instanceof TableView<?> tableView) {
            for (TableColumn<?, ?> column : tableView.getColumns()) translateColumn(column, visited);
            translateNode(tableView.getPlaceholder(), visited);
        }
        if (node instanceof TreeTableView<?> treeTableView) {
            for (TreeTableColumn<?, ?> column : treeTableView.getColumns()) translateTreeColumn(column, visited);
            translateNode(treeTableView.getPlaceholder(), visited);
        }
        if (node instanceof ListView<?> listView) {
            translateNode(listView.getPlaceholder(), visited);
        }
        if (node instanceof TabPane tabPane) {
            for (Tab tab : tabPane.getTabs()) translateTab(tab, visited);
        }
        if (node instanceof ScrollPane scrollPane) {
            translateNode(scrollPane.getContent(), visited);
        }
        if (node instanceof TitledPane titledPane) {
            translateNode(titledPane.getContent(), visited);
        }
        if (node instanceof SplitPane splitPane) {
            for (Node item : splitPane.getItems()) translateNode(item, visited);
        }
        if (node instanceof ToolBar toolBar) {
            for (Node item : toolBar.getItems()) translateNode(item, visited);
        }
        if (node instanceof Accordion accordion) {
            for (TitledPane pane : accordion.getPanes()) translateNode(pane, visited);
        }
        if (node instanceof DialogPane dialogPane) {
            if (!dialogPane.headerTextProperty().isBound()) dialogPane.setHeaderText(tr(dialogPane.getHeaderText()));
            if (!dialogPane.contentTextProperty().isBound()) dialogPane.setContentText(tr(dialogPane.getContentText()));
            translateNode(dialogPane.getHeader(), visited);
            translateNode(dialogPane.getContent(), visited);
            translateNode(dialogPane.getExpandableContent(), visited);
            translateNode(dialogPane.getGraphic(), visited);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) translateNode(child, visited);
        }
    }

    private void translateTab(Tab tab, Set<Node> visited) {
        if (!tab.textProperty().isBound()) tab.setText(tr(tab.getText()));
        translateNode(tab.getGraphic(), visited);
        translateNode(tab.getContent(), visited);
        ContextMenu menu = tab.getContextMenu();
        if (menu != null) {
            for (MenuItem item : menu.getItems()) translateMenu(item, visited);
        }
    }

    private void translateMenu(MenuItem item, Set<Node> visited) {
        if (!item.textProperty().isBound()) item.setText(tr(item.getText()));
        translateNode(item.getGraphic(), visited);
        if (item instanceof Menu menu) {
            for (MenuItem child : menu.getItems()) translateMenu(child, visited);
        }
    }

    private void translateColumn(TableColumn<?, ?> column, Set<Node> visited) {
        if (!column.textProperty().isBound()) column.setText(tr(column.getText()));
        translateNode(column.getGraphic(), visited);
        for (TableColumn<?, ?> child : column.getColumns()) translateColumn(child, visited);
    }

    private void translateTreeColumn(TreeTableColumn<?, ?> column, Set<Node> visited) {
        if (!column.textProperty().isBound()) column.setText(tr(column.getText()));
        translateNode(column.getGraphic(), visited);
        for (TreeTableColumn<?, ?> child : column.getColumns()) translateTreeColumn(child, visited);
    }
}
