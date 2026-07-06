package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.ui.model.navigation.LibraryNode;
import com.myhomelibcorp.ui.viewmodel.MainViewModel;
import javafx.collections.ObservableList;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RefreshPresenter {

    private final MainViewModel mainViewModel;
    private final StatusBarPresenter statusBarPresenter;
    private final LibraryNavigationPresenter navigationPresenter;

    /**
     * Оновлює всю бібліотеку: книги, авторів, серії, жанри, групи.
     * @param authorsTree дерево авторів
     * @param seriesListView список серій
     * @param genresTree дерево жанрів
     * @param groupsListView список груп
     * @param onComplete колбек після завершення
     */
    public void refreshAll(
            TreeView<LibraryNode> authorsTree,
            ObservableList<String> seriesListView,
            TreeView<LibraryNode> genresTree,
            ObservableList<Group> groupsListView,
            Runnable onComplete
    ) {
        log.info("Оновлення бібліотеки...");
        statusBarPresenter.setStatus("Оновлення...");

        mainViewModel.refreshBooks();

        // Оновлюємо навігацію
        navigationPresenter.loadAuthors(authorsTree, mainViewModel::loadBooksByAuthor)
                .thenRun(() -> {
                    // Після завантаження авторів оновлюємо інші розділи
                    navigationPresenter.loadSeries(seriesListView);
                    navigationPresenter.loadGenres(genresTree, mainViewModel::loadBooksByGenre);
                    navigationPresenter.loadGroups(groupsListView);

                    statusBarPresenter.setStatus("Оновлено");
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
    }
}