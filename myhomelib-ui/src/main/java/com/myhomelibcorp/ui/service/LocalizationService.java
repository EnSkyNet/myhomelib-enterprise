package com.myhomelibcorp.ui.service;

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
import java.util.Map;

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
        if (cached != null && languageCatalogs.hasLanguage(cached)) return cached;

        String value = readLanguageFile();
        if (value == null) value = normalizeLanguage(settings.get("ui.language", languageCatalogs.fallbackLanguage()));
        if (!languageCatalogs.hasLanguage(value)) value = languageCatalogs.fallbackLanguage();

        cachedLanguage = value;
        // Create/synchronize the plain-text selection file on the first run as well.
        writeLanguageFile(value);
        return value;
    }

    public void setLanguage(String language) {
        String value = normalizeLanguage(language);
        if (!languageCatalogs.hasLanguage(value)) value = languageCatalogs.fallbackLanguage();
        cachedLanguage = value;
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

    public String tr(String text) {
        if (text == null) return null;
        return languageCatalogs.translations(language())
                .map(map -> map.getOrDefault(text, text))
                .orElse(text);
    }

    /** Localized display label for a stable FB2 genre code. */
    public String genreName(String genreCode, String fallback) {
        return languageCatalogs.genreName(language(), genreCode, fallback);
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
            if (!Files.isRegularFile(languageFile)) return null;
            return Files.readAllLines(languageFile, StandardCharsets.UTF_8).stream()
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
            Files.createDirectories(languageFile.getParent());
            Files.writeString(languageFile,
                    "# MyHomeLib selected UI language. UTF-8.\n"
                            + "# Available languages are generated in available-languages.txt.\n"
                            + value + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // ApplicationSettingsPort remains the fallback if the file is read-only.
        }
    }

    private static String normalizeLanguage(String language) {
        String normalized = LanguageCatalogService.normalizeCode(language);
        return normalized.isBlank() ? "uk" : normalized;
    }

    public void apply(Parent root) {
        if (root == null) return;
        translateNode(root);
    }

    private void translateNode(Node node) {
        if (node instanceof Labeled labeled && !labeled.textProperty().isBound()) {
            labeled.setText(tr(labeled.getText()));
        }
        if (node instanceof TextInputControl input && !input.promptTextProperty().isBound()) {
            input.setPromptText(tr(input.getPromptText()));
        }
        if (node instanceof Text text && !text.textProperty().isBound()) {
            text.setText(tr(text.getText()));
        }
        if (node instanceof MenuBar menuBar) {
            for (Menu menu : menuBar.getMenus()) translateMenu(menu);
        }
        if (node instanceof TableView<?> tableView) {
            for (TableColumn<?, ?> column : tableView.getColumns()) translateColumn(column);
        }
        if (node instanceof TreeTableView<?> treeTableView) {
            for (TreeTableColumn<?, ?> column : treeTableView.getColumns()) translateTreeColumn(column);
        }
        if (node instanceof TabPane tabPane) {
            for (Tab tab : tabPane.getTabs()) {
                if (!tab.textProperty().isBound()) {
                    tab.setText(tr(tab.getText()));
                }
                if (tab.getContent() != null) translateNode(tab.getContent());
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) translateNode(child);
        }
    }

    private void translateMenu(MenuItem item) {
        if (!item.textProperty().isBound()) {
            item.setText(tr(item.getText()));
        }
        if (item instanceof Menu menu) {
            for (MenuItem child : menu.getItems()) translateMenu(child);
        }
    }

    private void translateColumn(TableColumn<?, ?> column) {
        if (!column.textProperty().isBound()) {
            column.setText(tr(column.getText()));
        }
        for (TableColumn<?, ?> child : column.getColumns()) translateColumn(child);
    }

    private void translateTreeColumn(TreeTableColumn<?, ?> column) {
        if (!column.textProperty().isBound()) {
            column.setText(tr(column.getText()));
        }
        for (TreeTableColumn<?, ?> child : column.getColumns()) translateTreeColumn(child);
    }
}
