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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationController {

    private final NavigationService navigationService;
    private final BookLoaderService bookLoaderService;
    private final ApplicationState appState;

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

        TreeItem<LibraryNode> authorsItem = new TreeItem<>(new CategoryNode("👤 Автори", "authors"));
        TreeItem<LibraryNode> seriesItem = new TreeItem<>(new CategoryNode("📚 Серії", "series"));
        TreeItem<LibraryNode> genresItem = new TreeItem<>(new CategoryNode("🏷 Жанри", "genres"));

        root.getChildren().addAll(authorsItem, seriesItem, genresItem);
        navigationTree.setRoot(root);
        navigationTree.setShowRoot(false);

        // Lazy loading для авторів
        authorsItem.setExpanded(true);
        authorsItem.getChildren().add(new TreeItem<LibraryNode>(new PlaceholderNode()));
        authorsItem.addEventHandler(TreeItem.branchExpandedEvent(), event -> {
            TreeItem<LibraryNode> item = event.getTreeItem();
            if (item == authorsItem && item.getChildren().size() == 1
                    && item.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadAuthors(authorsItem);
            }
        });

        // Lazy loading для серій
        seriesItem.getChildren().add(new TreeItem<LibraryNode>(new PlaceholderNode()));
        seriesItem.addEventHandler(TreeItem.branchExpandedEvent(), event -> {
            TreeItem<LibraryNode> item = event.getTreeItem();
            if (item == seriesItem && item.getChildren().size() == 1
                    && item.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadSeries(seriesItem);
            }
        });

        // Lazy loading для жанрів
        genresItem.getChildren().add(new TreeItem<LibraryNode>(new PlaceholderNode()));
        genresItem.addEventHandler(TreeItem.branchExpandedEvent(), event -> {
            TreeItem<LibraryNode> item = event.getTreeItem();
            if (item == genresItem && item.getChildren().size() == 1
                    && item.getChildren().get(0).getValue() instanceof PlaceholderNode) {
                loadGenres(genresItem);
            }
        });

        // Обробка вибору
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
            List<TreeItem<LibraryNode>> items = new ArrayList<>();
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
                        items.add(new TreeItem<LibraryNode>(new AuthorNode(domainAuthor)));
                    });
            parent.getChildren().addAll(items);
            parent.setExpanded(true);
        })).exceptionally(ex -> {
            log.error("Failed to load authors", ex);
            return null;
        });
    }

    private void loadSeries(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        navigationService.getAllSeriesNames().thenAccept(names -> UiExecutor.runOnUiThread(() -> {
            List<TreeItem<LibraryNode>> items = new ArrayList<>();
            names.forEach(name -> {
                com.myhomelibcorp.domain.model.series.Series domainSeries =
                        new com.myhomelibcorp.domain.model.series.Series(
                                com.myhomelibcorp.domain.model.valueobject.SeriesId.generate(),
                                name,
                                null
                        );
                items.add(new TreeItem<LibraryNode>(new SeriesNode(domainSeries)));
            });
            parent.getChildren().addAll(items);
            parent.setExpanded(true);
        })).exceptionally(ex -> {
            log.error("Failed to load series", ex);
            return null;
        });
    }

    private void loadGenres(TreeItem<LibraryNode> parent) {
        parent.getChildren().clear();
        navigationService.getAllGenres().thenAccept(genres -> UiExecutor.runOnUiThread(() -> {
            List<TreeItem<LibraryNode>> items = new ArrayList<>();
            genres.forEach(genre -> {
                com.myhomelibcorp.domain.model.genre.Genre domainGenre =
                        new com.myhomelibcorp.domain.model.genre.Genre(
                                com.myhomelibcorp.domain.model.valueobject.GenreId.fromCode(genre.getCode()),
                                genre.getName(),
                                genre.getParentId() != null ? com.myhomelibcorp.domain.model.valueobject.GenreId.fromCode(genre.getParentId()) : null,
                                genre.getFb2Code()
                        );
                items.add(new TreeItem<LibraryNode>(new GenreNode(domainGenre)));
            });
            parent.getChildren().addAll(items);
            parent.setExpanded(true);
        })).exceptionally(ex -> {
            log.error("Failed to load genres", ex);
            return null;
        });
    }
}