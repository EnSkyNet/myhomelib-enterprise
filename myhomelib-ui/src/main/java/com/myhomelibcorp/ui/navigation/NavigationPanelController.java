package com.myhomelibcorp.ui.navigation;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.navigation.NavigationMode;
import com.myhomelibcorp.application.navigation.NavigationNodeDto;
import com.myhomelibcorp.application.navigation.NavigationQueryService;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationPanelController {

    private final NavigationQueryService navigationQueryService;
    private final AlphabetToolbarController alphabetToolbarController;
    private final LocalizationService localizationService;
    private final ApplicationState appState;

    private Consumer<NavigationNodeDto> onNodeSelected;

    @FXML private ListView<NavigationNodeDto> navigationListView;
    @FXML private javafx.scene.control.Label navigationTitleLabel;
    @FXML private TextField listSearchField;
    @FXML private ComboBox<NavigationMode> navigationModeComboBox;
    @FXML private Button loadMoreAuthorsButton;

    private List<NavigationNodeDto> allNodes = List.of();
    private NavigationMode currentMode = NavigationMode.AUTHORS;
    private Character currentLetter;
    private String currentQuery = "";
    private long loadGeneration;
    private NavigationMode pendingSelectionMode;
    private String pendingSelectionId;
    private boolean suppressSelectionCallback;
    private boolean temporaryAuthorSearch;
    private boolean authorPanelSearchActive;
    private boolean suppressListSearchListener;
    private Character authorLetterBeforeSearch;
    private static final int AUTHOR_PAGE_SIZE = 500;
    private static final int AUTHOR_SEARCH_LIMIT = 200;
    private java.util.OptionalLong authorTotal = java.util.OptionalLong.empty();
    private NavigationQueryService.AuthorCursor authorCursor;
    private boolean loadingMoreAuthors;
    private final PauseTransition authorSearchDebounce = new PauseTransition(Duration.millis(250));

    @FXML
    public void initialize() {
        configureModeSelector();
        configureList();

        listSearchField.textProperty().addListener((obs, old, query) -> {
            if (suppressListSearchListener) return;
            currentQuery = query == null ? "" : query;
            if (currentMode == NavigationMode.AUTHORS) {
                authorSearchDebounce.stop();
                authorSearchDebounce.setOnFinished(e -> performAuthorListSearch(currentQuery));
                authorSearchDebounce.playFromStart();
            } else {
                filterList();
            }
        });

        alphabetToolbarController.setOnLetterSelected(letter -> {
            if (currentMode == NavigationMode.AUTHORS) {
                currentLetter = letter == '*' ? null : letter;
                loadMode(NavigationMode.AUTHORS);
            } else {
                currentLetter = letter;
                filterList();
            }
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

    @EventListener
    public void onNavigationRefresh(NavigationRefreshEvent event) {
        // Download/storage changes must not destroy transient author-search results.
        // The result set remains valid; only catalogue facets/counts changed in the background.
        if (temporaryAuthorSearch) return;
        refreshAll();
    }

    public void refreshAll() {
        temporaryAuthorSearch = false;
        updateNavigationTitle();
        log.info("Оновлення навігаційної панелі: {}", currentMode);
        // Очищаємо кеш перед завантаженням
        allNodes = List.of();
        navigationListView.getItems().clear();
        Platform.runLater(() -> loadMode(currentMode));
    }

    public void refreshForFilterChange() {
        log.info("Оновлення навігації після зміни глобального фільтра: {}", currentMode);
        Platform.runLater(() -> {
            if (currentMode == NavigationMode.AUTHORS) {
                String authorQuery = listSearchField == null ? "" : listSearchField.getText().trim();
                if (!authorQuery.isBlank()) {
                    performAuthorListSearch(authorQuery);
                    return;
                }
                currentLetter = null;
                alphabetToolbarController.clearSelection();
            }
            // Очищаємо кеш
            allNodes = List.of();
            navigationListView.getItems().clear();
            loadMode(currentMode);
        });
    }



    /**
     * Повне скидання навігації - очищує всі кеші та перезавантажує
     */
    public void resetNavigation() {
        log.info("Повне скидання навігації");
        Platform.runLater(() -> {
            temporaryAuthorSearch = false;
            updateNavigationTitle();
            allNodes = List.of();
            navigationListView.getItems().clear();
            currentLetter = null;
            currentQuery = "";
            alphabetToolbarController.clearSelection();
            suppressListSearchListener = true;
            try {
                listSearchField.clear();
            } finally {
                suppressListSearchListener = false;
            }
            loadMode(NavigationMode.AUTHORS);
        });
    }

    public void loadMode(NavigationMode mode) {
        if (mode == null) {
            return;
        }
        temporaryAuthorSearch = false;
        authorPanelSearchActive = false;
        authorLetterBeforeSearch = null;
        authorSearchDebounce.stop();
        updateNavigationTitle();
        boolean modeChanged = currentMode != mode;
        currentMode = mode;
        alphabetToolbarController.setAllOptionEnabled(mode != NavigationMode.AUTHORS);
        if (modeChanged) {
            currentLetter = mode == NavigationMode.AUTHORS ? null : '*';
            alphabetToolbarController.clearSelection();
            navigationListView.getSelectionModel().clearSelection();
        }
        if (navigationModeComboBox.getValue() != mode) {
            navigationModeComboBox.setValue(mode);
        }

        if (mode == NavigationMode.AUTHORS && currentLetter == null) {
            resolveFirstAuthorInitialAndLoad();
            return;
        }
        if (mode == NavigationMode.AUTHORS) {
            loadAuthorPage(currentLetter, false);
            return;
        }
        resetAuthorPaging();
        loadNodes(mode, mode == NavigationMode.AUTHORS ? currentLetter : null);
    }

    private void resolveFirstAuthorInitialAndLoad() {
        long generation = ++loadGeneration;
        String collectionId = currentCollectionId();
        navigationListView.setDisable(true);
        navigationQueryService.findFirstAuthorInitial().thenAccept(initial -> UiExecutor.runOnUiThread(() -> {
            if (!isCurrent(generation, collectionId) || currentMode != NavigationMode.AUTHORS) {
                return;
            }
            currentLetter = initial.orElse(null);
            if (currentLetter != null) {
                alphabetToolbarController.selectLetter(currentLetter);
                loadAuthorPage(currentLetter, false);
            } else {
                allNodes = List.of();
                navigationListView.getItems().clear();
                navigationListView.setDisable(false);
                resetAuthorPaging();
            }
        })).exceptionally(ex -> {
            handleLoadFailure(generation, collectionId, NavigationMode.AUTHORS, ex);
            return null;
        });
    }

    private void loadNodes(NavigationMode mode, Character initial) {
        long generation = ++loadGeneration;
        String collectionId = currentCollectionId();
        navigationListView.setDisable(true);
        navigationQueryService.load(mode, initial).thenAccept(nodes -> UiExecutor.runOnUiThread(() -> {
            if (!isCurrent(generation, collectionId) || currentMode != mode) {
                return;
            }
            allNodes = nodes == null ? List.of() : List.copyOf(nodes);
            navigationListView.setDisable(false);
            filterList();
            log.info("Завантажено {} navigation nodes для {}{}", allNodes.size(), mode,
                    mode == NavigationMode.AUTHORS ? " / " + initial : "");
        })).exceptionally(ex -> {
            handleLoadFailure(generation, collectionId, mode, ex);
            return null;
        });
    }

    private void loadAuthorPage(Character initial, boolean append) {
        if (initial == null || loadingMoreAuthors) return;
        long generation = ++loadGeneration;
        String collectionId = currentCollectionId();
        NavigationQueryService.AuthorCursor requestCursor = append ? authorCursor : null;
        if (!append) {
            authorTotal = java.util.OptionalLong.empty();
            authorCursor = null;
            allNodes = List.of();
            navigationListView.getItems().clear();
        }
        loadingMoreAuthors = true;
        navigationListView.setDisable(true);
        updateLoadMoreAuthorsButton();

        navigationQueryService.loadAuthorsAfter(initial, AUTHOR_PAGE_SIZE, requestCursor)
                .thenAccept(page -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrent(generation, collectionId) || currentMode != NavigationMode.AUTHORS
                            || !java.util.Objects.equals(currentLetter, initial)) {
                        return;
                    }
                    loadingMoreAuthors = false;
                    if (page != null && page.totalElements().isPresent()) {
                        authorTotal = page.totalElements();
                    }
                    authorCursor = page == null ? null : page.nextCursor();
                    List<NavigationNodeDto> incoming = page == null ? List.of() : page.content();
                    if (append && !incoming.isEmpty()) {
                        java.util.ArrayList<NavigationNodeDto> combined = new java.util.ArrayList<>(allNodes.size() + incoming.size());
                        combined.addAll(allNodes);
                        combined.addAll(incoming);
                        allNodes = List.copyOf(combined);
                    } else if (!append) {
                        allNodes = List.copyOf(incoming);
                    }
                    navigationListView.setDisable(false);
                    filterList();
                    updateLoadMoreAuthorsButton();
                    if (authorTotal.isPresent()) {
                        log.info("Завантажено {} / {} авторів для літери {} (keyset)",
                                allNodes.size(), authorTotal.getAsLong(), initial);
                    } else {
                        log.info("Завантажено {} авторів для літери {} (keyset, total не рахується)",
                                allNodes.size(), initial);
                    }
                })).exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        if (!isCurrent(generation, collectionId)) return;
                        loadingMoreAuthors = false;
                        updateLoadMoreAuthorsButton();
                    });
                    handleLoadFailure(generation, collectionId, NavigationMode.AUTHORS, ex);
                    return null;
                });
    }

    private void performAuthorListSearch(String query) {
        if (currentMode != NavigationMode.AUTHORS) return;
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            if (authorPanelSearchActive) {
                authorPanelSearchActive = false;
                temporaryAuthorSearch = false;
                updateNavigationTitle();
                currentLetter = authorLetterBeforeSearch;
                authorLetterBeforeSearch = null;
                if (currentLetter != null) alphabetToolbarController.selectLetter(currentLetter);
                loadMode(NavigationMode.AUTHORS);
            } else {
                filterList();
            }
            return;
        }

        if (!authorPanelSearchActive) authorLetterBeforeSearch = currentLetter;
        authorPanelSearchActive = true;
        temporaryAuthorSearch = true;
        currentLetter = null;
        resetAuthorPaging();
        alphabetToolbarController.clearSelection();
        final long generation = ++loadGeneration;
        final String collectionId = currentCollectionId();
        navigationListView.setDisable(true);
        updateNavigationTitle(normalized);

        navigationQueryService.searchAuthors(normalized, AUTHOR_SEARCH_LIMIT)
                .thenAccept(nodes -> UiExecutor.runOnUiThread(() -> {
                    if (!isCurrent(generation, collectionId) || currentMode != NavigationMode.AUTHORS
                            || !normalized.equals(currentQuery == null ? "" : currentQuery.trim())) return;
                    allNodes = nodes == null ? List.of() : List.copyOf(nodes);
                    navigationListView.setDisable(false);
                    navigationListView.getSelectionModel().clearSelection();
                    filterList();
                    updateLoadMoreAuthorsButton();
                    log.info("Server-side author search '{}': {} results", normalized, allNodes.size());
                })).exceptionally(ex -> {
                    handleLoadFailure(generation, collectionId, NavigationMode.AUTHORS, ex);
                    return null;
                });
    }

    @FXML
    private void onClearListSearch() {
        if (listSearchField == null) return;
        authorSearchDebounce.stop();
        listSearchField.clear();
        listSearchField.requestFocus();
    }

    @FXML
    private void onLoadMoreAuthors() {
        if (currentMode != NavigationMode.AUTHORS || currentLetter == null || temporaryAuthorSearch
                || loadingMoreAuthors || authorCursor == null) return;
        loadAuthorPage(currentLetter, true);
    }

    private void resetAuthorPaging() {
        authorTotal = java.util.OptionalLong.empty();
        authorCursor = null;
        loadingMoreAuthors = false;
        updateLoadMoreAuthorsButton();
    }

    private void updateLoadMoreAuthorsButton() {
        if (loadMoreAuthorsButton == null) return;
        boolean visible = currentMode == NavigationMode.AUTHORS && !temporaryAuthorSearch
                && currentLetter != null && authorCursor != null;
        loadMoreAuthorsButton.setVisible(visible);
        loadMoreAuthorsButton.setManaged(visible);
        loadMoreAuthorsButton.setDisable(loadingMoreAuthors);
        loadMoreAuthorsButton.setText(loadingMoreAuthors
                ? UiText.text("ui.fxml.book_workspace.loading")
                : authorTotal.isPresent()
                    ? UiText.text("ui.java.navigation_panel_controller.aae0c3c3c5") + allNodes.size() + " / " + authorTotal.getAsLong() + ")"
                    : UiText.text("ui.java.navigation_panel_controller.aae0c3c3c5") + allNodes.size() + ")");
    }

    private void handleLoadFailure(long generation, String collectionId, NavigationMode mode, Throwable ex) {
        UiExecutor.runOnUiThread(() -> {
            if (isCurrent(generation, collectionId)) {
                allNodes = List.of();
                navigationListView.getItems().clear();
                navigationListView.setDisable(false);
            }
        });
        log.error("Помилка завантаження навігації для {}", mode, ex);
    }

    private boolean isCurrent(long generation, String collectionId) {
        return generation == loadGeneration && java.util.Objects.equals(collectionId, currentCollectionId());
    }

    private String currentCollectionId() {
        var collection = appState.getCurrentLibraryCollection();
        return collection == null ? null : collection.getId();
    }

    private void filterList() {
        char letter = currentLetter == null ? '*' : currentLetter;
        String query = currentQuery == null ? "" : currentQuery.trim().toLowerCase(Locale.ROOT);

        List<String> genreCodes = currentMode == NavigationMode.GENRES
                ? allNodes.stream().map(NavigationNodeDto::id).filter(java.util.Objects::nonNull).toList()
                : List.of();
        List<NavigationNodeDto> filtered = allNodes.stream()
                .filter(node -> currentMode != NavigationMode.GENRES
                        || localizationService.shouldDisplayGenre(node.id(), genreCodes))
                .filter(node -> currentMode == NavigationMode.ALL_BOOKS
                        || currentMode == NavigationMode.ALREADY_READ
                        || currentMode == NavigationMode.HISTORY
                        || currentMode == NavigationMode.UPDATES
                        || (currentMode == NavigationMode.AUTHORS
                        ? matchesQuery(displayLabel(node), query)
                        : matchesFilter(displayLabel(node), letter, query)))
                .toList();

        UiExecutor.runOnUiThread(() -> {
            navigationListView.getItems().setAll(filtered);
            applyPendingSelection();
        });
    }

    private boolean matchesQuery(String name, String query) {
        if (name == null || name.isBlank()) return false;
        return query == null || query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean matchesFilter(String name, char letter, String query) {
        if (name == null || name.isBlank()) return false;
        String normalized = name.toLowerCase(Locale.ROOT);
        if (!query.isEmpty() && !normalized.contains(query)) return false;
        if (letter == '*') return true;
        if (letter == '#') return !Character.isLetter(name.charAt(0));
        return Character.toUpperCase(name.charAt(0)) == Character.toUpperCase(letter);
    }

    /**
     * Shows server-side author-search results in the left sidebar. Selecting a row
     * uses the normal AUTHORS navigation callback, therefore the central area
     * becomes the selected author workspace with that author's books.
     */
    public void showAuthorSearchResults(String query, List<AuthorDto> authors) {
        loadGeneration++; // invalidate any in-flight navigation load
        temporaryAuthorSearch = true;
        authorPanelSearchActive = false;
        authorLetterBeforeSearch = null;
        currentMode = NavigationMode.AUTHORS;
        currentLetter = null;
        pendingSelectionMode = null;
        pendingSelectionId = null;
        currentQuery = "";

        if (navigationModeComboBox != null && navigationModeComboBox.getValue() != NavigationMode.AUTHORS) {
            navigationModeComboBox.setValue(NavigationMode.AUTHORS);
        }
        alphabetToolbarController.clearSelection();
        if (listSearchField != null && !listSearchField.getText().isEmpty()) {
            suppressListSearchListener = true;
            try {
                listSearchField.clear();
            } finally {
                suppressListSearchListener = false;
            }
        }

        List<NavigationNodeDto> nodes = authors == null ? List.of() : authors.stream()
                .filter(java.util.Objects::nonNull)
                .filter(author -> author.getId() != null && !author.getId().isBlank())
                .map(author -> NavigationNodeDto.of(
                        NavigationMode.AUTHORS,
                        author.getId(),
                        author.getFullName() == null || author.getFullName().isBlank()
                                ? author.getShortName()
                                : author.getFullName()))
                .toList();
        allNodes = List.copyOf(nodes);
        navigationListView.setDisable(false);
        navigationListView.getSelectionModel().clearSelection();
        updateNavigationTitle(query);
        updateLoadMoreAuthorsButton();
        filterList();
    }

    /** Restores regular author navigation after a transient search is cleared. */
    public void clearAuthorSearchResults() {
        if (!temporaryAuthorSearch) return;
        temporaryAuthorSearch = false;
        authorPanelSearchActive = false;
        authorLetterBeforeSearch = null;
        updateNavigationTitle();
        currentLetter = null;
        loadMode(NavigationMode.AUTHORS);
    }

    private void updateNavigationTitle() {
        if (navigationTitleLabel != null) navigationTitleLabel.setText(localizationService.text("ui.text.navigation"));
    }

    private void updateNavigationTitle(String query) {
        if (navigationTitleLabel == null) return;
        String suffix = query == null || query.isBlank() ? "" : ": " + query.trim();
        navigationTitleLabel.setText(localizationService.text("ui.text.authors_search_results") + suffix);
    }

    private String displayLabel(NavigationNodeDto node) {
        return switch (node.mode()) {
            case ALL_BOOKS -> modeLabel(NavigationMode.ALL_BOOKS);
            case ALREADY_READ -> modeLabel(NavigationMode.ALREADY_READ);
            case HISTORY -> modeLabel(NavigationMode.HISTORY);
            case UPDATES -> modeLabel(NavigationMode.UPDATES);
            case LANGUAGES -> languageLabel(node.id());
            case GENRES -> localizationService.genreName(node.id(), node.label());
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
            case AUTHORS -> UiText.text("ui.fxml.details.authors");
            case SERIES -> UiText.text("ui.fxml.search_workspace.series_ee6d3f5");
            case GENRES -> UiText.text("ui.bulkedit.field.genres");
            case YEARS -> UiText.text("ui.java.navigation_panel_controller.d78759999d");
            case LANGUAGES -> UiText.text("ui.java.navigation_panel_controller.7fd89b3757");
            case ARCHIVES -> UiText.text("ui.import.filter.archives");
            case KEYWORDS -> UiText.text("ui.fxml.details.keywords");
            case GROUPS -> UiText.text("ui.fxml.details.groups");
            case REVIEWS -> UiText.text("ui.java.navigation_panel_controller.5b633ba58d");
            case UPDATES -> UiText.text("ui.fxml.updates_workspace.updates");
            case DOWNLOADED -> UiText.text("ui.java.navigation_panel_controller.c3d3dc029b");
            case ALREADY_READ -> UiText.text("ui.main.fxml.read");
            case HISTORY -> UiText.text("ui.main.fxml.reading_history");
            case ALL_BOOKS -> UiText.text("ui.annotations.all_books");
        });
    }

    private String groupLabel(String rawName) {
        if (rawName == null) return "";
        return switch (rawName.trim().toLowerCase(Locale.ROOT)) {
            case "favorites" -> localizationService.text("ui.text.favorites");
            case "to read" -> localizationService.text("ui.text.to_read");
            default -> rawName;
        };
    }

    private String reviewLabel(String id) {
        return localizationService.tr(switch (id) {
            case "rated" -> UiText.text("ui.java.navigation_panel_controller.d5ccd0eb01");
            case "reviewed" -> UiText.text("ui.java.navigation_panel_controller.5228db4abf");
            case "rated-reviewed" -> UiText.text("ui.java.navigation_panel_controller.d03ee34079");
            default -> id;
        });
    }

    public void revealNode(NavigationMode mode, String nodeId) {
        if (mode == null || nodeId == null || nodeId.isBlank()) return;
        pendingSelectionMode = mode;
        pendingSelectionId = nodeId;
        currentQuery = "";
        alphabetToolbarController.clearSelection();
        if (listSearchField != null && !listSearchField.getText().isEmpty()) {
            suppressListSearchListener = true;
            try {
                listSearchField.clear();
            } finally {
                suppressListSearchListener = false;
            }
        }

        if (mode == NavigationMode.AUTHORS) {
            long generation = ++loadGeneration;
            String collectionId = currentCollectionId();
            currentMode = mode;
            navigationModeComboBox.setValue(mode);
            navigationListView.setDisable(true);
            navigationQueryService.findAuthorInitial(nodeId).thenAccept(initial -> UiExecutor.runOnUiThread(() -> {
                if (!isCurrent(generation, collectionId) || currentMode != NavigationMode.AUTHORS) return;
                currentLetter = initial.orElse(null);
                if (currentLetter != null) {
                    alphabetToolbarController.selectLetter(currentLetter);
                }
                loadMode(mode);
            })).exceptionally(ex -> {
                handleLoadFailure(generation, collectionId, mode, ex);
                return null;
            });
            return;
        }

        currentLetter = '*';
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
        this.currentLetter = letter == '*' && currentMode == NavigationMode.AUTHORS ? null : letter;
        alphabetToolbarController.selectLetter(letter);
        if (currentMode == NavigationMode.AUTHORS) {
            loadMode(NavigationMode.AUTHORS);
        } else {
            filterList();
        }
    }

    public void clearSelection() {
        alphabetToolbarController.clearSelection();
        navigationListView.getSelectionModel().clearSelection();
    }

    @FXML public void onAuthors() { loadMode(NavigationMode.AUTHORS); clearSelection(); }
    @FXML public void onSeries() { loadMode(NavigationMode.SERIES); clearSelection(); }
    @FXML public void onGenres() { loadMode(NavigationMode.GENRES); clearSelection(); }
    @FXML public void onAllBooks() { loadMode(NavigationMode.ALL_BOOKS); clearSelection(); }

}
