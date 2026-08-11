package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.port.out.cache.AlphabetFilterPort;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.application.port.out.repository.PublisherRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.publisher.Publisher;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.controller.MainController;
import com.myhomelibcorp.ui.event.CollectionChangedEvent;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.model.navigation.*;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import jakarta.annotation.PreDestroy;
import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Головний контролер навігації.
 * Координує роботу між:
 * - AlphabetToolbar (UI)
 * - NavigationViewModel (стан)
 * - NavigationFilterService (фільтрація)
 * - NavigationTreeBuilder (побудова дерева)
 * - BookLoaderService (завантаження книг у таблицю)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationController {

    // ===== ЗАЛЕЖНОСТІ =====
    private final NavigationService uiNavigationService;
    private final BookLoaderService bookLoaderService;
    private final CollectionRepository collectionRepository;
    private final GroupRepository groupRepository;
    private final SeriesRepository seriesRepository;
    private final PublisherRepository publisherRepository;
    private final ApplicationState appState;
    private final MainController mainController;
    private final NavigationViewModel viewModel;
    private final NavigationFilterService filterService;
    private final NavigationTreeBuilder treeBuilder;

    // ===== FXML =====
    @FXML private TreeView<LibraryNode> navigationTree;
    @FXML private VBox navigationContainer;
    @FXML private HBox alphabetToolbarContainer;
    @FXML private TextField authorSearchField;

    // ===== ВНУТРІШНІ ПОЛЯ =====
    private AlphabetToolbar alphabetToolbar;
    private TreeItem<LibraryNode> authorsItem;
    private TreeItem<LibraryNode> seriesItem;
    private TreeItem<LibraryNode> genresItem;
    private TreeItem<LibraryNode> collectionsItem;
    private TreeItem<LibraryNode> groupsItem;
    private TreeItem<LibraryNode> publishersItem;
    private ChangeListener<TreeItem<LibraryNode>> selectionListener;

    private final AtomicBoolean isLoading = new AtomicBoolean(false);

    @FXML
    public void initialize() {
        log.info("NavigationController.initialize()");

        // ===== 1. АЛФАВІТНА ПАНЕЛЬ =====
        alphabetToolbar = new AlphabetToolbar();
        alphabetToolbar.setOnLetterSelected(this::onAlphabetLetterSelected);

        if (alphabetToolbarContainer != null) {
            alphabetToolbarContainer.getChildren().add(alphabetToolbar);
            HBox.setHgrow(alphabetToolbar, javafx.scene.layout.Priority.ALWAYS);
            alphabetToolbar.prefWidthProperty().bind(alphabetToolbarContainer.widthProperty());
            log.info("AlphabetToolbar додано");
        }

        // ===== 2. ПОШУК АВТОРІВ =====
        if (authorSearchField != null) {
            authorSearchField.textProperty().addListener((obs, old, query) -> {
                if (query != null && !query.isEmpty()) {
                    filterAuthorsByQuery(query);
                } else {
                    onAlphabetLetterSelected(viewModel.getFilter());
                }
            });
        }

        // ===== 3. ДЕРЕВО НАВІГАЦІЇ =====
        TreeItem<LibraryNode> root = new TreeItem<>(null);
        root.setExpanded(true);

        authorsItem = treeBuilder.createPlaceholder();
        seriesItem = treeBuilder.createPlaceholder();
        genresItem = treeBuilder.createPlaceholder();
        collectionsItem = treeBuilder.createPlaceholder();
        groupsItem = treeBuilder.createPlaceholder();
        publishersItem = treeBuilder.createPlaceholder();

        root.getChildren().addAll(authorsItem, seriesItem, genresItem,
                collectionsItem, groupsItem, publishersItem);

        navigationTree.setRoot(root);
        navigationTree.setShowRoot(false);

        setupLazyLoading(authorsItem, this::loadAuthors);
        setupLazyLoading(seriesItem, this::loadSeries);
        setupLazyLoading(genresItem, this::loadGenres);
        setupLazyLoading(collectionsItem, this::loadCollections);
        setupLazyLoading(groupsItem, this::loadGroups);
        setupLazyLoading(publishersItem, this::loadPublishers);

        // ===== 4. ВИБІР У ДЕРЕВІ =====
        selectionListener = (obs, old, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                mainController.cleanupReader();
                handleNodeSelection(newVal.getValue());
            }
        };
        navigationTree.getSelectionModel().selectedItemProperty().addListener(selectionListener);

        // ===== 5. ПОЧАТКОВИЙ СТАН =====
        viewModel.setFilter('*');
        viewModel.setMode(NavigationViewModel.NavigationMode.AUTHORS);
        alphabetToolbar.selectLetter('*');

        log.info("NavigationController ініціалізовано");
    }

    // ==================== АЛФАВІТНА НАВІГАЦІЯ ====================

    private void onAlphabetLetterSelected(char letter) {
        log.debug("Вибрано літеру: '{}'", letter);
        viewModel.setFilter(letter);

        NavigationViewModel.NavigationMode mode = viewModel.getMode();

        switch (mode) {
            case AUTHORS -> {
                loadAuthors();
                loadAuthorsIntoTable(letter);
            }
            case SERIES -> {
                loadSeries();
                loadSeriesIntoTable(letter);
            }
            default -> log.debug("Фільтр не застосовано для режиму: {}", mode);
        }

        if (authorSearchField != null) {
            authorSearchField.setText("");
        }
    }

    private void filterAuthorsByQuery(String query) {
        log.debug("Фільтрація авторів за запитом: '{}'", query);
        // TODO: Реалізувати фільтрацію за текстовим запитом
    }

    // ==================== ЗАВАНТАЖЕННЯ ДАНИХ ====================

    private void loadAuthors() {
        if (authorsItem == null) return;
        TreeItem<LibraryNode> newTree = treeBuilder.buildAuthorsTree(viewModel.getFilter());
        replaceTreeItem(authorsItem, newTree);
        viewModel.setMode(NavigationViewModel.NavigationMode.AUTHORS);
    }

    private void loadSeries() {
        if (seriesItem == null) return;
        TreeItem<LibraryNode> newTree = treeBuilder.buildSeriesTree(viewModel.getFilter());
        replaceTreeItem(seriesItem, newTree);
        viewModel.setMode(NavigationViewModel.NavigationMode.SERIES);
    }

    private void loadGenres() {
        if (genresItem == null) return;
        TreeItem<LibraryNode> newTree = treeBuilder.buildGenresTree();
        replaceTreeItem(genresItem, newTree);
        viewModel.setMode(NavigationViewModel.NavigationMode.GENRES);
    }

    private void loadCollections() {
        if (collectionsItem == null) return;
        try {
            List<Collection> collections = collectionRepository.findAll();
            TreeItem<LibraryNode> newTree = treeBuilder.buildCollectionsTree(collections);
            replaceTreeItem(collectionsItem, newTree);
            viewModel.setMode(NavigationViewModel.NavigationMode.COLLECTIONS);
        } catch (Exception e) {
            log.error("Помилка завантаження колекцій", e);
        }
    }

    private void loadGroups() {
        if (groupsItem == null) return;
        try {
            List<Group> groups = groupRepository.findAll();
            TreeItem<LibraryNode> newTree = treeBuilder.buildGroupsTree(groups);
            replaceTreeItem(groupsItem, newTree);
            viewModel.setMode(NavigationViewModel.NavigationMode.GROUPS);
        } catch (Exception e) {
            log.error("Помилка завантаження груп", e);
        }
    }

    private void loadPublishers() {
        if (publishersItem == null) return;
        try {
            List<Publisher> publishers = publisherRepository.findAll();
            TreeItem<LibraryNode> newTree = treeBuilder.buildPublishersTree(publishers);
            replaceTreeItem(publishersItem, newTree);
            viewModel.setMode(NavigationViewModel.NavigationMode.PUBLISHERS);
        } catch (Exception e) {
            log.error("Помилка завантаження видавництв", e);
        }
    }

    // ==================== ОНОВЛЕННЯ ТАБЛИЦІ КНИГ ====================

    private void loadAuthorsIntoTable(char letter) {
        List<Author> authors = filterService.getAuthorsByLetter(letter);

        if (authors.isEmpty()) {
            appState.getBookTable().clear();
            appState.getStatusBar().setStatusText("Немає авторів на '" + letter + "'");
            return;
        }

        // Показуємо книги першого автора
        Author firstAuthor = authors.get(0);
        AuthorId authorId = firstAuthor.getId();

        log.info("Завантаження книг для автора: {}", firstAuthor.getFullName());
        bookLoaderService.loadBooksByAuthor(authorId);

        appState.getStatusBar().setStatusText(
                String.format("Автори на '%s': %d авторів", letter, authors.size())
        );
    }

    private void loadSeriesIntoTable(char letter) {
        List<Series> series = filterService.getSeriesByLetter(letter);

        if (series.isEmpty()) {
            appState.getBookTable().clear();
            appState.getStatusBar().setStatusText("Немає серій на '" + letter + "'");
            return;
        }

        // Показуємо книги першої серії
        Series firstSeries = series.get(0);
        SeriesId seriesId = firstSeries.getId();

        log.info("Завантаження книг для серії: {}", firstSeries.getName());
        bookLoaderService.loadBooksBySeries(seriesId);

        appState.getStatusBar().setStatusText(
                String.format("Серії на '%s': %d серій", letter, series.size())
        );
    }

    // ==================== ДОПОМІЖНІ МЕТОДИ ====================

    private void replaceTreeItem(TreeItem<LibraryNode> oldItem, TreeItem<LibraryNode> newItem) {
        TreeItem<LibraryNode> parent = oldItem.getParent();
        if (parent != null) {
            int index = parent.getChildren().indexOf(oldItem);
            parent.getChildren().set(index, newItem);
        }
    }

    private void setupLazyLoading(TreeItem<LibraryNode> item, Runnable loader) {
        item.addEventHandler(TreeItem.<LibraryNode>branchExpandedEvent(), event -> {
            TreeItem<LibraryNode> source = event.getTreeItem();
            if (source == item && source.getChildren().size() == 1
                    && source.getChildren().get(0).getValue() instanceof NavigationTreeBuilder.CategoryNode) {
                loader.run();
            }
        });
    }

    private void handleNodeSelection(LibraryNode node) {
        if (node instanceof AuthorNode) {
            AuthorId id = ((AuthorNode) node).author().getId();
            uiNavigationService.navigateToAuthor(id);
        } else if (node instanceof SeriesNode) {
            SeriesId id = ((SeriesNode) node).series().getId();
            uiNavigationService.navigateToSeries(id);
        } else if (node instanceof GenreNode) {
            GenreId id = ((GenreNode) node).genre().getId();
            uiNavigationService.navigateToGenre(id);
        } else if (node instanceof PublisherNode) {
            Publisher publisher = ((PublisherNode) node).publisher();
            uiNavigationService.navigateToPublisher(publisher.getName());
        } else if (node instanceof CollectionNode) {
            Collection collection = ((CollectionNode) node).collection();
            mainController.switchToCollection(collection);
        } else if (node instanceof GroupNode) {
            Group group = ((GroupNode) node).group();
            appState.setCurrentGroup(group);
            mainController.showGroupWorkspace(group);
        } else if (node instanceof NavigationTreeBuilder.CategoryNode) {
            NavigationTreeBuilder.CategoryNode cat = (NavigationTreeBuilder.CategoryNode) node;
            if ("groups".equals(cat.getType())) {
                mainController.showGroupWorkspace(appState.getCurrentGroup());
            }
        }
    }

    // ==================== FXML ДІЇ ====================

    @FXML private void onHome() {
        mainController.cleanupReader();
        mainController.showDashboard();
    }

    @FXML private void onAuthors() {
        mainController.cleanupReader();
        viewModel.setMode(NavigationViewModel.NavigationMode.AUTHORS);
        if (authorsItem != null) {
            authorsItem.setExpanded(true);
            navigationTree.getSelectionModel().select(authorsItem);
        }
        loadAuthors();
        loadAuthorsIntoTable(viewModel.getFilter());
    }

    @FXML private void onSeries() {
        mainController.cleanupReader();
        viewModel.setMode(NavigationViewModel.NavigationMode.SERIES);
        if (seriesItem != null) {
            seriesItem.setExpanded(true);
            navigationTree.getSelectionModel().select(seriesItem);
        }
        loadSeries();
        loadSeriesIntoTable(viewModel.getFilter());
    }

    @FXML private void onGenres() {
        mainController.cleanupReader();
        viewModel.setMode(NavigationViewModel.NavigationMode.GENRES);
        if (genresItem != null) {
            genresItem.setExpanded(true);
            navigationTree.getSelectionModel().select(genresItem);
        }
        loadGenres();
    }

    @FXML private void onCollections() {
        mainController.cleanupReader();
        viewModel.setMode(NavigationViewModel.NavigationMode.COLLECTIONS);
        if (collectionsItem != null) {
            collectionsItem.setExpanded(true);
            navigationTree.getSelectionModel().select(collectionsItem);
        }
        loadCollections();
    }

    @FXML private void onGroups() {
        mainController.cleanupReader();
        viewModel.setMode(NavigationViewModel.NavigationMode.GROUPS);
        if (groupsItem != null) {
            groupsItem.setExpanded(true);
            navigationTree.getSelectionModel().select(groupsItem);
        }
        loadGroups();
    }

    @FXML private void onNewBooks() {
        mainController.cleanupReader();
        bookLoaderService.loadRecentBooks();
    }

    @FXML private void onHistory() {
        mainController.cleanupReader();
        mainController.handleHistory();
    }

    @FXML private void onSearch() {
        mainController.cleanupReader();
        mainController.showSearchResults("");
    }

    @FXML private void onImport() {
        mainController.cleanupReader();
        mainController.showImportWorkspace();
    }

    @FXML private void onSettings() {
        mainController.cleanupReader();
        mainController.handleSettings();
    }

    // ==================== ПОДІЇ ====================

    @EventListener
    public void onCollectionChanged(CollectionChangedEvent event) {
        refreshNavigation();
    }

    @EventListener
    public void onNavigationRefresh(NavigationRefreshEvent event) {
        refreshNavigation();
    }

    public void refreshNavigation() {
        if (isLoading.getAndSet(true)) return;

        try {
            NavigationViewModel.NavigationMode mode = viewModel.getMode();
            switch (mode) {
                case AUTHORS -> {
                    loadAuthors();
                    loadAuthorsIntoTable(viewModel.getFilter());
                }
                case SERIES -> {
                    loadSeries();
                    loadSeriesIntoTable(viewModel.getFilter());
                }
                case GENRES -> loadGenres();
                case COLLECTIONS -> loadCollections();
                case GROUPS -> loadGroups();
                case PUBLISHERS -> loadPublishers();
            }

            UiExecutor.runOnUiThread(() -> {
                navigationTree.refresh();
                log.info("Навігацію оновлено");
                isLoading.set(false);
            });
        } catch (Exception e) {
            log.error("Помилка оновлення навігації", e);
            isLoading.set(false);
        }
    }

    // ==================== ОЧИЩЕННЯ ====================

    @PreDestroy
    public void cleanup() {
        log.info("NavigationController: очищення");
        if (selectionListener != null) {
            navigationTree.getSelectionModel().selectedItemProperty().removeListener(selectionListener);
            selectionListener = null;
        }
    }
}