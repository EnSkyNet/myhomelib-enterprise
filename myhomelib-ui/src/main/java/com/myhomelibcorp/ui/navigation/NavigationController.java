package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.navigation.NavigationService;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.controller.MainController;
import com.myhomelibcorp.ui.model.navigation.AuthorNode;
import com.myhomelibcorp.ui.model.navigation.CollectionNode;
import com.myhomelibcorp.ui.model.navigation.GenreNode;
import com.myhomelibcorp.ui.model.navigation.LibraryNode;
import com.myhomelibcorp.ui.model.navigation.SeriesNode;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.fxml.FXML;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationController {

    private final NavigationService navigationService;
    private final BookLoaderService bookLoaderService;
    private final CollectionRepository collectionRepository;
    private final ApplicationState appState;
    private final MainController mainController;

    @FXML private TreeView<LibraryNode> navigationTree;

    // Внутрішні класи-маркери
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

        // Категорії в дереві (для швидкого доступу)
        TreeItem<LibraryNode> authorsItem = new TreeItem<>(new CategoryNode("📚 Автори", "authors"));
        TreeItem<LibraryNode> seriesItem = new TreeItem<>(new CategoryNode("📖 Серії", "series"));
        TreeItem<LibraryNode> genresItem = new TreeItem<>(new CategoryNode("🏷 Жанри", "genres"));
        TreeItem<LibraryNode> collectionsItem = new TreeItem<>(new CategoryNode("⭐ Колекції", "collections"));

        root.getChildren().addAll(authorsItem, seriesItem, genresItem, collectionsItem);
        navigationTree.setRoot(root);
        navigationTree.setShowRoot(false);

        // Lazy loading для авторів
        authorsItem.setExpanded(true);
        authorsItem.getChildren().add(new TreeItem<LibraryNode>(new PlaceholderNode()));
        authorsItem.addEventHandler(TreeItem.<LibraryNode>branchExpandedEvent(), event -> {
            TreeItem<LibraryNode> item = event.getTreeItem();
            if (item == authorsItem && item.getChildren().size() == 1
                    && item.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadAuthors(authorsItem);
            }
        });

        // Lazy loading для серій
        seriesItem.getChildren().add(new TreeItem<LibraryNode>(new PlaceholderNode()));
        seriesItem.addEventHandler(TreeItem.<LibraryNode>branchExpandedEvent(), event -> {
            TreeItem<LibraryNode> item = event.getTreeItem();
            if (item == seriesItem && item.getChildren().size() == 1
                    && item.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadSeries(seriesItem);
            }
        });

        // Lazy loading для жанрів
        genresItem.getChildren().add(new TreeItem<LibraryNode>(new PlaceholderNode()));
        genresItem.addEventHandler(TreeItem.<LibraryNode>branchExpandedEvent(), event -> {
            TreeItem<LibraryNode> item = event.getTreeItem();
            if (item == genresItem && item.getChildren().size() == 1
                    && item.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadGenres(genresItem);
            }
        });

        // Lazy loading для колекцій
        collectionsItem.getChildren().add(new TreeItem<LibraryNode>(new PlaceholderNode()));
        collectionsItem.addEventHandler(TreeItem.<LibraryNode>branchExpandedEvent(), event -> {
            TreeItem<LibraryNode> item = event.getTreeItem();
            if (item == collectionsItem && item.getChildren().size() == 1
                    && item.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadCollections(collectionsItem);
            }
        });

        // Обробка вибору в дереві
        navigationTree.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                LibraryNode node = newVal.getValue();
                if (node instanceof AuthorNode) {
                    AuthorId id = ((AuthorNode) node).author().getId();
                    mainController.showAuthorWorkspace(id);
                } else if (node instanceof SeriesNode) {
                    SeriesId id = ((SeriesNode) node).series().getId();
                    mainController.showSeriesWorkspace(id);
                } else if (node instanceof GenreNode) {
                    GenreId id = ((GenreNode) node).genre().getId();
                    mainController.showGenreWorkspace(id);
                } else if (node instanceof CollectionNode) {
                    // Показати колекцію
                }
            }
        });
    }

    private void loadAuthors(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        navigationService.getAllAuthors().thenAccept(authors -> UiExecutor.runOnUiThread(() -> {
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
                        parent.getChildren().add(new TreeItem<LibraryNode>(new AuthorNode(domainAuthor)));
                    });
            parent.setExpanded(true);
        })).exceptionally(ex -> {
            log.error("Failed to load authors", ex);
            return null;
        });
    }

    private void loadSeries(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        navigationService.getAllSeriesNames().thenAccept(names -> UiExecutor.runOnUiThread(() -> {
            names.forEach(name -> {
                com.myhomelibcorp.domain.model.series.Series domainSeries =
                        new com.myhomelibcorp.domain.model.series.Series(
                                com.myhomelibcorp.domain.model.valueobject.SeriesId.generate(),
                                name,
                                null
                        );
                parent.getChildren().add(new TreeItem<LibraryNode>(new SeriesNode(domainSeries)));
            });
            parent.setExpanded(true);
        })).exceptionally(ex -> {
            log.error("Failed to load series", ex);
            return null;
        });
    }

    private void loadGenres(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        navigationService.getAllGenres().thenAccept(genres -> UiExecutor.runOnUiThread(() -> {
            genres.forEach(genre -> {
                com.myhomelibcorp.domain.model.genre.Genre domainGenre =
                        new com.myhomelibcorp.domain.model.genre.Genre(
                                com.myhomelibcorp.domain.model.valueobject.GenreId.fromCode(genre.getCode()),
                                genre.getName(),
                                genre.getParentId() != null ? com.myhomelibcorp.domain.model.valueobject.GenreId.fromCode(genre.getParentId()) : null,
                                genre.getFb2Code()
                        );
                parent.getChildren().add(new TreeItem<LibraryNode>(new GenreNode(domainGenre)));
            });
            parent.setExpanded(true);
        })).exceptionally(ex -> {
            log.error("Failed to load genres", ex);
            return null;
        });
    }

    private void loadCollections(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        try {
            collectionRepository.findAll().forEach(collection -> {
                parent.getChildren().add(new TreeItem<LibraryNode>(new CollectionNode(collection)));
            });
            parent.setExpanded(true);
        } catch (Exception e) {
            log.error("Failed to load collections", e);
        }
    }

    // ========== ОБРОБНИКИ КНОПОК ЛІВОЇ ПАНЕЛІ ==========

    @FXML
    private void onHome() {
        mainController.showDashboard();
    }

    @FXML
    private void onAuthors() {
        TreeItem<LibraryNode> root = navigationTree.getRoot();
        if (root != null && !root.getChildren().isEmpty()) {
            TreeItem<LibraryNode> authorsItem = root.getChildren().get(0);
            authorsItem.setExpanded(true);
            navigationTree.getSelectionModel().select(authorsItem);
            if (authorsItem.getChildren().size() == 1
                    && authorsItem.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadAuthors(authorsItem);
            }
        }
    }

    @FXML
    private void onSeries() {
        TreeItem<LibraryNode> root = navigationTree.getRoot();
        if (root != null && root.getChildren().size() > 1) {
            TreeItem<LibraryNode> seriesItem = root.getChildren().get(1);
            seriesItem.setExpanded(true);
            navigationTree.getSelectionModel().select(seriesItem);
            if (seriesItem.getChildren().size() == 1
                    && seriesItem.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadSeries(seriesItem);
            }
        }
    }

    @FXML
    private void onGenres() {
        TreeItem<LibraryNode> root = navigationTree.getRoot();
        if (root != null && root.getChildren().size() > 2) {
            TreeItem<LibraryNode> genresItem = root.getChildren().get(2);
            genresItem.setExpanded(true);
            navigationTree.getSelectionModel().select(genresItem);
            if (genresItem.getChildren().size() == 1
                    && genresItem.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadGenres(genresItem);
            }
        }
    }

    @FXML
    private void onCollections() {
        TreeItem<LibraryNode> root = navigationTree.getRoot();
        if (root != null && root.getChildren().size() > 3) {
            TreeItem<LibraryNode> collectionsItem = root.getChildren().get(3);
            collectionsItem.setExpanded(true);
            navigationTree.getSelectionModel().select(collectionsItem);
            if (collectionsItem.getChildren().size() == 1
                    && collectionsItem.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadCollections(collectionsItem);
            }
        }
    }

    @FXML
    private void onNewBooks() {
        bookLoaderService.loadRecentBooks();
    }

    @FXML
    private void onHistory() {
        mainController.handleHistory();
    }

    @FXML
    private void onSearch() {
        // Активувати поле пошуку
    }

    @FXML
    private void onImport() {
        mainController.handleImport();
    }


    @FXML
    private void onSettings() {
        mainController.handleSettings();
    }

    /**
     * Оновлює дерево навігації
     */
    public void refreshNavigation() {
        TreeItem<LibraryNode> root = navigationTree.getRoot();
        if (root != null) {
            // Оновлюємо всі категорії з маркерами
            root.getChildren().forEach(item -> {
                if (item.getChildren().size() == 1
                        && item.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                    // Залишаємо маркер, щоб завантажити при розгортанні
                }
            });
        }
        navigationTree.refresh();
    }

}