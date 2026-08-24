package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.navigation.NavigationMode;
import com.myhomelibcorp.application.navigation.NavigationNodeDto;
import com.myhomelibcorp.application.navigation.NavigationQueryService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Thin JavaFX adapter for application navigation.
 * Catalogue access and node construction live in NavigationQueryService.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationPanelController {

    private final NavigationQueryService navigationQueryService;
    private final AlphabetToolbarController alphabetToolbarController;
    private final LocalizationService localizationService;

    private Consumer<NavigationNodeDto> onNodeSelected;

    @FXML private ListView<NavigationNodeDto> navigationListView;
    @FXML private TextField listSearchField;
    @FXML private ComboBox<NavigationMode> navigationModeComboBox;

    private List<NavigationNodeDto> allNodes = List.of();
    private NavigationMode currentMode = NavigationMode.AUTHORS;
    private char currentLetter = '*';
    private String currentQuery = "";
    private long loadGeneration;
    private NavigationMode pendingSelectionMode;
    private String pendingSelectionId;
    private boolean suppressSelectionCallback;

    @FXML
    public void initialize() {
        configureModeSelector();
        configureList();

        listSearchField.textProperty().addListener((obs, old, query) -> {
            currentQuery = query == null ? "" : query;
            filterList();
        });

        alphabetToolbarController.setOnLetterSelected(letter -> {
            currentLetter = letter;
            filterList();
        });

        navigationModeComboBox.setValue(NavigationMode.AUTHORS);
        loadMode(NavigationMode.AUTHORS);
    }

    private void configureModeSelector() {
        navigationModeComboBox.getItems().setAll(NavigationMode.values());
        navigationModeComboBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(NavigationMode mode, boolean empty) {
                super.updateItem(mode, empty);
                setText(empty || mode == null ? null : modeLabel(mode));
            }
        });
        navigationModeComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(NavigationMode mode, boolean empty) {
                super.updateItem(mode, empty);
                setText(empty || mode == null ? null : modeLabel(mode));
            }
        });
        navigationModeComboBox.valueProperty().addListener((obs, oldMode, newMode) -> {
            if (newMode != null && newMode != currentMode) {
                loadMode(newMode);
            }
        });
    }

    private void configureList() {
        navigationListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(NavigationNodeDto item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                String label = displayLabel(item);
                setText(item.hasKnownBookCount() ? label + " (" + item.bookCount() + ")" : label);
            }
        });

        navigationListView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected == null || onNodeSelected == null || suppressSelectionCallback) {
                return;
            }
            log.debug("Navigation node selected: mode={}, id={}, label={}",
                    selected.mode(), selected.id(), selected.label());
            onNodeSelected.accept(selected);
        });
    }

    public void setOnNodeSelected(Consumer<NavigationNodeDto> callback) {
        this.onNodeSelected = callback;
    }

    public void refreshAll() {
        log.info("Оновлення навігаційної панелі: {}", currentMode);
        Platform.runLater(() -> loadMode(currentMode));
    }

    public void loadMode(NavigationMode mode) {
        if (mode == null) {
            return;
        }
        boolean modeChanged = currentMode != mode;
        currentMode = mode;
        if (modeChanged) {
            currentLetter = '*';
            alphabetToolbarController.clearSelection();
            navigationListView.getSelectionModel().clearSelection();
        }
        if (navigationModeComboBox.getValue() != mode) {
            navigationModeComboBox.setValue(mode);
        }

        long generation = ++loadGeneration;
        navigationListView.setDisable(true);
        navigationQueryService.load(mode).thenAccept(nodes -> UiExecutor.runOnUiThread(() -> {
            if (generation != loadGeneration || currentMode != mode) {
                return; // stale async response after a fast mode switch
            }
            allNodes = nodes == null ? List.of() : List.copyOf(nodes);
            navigationListView.setDisable(false);
            filterList();
            log.info("Завантажено {} navigation nodes для {}", allNodes.size(), mode);
        })).exceptionally(ex -> {
            UiExecutor.runOnUiThread(() -> {
                if (generation == loadGeneration) {
                    allNodes = List.of();
                    navigationListView.getItems().clear();
                    navigationListView.setDisable(false);
                }
            });
            log.error("Помилка завантаження навігації для {}", mode, ex);
            return null;
        });
    }

    private void filterList() {
        char letter = currentLetter;
        String query = currentQuery == null ? "" : currentQuery.trim().toLowerCase(Locale.ROOT);

        List<NavigationNodeDto> filtered = allNodes.stream()
                .filter(node -> currentMode == NavigationMode.ALL_BOOKS
                        || currentMode == NavigationMode.ALREADY_READ
                        || currentMode == NavigationMode.HISTORY
                        || matchesFilter(displayLabel(node), letter, query))
                .toList();

        UiExecutor.runOnUiThread(() -> {
            navigationListView.getItems().setAll(filtered);
            applyPendingSelection();
        });
    }

    private boolean matchesFilter(String name, char letter, String query) {
        if (name == null || name.isBlank()) return false;
        String normalized = name.toLowerCase(Locale.ROOT);
        if (!query.isEmpty() && !normalized.contains(query)) return false;
        if (letter == '*') return true;
        if (letter == '#') return !Character.isLetter(name.charAt(0));
        return Character.toUpperCase(name.charAt(0)) == Character.toUpperCase(letter);
    }

    private String displayLabel(NavigationNodeDto node) {
        return switch (node.mode()) {
            case ALL_BOOKS -> modeLabel(NavigationMode.ALL_BOOKS);
            case ALREADY_READ -> modeLabel(NavigationMode.ALREADY_READ);
            case HISTORY -> modeLabel(NavigationMode.HISTORY);
            case LANGUAGES -> languageLabel(node.id());
            case GROUPS -> groupLabel(node.label());
            case REVIEWS -> reviewLabel(node.id());
            default -> node.label();
        };
    }

    private String languageLabel(String languageCode) {
        try {
            Locale uiLocale = Locale.forLanguageTag(localizationService.language());
            String name = Locale.forLanguageTag(languageCode).getDisplayLanguage(uiLocale);
            if (name == null || name.isBlank() || name.equalsIgnoreCase(languageCode)) return languageCode;
            String display = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            return display + " (" + languageCode + ")";
        } catch (Exception e) {
            return languageCode;
        }
    }

    private String modeLabel(NavigationMode mode) {
        return localizationService.tr(switch (mode) {
            case AUTHORS -> "Автори";
            case SERIES -> "Серії";
            case GENRES -> "Жанри";
            case YEARS -> "Роки";
            case LANGUAGES -> "Мови";
            case ARCHIVES -> "Архіви";
            case KEYWORDS -> "Ключові слова";
            case GROUPS -> "Групи";
            case REVIEWS -> "Відгуки";
            case ALREADY_READ -> "Прочитані";
            case HISTORY -> "Історія читання";
            case ALL_BOOKS -> "Усі книги";
        });
    }

    private String groupLabel(String rawName) {
        if (rawName == null) return "";
        return switch (rawName.trim().toLowerCase(Locale.ROOT)) {
            case "favorites" -> localizationService.tr("Обране");
            case "to read" -> localizationService.tr("До читання");
            default -> rawName;
        };
    }

    private String reviewLabel(String id) {
        return localizationService.tr(switch (id) {
            case "rated" -> "Оцінені";
            case "reviewed" -> "З відгуками";
            case "rated-reviewed" -> "Оцінені з відгуками";
            default -> id;
        });
    }

    /** Switches the sidebar to a mode and selects a stable node without firing navigation twice. */
    public void revealNode(NavigationMode mode, String nodeId) {
        if (mode == null || nodeId == null || nodeId.isBlank()) return;
        pendingSelectionMode = mode;
        pendingSelectionId = nodeId;
        currentLetter = '*';
        currentQuery = "";
        alphabetToolbarController.clearSelection();
        if (listSearchField != null && !listSearchField.getText().isEmpty()) {
            listSearchField.clear();
        }
        loadMode(mode);
    }

    private void applyPendingSelection() {
        if (pendingSelectionMode != currentMode || pendingSelectionId == null) return;
        NavigationNodeDto match = navigationListView.getItems().stream()
                .filter(node -> node.id().equalsIgnoreCase(pendingSelectionId))
                .findFirst()
                .orElse(null);
        if (match == null) return;
        suppressSelectionCallback = true;
        try {
            navigationListView.getSelectionModel().select(match);
            navigationListView.scrollTo(match);
            pendingSelectionMode = null;
            pendingSelectionId = null;
        } finally {
            suppressSelectionCallback = false;
        }
    }

    public NavigationMode getCurrentMode() {
        return currentMode;
    }

    public void selectLetter(char letter) {
        this.currentLetter = letter;
        alphabetToolbarController.selectLetter(letter);
        filterList();
    }

    public void clearSelection() {
        alphabetToolbarController.clearSelection();
        navigationListView.getSelectionModel().clearSelection();
    }

    // Compatibility entry points used by MainController and existing FXML actions.
    public void loadAuthors() { loadMode(NavigationMode.AUTHORS); }
    public void loadSeries() { loadMode(NavigationMode.SERIES); }
    public void loadGenres() { loadMode(NavigationMode.GENRES); }
    public void loadYears() { loadMode(NavigationMode.YEARS); }
    public void loadLanguages() { loadMode(NavigationMode.LANGUAGES); }
    public void loadArchives() { loadMode(NavigationMode.ARCHIVES); }
    public void loadKeywords() { loadMode(NavigationMode.KEYWORDS); }
    public void loadGroups() { loadMode(NavigationMode.GROUPS); }
    public void loadReviews() { loadMode(NavigationMode.REVIEWS); }
    public void loadAlreadyRead() { loadMode(NavigationMode.ALREADY_READ); }
    public void loadHistory() { loadMode(NavigationMode.HISTORY); }
    public void loadAllBooks() { loadMode(NavigationMode.ALL_BOOKS); }

    @FXML public void onAuthors() { loadAuthors(); clearSelection(); }
    @FXML public void onSeries() { loadSeries(); clearSelection(); }
    @FXML public void onGenres() { loadGenres(); clearSelection(); }
    @FXML public void onYears() { loadYears(); clearSelection(); }
    @FXML public void onLanguages() { loadLanguages(); clearSelection(); }
    @FXML public void onArchives() { loadArchives(); clearSelection(); }
    @FXML public void onKeywords() { loadKeywords(); clearSelection(); }
    @FXML public void onGroups() { loadGroups(); clearSelection(); }
    @FXML public void onReviews() { loadReviews(); clearSelection(); }
    @FXML public void onAlreadyRead() { loadAlreadyRead(); clearSelection(); }
    @FXML public void onHistory() { loadHistory(); clearSelection(); }
    @FXML public void onAllBooks() { loadAllBooks(); clearSelection(); }
}
