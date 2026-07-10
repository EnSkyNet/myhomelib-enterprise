package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.model.navigation.AuthorNode;
import com.myhomelibcorp.ui.model.navigation.LibraryNode;
import com.myhomelibcorp.ui.presenter.LibraryNavigationPresenter;
import com.myhomelibcorp.ui.viewmodel.MainViewModel;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class LibraryReloadService {

    private final MainViewModel mainViewModel;
    private final LibraryNavigationPresenter navigationPresenter;

    public CompletableFuture<Void> reload(
            TreeView<LibraryNode> authorsTree,
            ObservableList<String> seriesList,
            TreeView<LibraryNode> genresTree,
            ObservableList<Group> groupsList,
            Consumer<AuthorId> onAuthorSelected
    ) {
        log.info("🔄 Початок повного перезавантаження бібліотеки...");

        // 1. Очищення кешів та стану
        mainViewModel.clearCaches();

        // 2. Перезавантаження навігації (автори, серії, жанри, групи)
        return navigationPresenter.refreshAll(authorsTree, seriesList, genresTree, groupsList)
                .thenRun(() -> {
                    // 3. Перевіряємо, чи дерево авторів не порожнє
                    UiExecutor.runOnUiThread(() -> {
                        TreeItem<LibraryNode> root = authorsTree.getRoot();
                        if (root != null && !root.getChildren().isEmpty()) {
                            TreeItem<LibraryNode> firstItem = root.getChildren().get(0);
                            if (firstItem.getValue() instanceof AuthorNode) {
                                AuthorId id = ((AuthorNode) firstItem.getValue()).author().getId();
                                log.info("✅ Вибрано першого автора: {}", id.asString());
                                authorsTree.getSelectionModel().select(firstItem);
                                // Завантажуємо книги цього автора
                                onAuthorSelected.accept(id);
                            } else {
                                log.warn("Перший елемент не є AuthorNode, завантажуємо всі книги");
                                mainViewModel.loadAllBooks();
                            }
                        } else {
                            log.warn("Дерево авторів порожнє після перезавантаження. Можливо, немає авторів у БД.");
                            // Якщо немає авторів, завантажуємо всі книги (але це може бути помилкою)
                            mainViewModel.loadAllBooks();
                        }
                    });
                })
                .thenRun(() -> {
                    log.info("✅ Перезавантаження бібліотеки завершено");
                });
    }
}