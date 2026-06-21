package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.domain.model.navigation.LibraryNode;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.service.NavigationManager;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationController {

    private final NavigationManager navigationManager;

    private TreeView<LibraryNode> authorsTree;
    private ListView<String> seriesListView;
    private ListView<String> genresListView;
    private ListView<String> groupsListView;
    private ListView<String> downloadsListView;

    public void setupNavigation(
            TreeView<LibraryNode> authorsTree,
            ListView<String> seriesListView,
            ListView<String> genresListView,
            ListView<String> groupsListView,
            ListView<String> downloadsListView,
            Consumer<AuthorId> onAuthorSelected
    ) {
        this.authorsTree = authorsTree;
        this.seriesListView = seriesListView;
        this.genresListView = genresListView;
        this.groupsListView = groupsListView;
        this.downloadsListView = downloadsListView;

        // Завантаження авторів з порожнім Runnable (onLoaded)
        navigationManager.loadAuthors(
                authorsTree,
                onAuthorSelected,
                () -> {} // порожній callback після завантаження
        );

        // Налаштування списків
        setupLists();
    }

    private void setupLists() {
        // Серії
        seriesListView.getItems().addAll("Мир Вічного Поляна", "CCC", "Грабитель");
        // Жанри
        genresListView.getItems().addAll("Наукова фантастика", "Детектив", "Історичний", "Фентезі");
        // Групи
        groupsListView.getItems().addAll("Favorites", "To Read", "Мої улюблені");
        // Завантаження
        downloadsListView.getItems().addAll("Завантаження 1", "Завантаження 2");
    }

    public void refreshAuthors() {
        navigationManager.loadAuthors(
                authorsTree,
                id -> {}, // порожній обробник вибору
                () -> {}  // порожній callback
        );
    }

    public ListView<String> getSeriesListView() {
        return seriesListView;
    }

    public ListView<String> getGenresListView() {
        return genresListView;
    }

    public ListView<String> getGroupsListView() {
        return groupsListView;
    }

    public ListView<String> getDownloadsListView() {
        return downloadsListView;
    }
}