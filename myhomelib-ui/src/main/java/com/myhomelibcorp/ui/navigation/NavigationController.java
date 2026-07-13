package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.navigation.NavigationService;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.model.navigation.AuthorNode;
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
    private final ApplicationState appState;

    @FXML private TreeView<LibraryNode> navigationTree;

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

        TreeItem<LibraryNode> authorsItem = new TreeItem<>(new CategoryNode("👤 Автори", "authors"));
        TreeItem<LibraryNode> seriesItem = new TreeItem<>(new CategoryNode("📚 Серії", "series"));
        TreeItem<LibraryNode> genresItem = new TreeItem<>(new CategoryNode("🏷 Жанри", "genres"));

        root.getChildren().addAll(authorsItem, seriesItem, genresItem);
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

        // Обробка вибору в дереві
        navigationTree.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                LibraryNode node = newVal.getValue();
                if (node instanceof AuthorNode) {
                    AuthorId id = ((AuthorNode) node).author().getId();
                    bookLoaderService.loadBooksByAuthor(id);
                } else if (node instanceof SeriesNode) {
                    SeriesId id = ((SeriesNode) node).series().getId();
                    bookLoaderService.loadBooksBySeries(id);
                } else if (node instanceof GenreNode) {
                    GenreId id = ((GenreNode) node).genre().getId();
                    bookLoaderService.loadBooksByGenre(id);
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

    // ========== ОБРОБНИКИ КНОПОК НАВІГАЦІЇ ==========

    @FXML
    private void onRecentOpened() {
        log.info("Завантаження останніх відкритих книг");
        bookLoaderService.loadRecentBooks();
    }

    @FXML
    private void onFavorites() {
        log.info("Завантаження обраних книг");
        bookLoaderService.loadFavoriteBooks();
    }

    @FXML
    private void onContinueReading() {
        log.info("Завантаження книг для продовження читання");
        bookLoaderService.loadContinueReading();
    }

    @FXML
    private void onAuthors() {
        log.info("Перехід до авторів");
        TreeItem<LibraryNode> root = navigationTree.getRoot();
        if (root != null && !root.getChildren().isEmpty()) {
            TreeItem<LibraryNode> authorsItem = root.getChildren().get(0);
            authorsItem.setExpanded(true);
            navigationTree.getSelectionModel().select(authorsItem);
            // Якщо ще не завантажені, то завантажимо
            if (authorsItem.getChildren().size() == 1
                    && authorsItem.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadAuthors(authorsItem);
            }
        }
    }

    @FXML
    private void onSeries() {
        log.info("Перехід до серій");
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
        log.info("Перехід до жанрів");
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
    private void onLanguages() {
        log.info("Завантаження книг за мовою (українська)");
        // Можна зробити діалог вибору мови, або завантажити за замовчуванням
        bookLoaderService.loadBooksByLanguage("uk");
    }

    @FXML
    private void onYears() {
        log.info("Завантаження книг за роком (2024)");
        // Аналогічно, можна зробити вибір року
        bookLoaderService.loadBooksByYear(2024);
    }

    @FXML
    private void onPublishers() {
        log.info("Завантаження книг за видавництвом");
        // За замовчуванням завантажуємо всі книги, або можна додати діалог
        bookLoaderService.loadAllBooks();
    }

    @FXML
    private void onCollections() {
        log.info("Завантаження всіх книг (колекції)");
        bookLoaderService.loadAllBooks();
    }

    /**
     * Додатковий метод для примусового оновлення дерева навігації
     */
    public void refreshNavigation() {
        TreeItem<LibraryNode> root = navigationTree.getRoot();
        if (root != null && !root.getChildren().isEmpty()) {
            TreeItem<LibraryNode> authorsItem = root.getChildren().get(0);
            if (authorsItem.getChildren().size() == 1
                    && authorsItem.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadAuthors(authorsItem);
            }
            // Аналогічно для серій та жанрів можна додати
        }
        navigationTree.refresh();
    }
}