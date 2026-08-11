package com.myhomelibcorp.ui.navigation;

import com.myhomelibcorp.application.dto.NavigationDataDto;
import com.myhomelibcorp.application.usecase.navigation.LoadNavigationDataUseCase;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.publisher.Publisher;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.ui.model.navigation.*;
import javafx.scene.control.TreeItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Builder for navigation tree items.
 * Creates TreeItem structures for different navigation categories.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationTreeBuilder {

    private final LoadNavigationDataUseCase loadNavigationDataUseCase;
    private final NavigationFilterService filterService;

    // Category nodes
    private static final CategoryNode AUTHORS_CATEGORY = new CategoryNode("Authors", "authors");
    private static final CategoryNode SERIES_CATEGORY = new CategoryNode("Series", "series");
    private static final CategoryNode GENRES_CATEGORY = new CategoryNode("Genres", "genres");
    private static final CategoryNode COLLECTIONS_CATEGORY = new CategoryNode("Collections", "collections");
    private static final CategoryNode GROUPS_CATEGORY = new CategoryNode("Groups", "groups");
    private static final CategoryNode PUBLISHERS_CATEGORY = new CategoryNode("Publishers", "publishers");

    private static final CategoryNode EMPTY_NODE = new CategoryNode("...", "empty");

    /**
     * Build authors tree with filter.
     */
    public TreeItem<LibraryNode> buildAuthorsTree(char filter) {
        TreeItem<LibraryNode> root = new TreeItem<>(AUTHORS_CATEGORY);
        List<Author> authors = filterService.getAuthorsByLetter(filter);

        if (authors.isEmpty()) {
            root.getChildren().add(createEmptyItem(filter, true));
        } else {
            for (Author author : authors) {
                root.getChildren().add(new TreeItem<>(new AuthorNode(author)));
            }
        }

        root.setExpanded(true);
        return root;
    }

    /**
     * Build series tree with filter.
     */
    public TreeItem<LibraryNode> buildSeriesTree(char filter) {
        TreeItem<LibraryNode> root = new TreeItem<>(SERIES_CATEGORY);
        List<Series> series = filterService.getSeriesByLetter(filter);

        if (series.isEmpty()) {
            root.getChildren().add(createEmptyItem(filter, false));
        } else {
            for (Series s : series) {
                root.getChildren().add(new TreeItem<>(new SeriesNode(s)));
            }
        }

        root.setExpanded(true);
        return root;
    }

    /**
     * Build genres tree.
     */
    public TreeItem<LibraryNode> buildGenresTree() {
        TreeItem<LibraryNode> root = new TreeItem<>(GENRES_CATEGORY);

        loadNavigationDataUseCase.execute()
                .thenAccept(data -> {
                    for (com.myhomelibcorp.application.dto.GenreDto dto : data.getGenres()) {
                        Genre genre = new Genre(
                                GenreId.fromCode(dto.getCode()),
                                dto.getName(),
                                dto.getParentId() != null ? GenreId.fromCode(dto.getParentId()) : null,
                                dto.getFb2Code()
                        );
                        root.getChildren().add(new TreeItem<>(new GenreNode(genre)));
                    }
                    root.setExpanded(true);
                })
                .exceptionally(ex -> {
                    log.error("Failed to load genres", ex);
                    return null;
                });

        return root;
    }

    /**
     * Build collections tree.
     */
    public TreeItem<LibraryNode> buildCollectionsTree(List<Collection> collections) {
        TreeItem<LibraryNode> root = new TreeItem<>(COLLECTIONS_CATEGORY);

        if (collections == null || collections.isEmpty()) {
            root.getChildren().add(new TreeItem<>(new CategoryNode("No collections", "empty")));
        } else {
            for (Collection collection : collections) {
                root.getChildren().add(new TreeItem<>(new CollectionNode(collection)));
            }
        }

        root.setExpanded(true);
        return root;
    }

    /**
     * Build groups tree.
     */
    public TreeItem<LibraryNode> buildGroupsTree(List<Group> groups) {
        TreeItem<LibraryNode> root = new TreeItem<>(GROUPS_CATEGORY);

        if (groups == null || groups.isEmpty()) {
            root.getChildren().add(new TreeItem<>(new CategoryNode("No groups", "empty")));
        } else {
            for (Group group : groups) {
                root.getChildren().add(new TreeItem<>(new GroupNode(group)));
            }
        }

        root.setExpanded(true);
        return root;
    }

    /**
     * Build publishers tree.
     */
    public TreeItem<LibraryNode> buildPublishersTree(List<Publisher> publishers) {
        TreeItem<LibraryNode> root = new TreeItem<>(PUBLISHERS_CATEGORY);

        if (publishers == null || publishers.isEmpty()) {
            root.getChildren().add(new TreeItem<>(new CategoryNode("No publishers", "empty")));
        } else {
            publishers.stream()
                    .sorted(Comparator.comparing(Publisher::getName))
                    .forEach(publisher -> {
                        root.getChildren().add(new TreeItem<>(new PublisherNode(publisher)));
                    });
        }

        root.setExpanded(true);
        return root;
    }

    /**
     * Create placeholder item for lazy loading.
     */
    public TreeItem<LibraryNode> createPlaceholder() {
        return new TreeItem<>(EMPTY_NODE);
    }

    /**
     * Create empty state item.
     */
    private TreeItem<LibraryNode> createEmptyItem(char filter, boolean isAuthorMode) {
        String message = filterService.getEmptyMessage(filter, isAuthorMode);
        return new TreeItem<>(new CategoryNode(message, "empty"));
    }

    /**
     * Category node implementation.
     */
    public static class CategoryNode implements LibraryNode {
        private final String name;
        private final String type;

        public CategoryNode(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public String getType() {
            return type;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Get root categories for the tree.
     */
    public List<TreeItem<LibraryNode>> getRootCategories() {
        return List.of(
                new TreeItem<>(AUTHORS_CATEGORY),
                new TreeItem<>(SERIES_CATEGORY),
                new TreeItem<>(GENRES_CATEGORY),
                new TreeItem<>(COLLECTIONS_CATEGORY),
                new TreeItem<>(GROUPS_CATEGORY),
                new TreeItem<>(PUBLISHERS_CATEGORY)
        );
    }
}