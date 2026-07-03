package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.usecase.author.LoadAuthorsUseCase;
import com.myhomelibcorp.application.usecase.genre.LoadGenresUseCase;
import com.myhomelibcorp.application.usecase.group.LoadGroupsUseCase;
import com.myhomelibcorp.application.usecase.series.LoadSeriesUseCase;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.model.navigation.AuthorNode;
import com.myhomelibcorp.ui.model.navigation.GenreNode;
import com.myhomelibcorp.ui.model.navigation.LibraryNode;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class LibraryNavigationPresenter {

    private final LoadAuthorsUseCase loadAuthorsUseCase;
    private final LoadSeriesUseCase loadSeriesUseCase;
    private final LoadGenresUseCase loadGenresUseCase;
    private final LoadGroupsUseCase loadGroupsUseCase;
    private final BackgroundExecutor backgroundExecutor;

    public CompletableFuture<Void> loadAuthors(TreeView<LibraryNode> authorsTree, Consumer<AuthorId> onAuthorSelected) {
        return backgroundExecutor.submit(() -> loadAuthorsUseCase.execute())
                .thenAccept(authors -> UiExecutor.runOnUiThread(() -> {
                    TreeItem<LibraryNode> root = new TreeItem<>(null);
                    root.setExpanded(true);
                    authors.stream()
                            .sorted(Comparator.comparing(Author::getLastName))
                            .forEach(author -> root.getChildren().add(new TreeItem<>(new AuthorNode(author))));
                    authorsTree.setRoot(root);
                    authorsTree.setShowRoot(false);
                    authorsTree.getSelectionModel().selectedItemProperty().addListener(
                            (obs, old, newVal) -> {
                                if (newVal != null && newVal.getValue() instanceof AuthorNode) {
                                    AuthorId id = ((AuthorNode) newVal.getValue()).author().getId();
                                    onAuthorSelected.accept(id);
                                }
                            }
                    );
                }));
    }

    public void loadSeries(ObservableList<String> seriesList) {
        backgroundExecutor.submit(() -> loadSeriesUseCase.execute())
                .thenAccept(names -> UiExecutor.runOnUiThread(() -> seriesList.setAll(names)))
                .exceptionally(ex -> {
                    log.error("Failed to load series", ex);
                    return null;
                });
    }

    public void loadGenres(TreeView<LibraryNode> genresTree, Consumer<String> onGenreSelected) {
        backgroundExecutor.submit(() -> loadGenresUseCase.getAllGenresHierarchy())
                .thenAccept(genres -> UiExecutor.runOnUiThread(() -> {
                    TreeItem<LibraryNode> root = new TreeItem<>(null);
                    root.setExpanded(true);
                    if (!genres.isEmpty()) {
                        Map<String, TreeItem<LibraryNode>> nodeMap = new HashMap<>();
                        for (Genre genre : genres) {
                            nodeMap.put(genre.getId().asString(), new TreeItem<>(new GenreNode(genre)));
                        }
                        for (Genre genre : genres) {
                            TreeItem<LibraryNode> node = nodeMap.get(genre.getId().asString());
                            if (genre.getParentId() != null) {
                                TreeItem<LibraryNode> parent = nodeMap.get(genre.getParentId().asString());
                                if (parent != null) {
                                    parent.getChildren().add(node);
                                } else {
                                    root.getChildren().add(node);
                                }
                            } else {
                                root.getChildren().add(node);
                            }
                        }
                    }
                    genresTree.setRoot(root);
                    genresTree.setShowRoot(false);
                    genresTree.getSelectionModel().selectedItemProperty().addListener(
                            (obs, old, newVal) -> {
                                if (newVal != null && newVal.getValue() instanceof GenreNode) {
                                    Genre genre = ((GenreNode) newVal.getValue()).genre();
                                    onGenreSelected.accept(genre.getId().asString());
                                }
                            }
                    );
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load genres", ex);
                    return null;
                });
    }

    public void loadGroups(ObservableList<Group> groupsList) {
        backgroundExecutor.submit(() -> loadGroupsUseCase.execute())
                .thenAccept(groups -> UiExecutor.runOnUiThread(() -> groupsList.setAll(groups)))
                .exceptionally(ex -> {
                    log.error("Failed to load groups", ex);
                    return null;
                });
    }
}