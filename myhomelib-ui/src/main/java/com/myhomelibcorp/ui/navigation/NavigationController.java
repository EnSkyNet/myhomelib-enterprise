package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.dto.AuthorDto;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.application.dto.NavigationDataDto;
import com.myhomelibcorp.application.port.out.repository.PublisherRepository;
import com.myhomelibcorp.application.usecase.navigation.LoadNavigationDataUseCase;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
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
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationController {

    private final LoadNavigationDataUseCase loadNavigationDataUseCase;
    private final NavigationService uiNavigationService;
    private final BookLoaderService bookLoaderService;
    private final CollectionRepository collectionRepository;
    private final GroupRepository groupRepository;
    private final SeriesRepository seriesRepository;
    private final PublisherRepository publisherRepository;  // ← ДОДАЄМО
    private final ApplicationState appState;
    private final MainController mainController;

    @FXML private TreeView<LibraryNode> navigationTree;

    private TreeItem<LibraryNode> authorsItem;
    private TreeItem<LibraryNode> seriesItem;
    private TreeItem<LibraryNode> genresItem;
    private TreeItem<LibraryNode> collectionsItem;
    private TreeItem<LibraryNode> groupsItem;
    private TreeItem<LibraryNode> publishersItem;  // ← ДОДАЄМО
    private ChangeListener<TreeItem<LibraryNode>> selectionListener;

    private final AtomicBoolean isLoading = new AtomicBoolean(false);

    private static class PlaceholderNode implements LibraryNode {
        @Override
        public String toString() { return "..."; }
    }

    private static class CategoryNode implements LibraryNode {
        private final String name;
        private final String type;
        CategoryNode(String name, String type) { this.name = name; this.type = type; }
        @Override public String toString() { return name; }
    }

    @FXML
    public void initialize() {
        TreeItem<LibraryNode> root = new TreeItem<>(null);
        root.setExpanded(true);

        authorsItem = new TreeItem<>(new CategoryNode("Автори", "authors"));
        seriesItem = new TreeItem<>(new CategoryNode("Серії", "series"));
        genresItem = new TreeItem<>(new CategoryNode("Жанри", "genres"));
        collectionsItem = new TreeItem<>(new CategoryNode("Колекції (бази даних)", "collections"));
        groupsItem = new TreeItem<>(new CategoryNode("Групи (списки книг)", "groups"));
        publishersItem = new TreeItem<>(new CategoryNode("Видавництва", "publishers"));

        root.getChildren().addAll(authorsItem, seriesItem, genresItem, collectionsItem, groupsItem, publishersItem);
        navigationTree.setRoot(root);
        navigationTree.setShowRoot(false);

        setupLazyLoading(authorsItem, () -> loadAuthors(authorsItem));
        setupLazyLoading(seriesItem, () -> loadSeries(seriesItem));
        setupLazyLoading(genresItem, () -> loadGenres(genresItem));
        setupLazyLoading(collectionsItem, () -> loadLibraryCollections(collectionsItem));
        setupLazyLoading(groupsItem, () -> loadGroups(groupsItem));
        setupLazyLoading(publishersItem, () -> loadPublishers(publishersItem));

        // Зберігаємо слухач для можливості видалення
        selectionListener = (obs, old, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                // ЗАКРИВАЄМО READER ПРИ НАВІГАЦІЇ
                mainController.cleanupReader();

                LibraryNode node = newVal.getValue();
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
                } else if (node instanceof CategoryNode) {
                    CategoryNode cat = (CategoryNode) node;
                    if ("groups".equals(cat.type)) {
                        mainController.showGroupWorkspace(appState.getCurrentGroup());
                    }
                }
            }
        };
        navigationTree.getSelectionModel().selectedItemProperty().addListener(selectionListener);
    }

    // ==================== ЗАВАНТАЖЕННЯ ВИДАВНИЦТВ ====================

    private void loadPublishers(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        try {
            List<Publisher> publishers = publisherRepository.findAll();
            UiExecutor.runOnUiThread(() -> {
                publishers.stream()
                        .sorted(Comparator.comparing(Publisher::getName))
                        .forEach(publisher -> {
                            parent.getChildren().add(new TreeItem<>(new PublisherNode(publisher)));
                        });
                parent.setExpanded(true);
                log.info("Завантажено {} видавництв", publishers.size());
            });
        } catch (Exception e) {
            log.error("Failed to load publishers", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("NavigationController: очищення слухачів");
        if (selectionListener != null) {
            navigationTree.getSelectionModel().selectedItemProperty().removeListener(selectionListener);
            selectionListener = null;
        }
    }

    private void setupLazyLoading(TreeItem<LibraryNode> item, Runnable loader) {
        TreeItem<LibraryNode> placeholder = new TreeItem<>(new PlaceholderNode());
        item.getChildren().add(placeholder);
        item.addEventHandler(TreeItem.<LibraryNode>branchExpandedEvent(), event -> {
            TreeItem<LibraryNode> source = event.getTreeItem();
            if (source == item && source.getChildren().size() == 1
                    && source.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loader.run();
            }
        });
    }

    private void loadAuthors(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        loadNavigationDataUseCase.execute()
                .thenAccept(data -> UiExecutor.runOnUiThread(() -> {
                    data.getAuthors().stream()
                            .sorted(Comparator.comparing(AuthorDto::getLastName))
                            .forEach(author -> {
                                com.myhomelibcorp.domain.model.author.Author domainAuthor =
                                        new com.myhomelibcorp.domain.model.author.Author(
                                                AuthorId.fromString(author.getId()),
                                                author.getFirstName(),
                                                author.getMiddleName(),
                                                author.getLastName()
                                        );
                                parent.getChildren().add(new TreeItem<>(new AuthorNode(domainAuthor)));
                            });
                    parent.setExpanded(true);
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load authors", ex);
                    return null;
                });
    }

    private void loadSeries(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        try {
            List<Series> seriesList = seriesRepository.findAll();
            UiExecutor.runOnUiThread(() -> {
                seriesList.stream()
                        .sorted(Comparator.comparing(Series::getName))
                        .forEach(series -> {
                            parent.getChildren().add(new TreeItem<>(new SeriesNode(series)));
                        });
                parent.setExpanded(true);
                log.info("Завантажено {} серій", seriesList.size());
            });
        } catch (Exception e) {
            log.error("Failed to load series", e);
        }
    }

    private void loadGenres(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        loadNavigationDataUseCase.execute()
                .thenAccept(data -> UiExecutor.runOnUiThread(() -> {
                    data.getGenres().forEach(genre -> {
                        com.myhomelibcorp.domain.model.genre.Genre domainGenre =
                                new com.myhomelibcorp.domain.model.genre.Genre(
                                        GenreId.fromCode(genre.getCode()),
                                        genre.getName(),
                                        genre.getParentId() != null ? GenreId.fromCode(genre.getParentId()) : null,
                                        genre.getFb2Code()
                                );
                        parent.getChildren().add(new TreeItem<>(new GenreNode(domainGenre)));
                    });
                    parent.setExpanded(true);
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load genres", ex);
                    return null;
                });
    }

    private void loadLibraryCollections(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        try {
            List<Collection> collections = collectionRepository.findAll();
            UiExecutor.runOnUiThread(() -> {
                collections.forEach(collection -> {
                    parent.getChildren().add(new TreeItem<>(new CollectionNode(collection)));
                });
                parent.setExpanded(true);
                log.info("Завантажено {} колекцій", collections.size());
            });
        } catch (Exception e) {
            log.error("Failed to load library collections", e);
        }
    }

    private void loadGroups(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        try {
            List<Group> groups = groupRepository.findAll();
            UiExecutor.runOnUiThread(() -> {
                groups.forEach(group -> {
                    parent.getChildren().add(new TreeItem<>(new GroupNode(group)));
                });
                parent.setExpanded(true);
                log.info("Завантажено {} груп", groups.size());
            });
        } catch (Exception e) {
            log.error("Failed to load groups", e);
        }
    }

    // ==================== FXML МЕТОДИ ====================

    @FXML private void onHome() {
        mainController.cleanupReader();
        mainController.showDashboard();
    }

    @FXML private void onAuthors() {
        mainController.cleanupReader();
        if (authorsItem != null) {
            authorsItem.setExpanded(true);
            navigationTree.getSelectionModel().select(authorsItem);
        }
    }

    @FXML private void onSeries() {
        mainController.cleanupReader();
        if (seriesItem != null) {
            seriesItem.setExpanded(true);
            navigationTree.getSelectionModel().select(seriesItem);
        }
    }

    @FXML private void onGenres() {
        mainController.cleanupReader();
        if (genresItem != null) {
            genresItem.setExpanded(true);
            navigationTree.getSelectionModel().select(genresItem);
        }
    }

    @FXML private void onCollections() {
        mainController.cleanupReader();
        if (collectionsItem != null) {
            collectionsItem.setExpanded(true);
            navigationTree.getSelectionModel().select(collectionsItem);
        }
    }

    @FXML private void onGroups() {
        mainController.cleanupReader();
        if (groupsItem != null) {
            groupsItem.setExpanded(true);
            navigationTree.getSelectionModel().select(groupsItem);
        }
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

    // ==================== ПОДІЇ ТА ОНОВЛЕННЯ ====================

    @EventListener
    public void onCollectionChanged(CollectionChangedEvent event) {
        refreshNavigation();
    }

    @EventListener
    public void onNavigationRefresh(NavigationRefreshEvent event) {
        refreshNavigation();
    }

    public void refreshNavigation() {
        if (isLoading.get()) return;
        isLoading.set(true);
        try {
            clearCategory(authorsItem);
            clearCategory(seriesItem);
            clearCategory(genresItem);
            clearCategory(groupsItem);
            clearCategory(collectionsItem);
            clearCategory(publishersItem);

            CompletableFuture<Void> all = CompletableFuture.allOf(
                    loadAuthorsAsync(),
                    loadSeriesAsync(),
                    loadGenresAsync(),
                    loadGroupsAsync(),
                    loadCollectionsAsync(),
                    loadPublishersAsync()
            );

            all.thenRun(() -> {
                UiExecutor.runOnUiThread(() -> {
                    navigationTree.refresh();
                    log.info("Навігаційне дерево оновлено");
                    isLoading.set(false);
                });
            }).exceptionally(ex -> {
                log.error("Помилка оновлення навігації", ex);
                isLoading.set(false);
                return null;
            });
        } catch (Exception e) {
            log.error("Помилка оновлення навігації", e);
            isLoading.set(false);
        }
    }

    private void clearCategory(TreeItem<LibraryNode> item) {
        if (item != null) {
            item.getChildren().clear();
            item.getChildren().add(new TreeItem<>(new PlaceholderNode()));
        }
    }

    private CompletableFuture<Void> loadAuthorsAsync() {
        return loadNavigationDataUseCase.execute()
                .thenAccept(data -> UiExecutor.runOnUiThread(() -> {
                    authorsItem.getChildren().clear();
                    data.getAuthors().stream()
                            .sorted(Comparator.comparing(AuthorDto::getLastName))
                            .forEach(author -> {
                                com.myhomelibcorp.domain.model.author.Author domainAuthor =
                                        new com.myhomelibcorp.domain.model.author.Author(
                                                AuthorId.fromString(author.getId()),
                                                author.getFirstName(),
                                                author.getMiddleName(),
                                                author.getLastName()
                                        );
                                authorsItem.getChildren().add(new TreeItem<>(new AuthorNode(domainAuthor)));
                            });
                    authorsItem.setExpanded(true);
                }));
    }

    private CompletableFuture<Void> loadSeriesAsync() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Series> seriesList = seriesRepository.findAll();
                UiExecutor.runOnUiThread(() -> {
                    seriesItem.getChildren().clear();
                    seriesList.stream()
                            .sorted(Comparator.comparing(Series::getName))
                            .forEach(series -> {
                                seriesItem.getChildren().add(new TreeItem<>(new SeriesNode(series)));
                            });
                    seriesItem.setExpanded(true);
                    log.info("Завантажено {} серій", seriesList.size());
                });
            } catch (Exception e) {
                log.error("Failed to load series async", e);
            }
        });
    }

    private CompletableFuture<Void> loadGenresAsync() {
        return loadNavigationDataUseCase.execute()
                .thenAccept(data -> UiExecutor.runOnUiThread(() -> {
                    genresItem.getChildren().clear();
                    data.getGenres().forEach(genre -> {
                        com.myhomelibcorp.domain.model.genre.Genre domainGenre =
                                new com.myhomelibcorp.domain.model.genre.Genre(
                                        GenreId.fromCode(genre.getCode()),
                                        genre.getName(),
                                        genre.getParentId() != null ? GenreId.fromCode(genre.getParentId()) : null,
                                        genre.getFb2Code()
                                );
                        genresItem.getChildren().add(new TreeItem<>(new GenreNode(domainGenre)));
                    });
                    genresItem.setExpanded(true);
                }));
    }

    private CompletableFuture<Void> loadGroupsAsync() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Group> groups = groupRepository.findAll();
                UiExecutor.runOnUiThread(() -> {
                    groupsItem.getChildren().clear();
                    groups.forEach(group -> {
                        groupsItem.getChildren().add(new TreeItem<>(new GroupNode(group)));
                    });
                    groupsItem.setExpanded(true);
                });
            } catch (Exception e) {
                log.error("Failed to load groups", e);
            }
        });
    }

    private CompletableFuture<Void> loadCollectionsAsync() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Collection> collections = collectionRepository.findAll();
                UiExecutor.runOnUiThread(() -> {
                    collectionsItem.getChildren().clear();
                    collections.forEach(collection -> {
                        collectionsItem.getChildren().add(new TreeItem<>(new CollectionNode(collection)));
                    });
                    collectionsItem.setExpanded(true);
                });
            } catch (Exception e) {
                log.error("Failed to load library collections", e);
            }
        });
    }

    private CompletableFuture<Void> loadPublishersAsync() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Publisher> publishers = publisherRepository.findAll();
                UiExecutor.runOnUiThread(() -> {
                    publishersItem.getChildren().clear();
                    publishers.stream()
                            .sorted(Comparator.comparing(Publisher::getName))
                            .forEach(publisher -> {
                                publishersItem.getChildren().add(new TreeItem<>(new PublisherNode(publisher)));
                            });
                    publishersItem.setExpanded(true);
                    log.info("Завантажено {} видавництв", publishers.size());
                });
            } catch (Exception e) {
                log.error("Failed to load publishers async", e);
            }
        });
    }
}