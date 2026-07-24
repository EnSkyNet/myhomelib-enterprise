package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.controller.MainController;
import com.myhomelibcorp.ui.event.CollectionChangedEvent;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.model.navigation.AuthorNode;
import com.myhomelibcorp.ui.model.navigation.CollectionNode;
import com.myhomelibcorp.ui.model.navigation.GenreNode;
import com.myhomelibcorp.ui.model.navigation.GroupNode;
import com.myhomelibcorp.ui.model.navigation.LibraryNode;
import com.myhomelibcorp.ui.model.navigation.SeriesNode;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.NavigationService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
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

    private final com.myhomelibcorp.application.navigation.NavigationService appNavigationService;
    private final NavigationService uiNavigationService;
    private final BookLoaderService bookLoaderService;
    private final CollectionRepository collectionRepository;
    private final SeriesRepository seriesRepository;
    private final GroupRepository groupRepository;
    private final ApplicationState appState;
    private final MainController mainController;

    @FXML private TreeView<LibraryNode> navigationTree;

    private TreeItem<LibraryNode> authorsItem;
    private TreeItem<LibraryNode> seriesItem;
    private TreeItem<LibraryNode> genresItem;
    private TreeItem<LibraryNode> collectionsItem;
    private TreeItem<LibraryNode> groupsItem;

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

        root.getChildren().addAll(authorsItem, seriesItem, genresItem, collectionsItem, groupsItem);
        navigationTree.setRoot(root);
        navigationTree.setShowRoot(false);

        // Ліниве завантаження
        setupLazyLoading(authorsItem, () -> loadAuthors(authorsItem));
        setupLazyLoading(seriesItem, () -> loadSeries(seriesItem));
        setupLazyLoading(genresItem, () -> loadGenres(genresItem));
        setupLazyLoading(collectionsItem, () -> loadLibraryCollections(collectionsItem));
        setupLazyLoading(groupsItem, () -> loadGroups(groupsItem));

        // Вибір вузла
        navigationTree.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
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
        });
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

    // ==================== ЗАВАНТАЖЕННЯ ====================

    private void loadAuthors(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        appNavigationService.getAllAuthors().thenAccept(authors -> UiExecutor.runOnUiThread(() -> {
            authors.stream()
                    .sorted(Comparator.comparing(a -> a.getLastName()))
                    .forEach(author -> {
                        com.myhomelibcorp.domain.model.author.Author domainAuthor =
                                new com.myhomelibcorp.domain.model.author.Author(
                                        com.myhomelibcorp.domain.model.valueobject.AuthorId.fromString(author.getId()),
                                        author.getFirstName(),
                                        author.getMiddleName(),
                                        author.getLastName()
                                );
                        parent.getChildren().add(new TreeItem<>(new AuthorNode(domainAuthor)));
                    });
            parent.setExpanded(true);
        })).exceptionally(ex -> {
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
        appNavigationService.getAllGenres().thenAccept(genres -> UiExecutor.runOnUiThread(() -> {
            genres.forEach(genre -> {
                com.myhomelibcorp.domain.model.genre.Genre domainGenre =
                        new com.myhomelibcorp.domain.model.genre.Genre(
                                com.myhomelibcorp.domain.model.valueobject.GenreId.fromCode(genre.getCode()),
                                genre.getName(),
                                genre.getParentId() != null ? com.myhomelibcorp.domain.model.valueobject.GenreId.fromCode(genre.getParentId()) : null,
                                genre.getFb2Code()
                        );
                parent.getChildren().add(new TreeItem<>(new GenreNode(domainGenre)));
            });
            parent.setExpanded(true);
        })).exceptionally(ex -> {
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

    // ==================== КНОПКИ ====================

    @FXML private void onHome() { mainController.showDashboard(); }
    @FXML private void onAuthors() { if (authorsItem != null) { authorsItem.setExpanded(true); navigationTree.getSelectionModel().select(authorsItem); } }
    @FXML private void onSeries() { if (seriesItem != null) { seriesItem.setExpanded(true); navigationTree.getSelectionModel().select(seriesItem); } }
    @FXML private void onGenres() { if (genresItem != null) { genresItem.setExpanded(true); navigationTree.getSelectionModel().select(genresItem); } }
    @FXML private void onCollections() { if (collectionsItem != null) { collectionsItem.setExpanded(true); navigationTree.getSelectionModel().select(collectionsItem); } }
    @FXML private void onGroups() { if (groupsItem != null) { groupsItem.setExpanded(true); navigationTree.getSelectionModel().select(groupsItem); } }
    @FXML private void onNewBooks() { bookLoaderService.loadRecentBooks(); }
    @FXML private void onHistory() { mainController.handleHistory(); }
    @FXML private void onSearch() { mainController.showSearchResults(""); }
    @FXML private void onImport() { mainController.showImportWorkspace(); }
    @FXML private void onSettings() { mainController.handleSettings(); }

    // ==================== ОНОВЛЕННЯ ====================

    @EventListener
    public void onCollectionChanged(CollectionChangedEvent event) {
        log.info("Отримано подію зміни колекції: {}", event.collection() != null ? event.collection().getName() : "null");
        refreshNavigation();
    }

    @EventListener
    public void onNavigationRefresh(NavigationRefreshEvent event) {
        log.info("Отримано подію оновлення навігації");
        refreshNavigation();
    }

    public void refreshNavigation() {
        if (isLoading.get()) {
            log.info("Оновлення вже виконується, пропускаємо");
            return;
        }
        isLoading.set(true);
        try {
            log.info("Оновлення навігаційного дерева...");
            clearCategory(authorsItem);
            clearCategory(seriesItem);
            clearCategory(genresItem);
            clearCategory(groupsItem);
            clearCategory(collectionsItem);

            CompletableFuture<Void> all = CompletableFuture.allOf(
                    loadAuthorsAsync(authorsItem),
                    loadSeriesAsync(seriesItem),
                    loadGenresAsync(genresItem),
                    loadGroupsAsync(groupsItem),
                    loadCollectionsAsync(collectionsItem)
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

    private CompletableFuture<Void> loadAuthorsAsync(TreeItem<LibraryNode> parent) {
        return appNavigationService.getAllAuthors()
                .thenAccept(authors -> UiExecutor.runOnUiThread(() -> {
                    parent.getChildren().clear();
                    authors.stream()
                            .sorted(Comparator.comparing(a -> a.getLastName()))
                            .forEach(author -> {
                                com.myhomelibcorp.domain.model.author.Author domainAuthor =
                                        new com.myhomelibcorp.domain.model.author.Author(
                                                com.myhomelibcorp.domain.model.valueobject.AuthorId.fromString(author.getId()),
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
                })
                .thenApply(v -> null);
    }

    private CompletableFuture<Void> loadSeriesAsync(TreeItem<LibraryNode> parent) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Series> seriesList = seriesRepository.findAll();
                UiExecutor.runOnUiThread(() -> {
                    parent.getChildren().clear();
                    seriesList.stream()
                            .sorted(Comparator.comparing(Series::getName))
                            .forEach(series -> {
                                parent.getChildren().add(new TreeItem<>(new SeriesNode(series)));
                            });
                    parent.setExpanded(true);
                });
            } catch (Exception e) {
                log.error("Failed to load series", e);
            }
        });
    }

    private CompletableFuture<Void> loadGenresAsync(TreeItem<LibraryNode> parent) {
        return appNavigationService.getAllGenres()
                .thenAccept(genres -> UiExecutor.runOnUiThread(() -> {
                    parent.getChildren().clear();
                    genres.forEach(genre -> {
                        com.myhomelibcorp.domain.model.genre.Genre domainGenre =
                                new com.myhomelibcorp.domain.model.genre.Genre(
                                        com.myhomelibcorp.domain.model.valueobject.GenreId.fromCode(genre.getCode()),
                                        genre.getName(),
                                        genre.getParentId() != null ? com.myhomelibcorp.domain.model.valueobject.GenreId.fromCode(genre.getParentId()) : null,
                                        genre.getFb2Code()
                                );
                        parent.getChildren().add(new TreeItem<>(new GenreNode(domainGenre)));
                    });
                    parent.setExpanded(true);
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load genres", ex);
                    return null;
                })
                .thenApply(v -> null);
    }

    private CompletableFuture<Void> loadGroupsAsync(TreeItem<LibraryNode> parent) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Group> groups = groupRepository.findAll();
                UiExecutor.runOnUiThread(() -> {
                    parent.getChildren().clear();
                    groups.forEach(group -> {
                        parent.getChildren().add(new TreeItem<>(new GroupNode(group)));
                    });
                    parent.setExpanded(true);
                });
            } catch (Exception e) {
                log.error("Failed to load groups", e);
            }
        });
    }

    private CompletableFuture<Void> loadCollectionsAsync(TreeItem<LibraryNode> parent) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Collection> collections = collectionRepository.findAll();
                UiExecutor.runOnUiThread(() -> {
                    parent.getChildren().clear();
                    collections.forEach(collection -> {
                        parent.getChildren().add(new TreeItem<>(new CollectionNode(collection)));
                    });
                    parent.setExpanded(true);
                });
            } catch (Exception e) {
                log.error("Failed to load library collections", e);
            }
        });
    }
}