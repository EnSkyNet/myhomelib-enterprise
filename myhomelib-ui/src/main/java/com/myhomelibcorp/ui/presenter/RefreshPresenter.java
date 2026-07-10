package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.ui.model.navigation.LibraryNode;
import com.myhomelibcorp.ui.viewmodel.MainViewModel;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class RefreshPresenter {

    private final MainViewModel mainViewModel;
    private final StatusBarPresenter statusBarPresenter;
    private final LibraryNavigationPresenter navigationPresenter;

    /**
     * Оновлює всю бібліотеку та повертає CompletableFuture.
     */
    public CompletableFuture<Void> refreshAll(
            TreeView<LibraryNode> authorsTree,
            ObservableList<String> seriesListView,
            TreeView<LibraryNode> genresTree,
            ObservableList<Group> groupsListView
    ) {
        log.info("Оновлення бібліотеки...");
        statusBarPresenter.setStatus("Оновлення...");

        mainViewModel.refreshBooks();

        return navigationPresenter.refreshAll(authorsTree, seriesListView, genresTree, groupsListView)
                .thenRun(() -> {
                    statusBarPresenter.setStatus("Оновлено");
                    log.info("Оновлення бібліотеки завершено");
                });
    }
}