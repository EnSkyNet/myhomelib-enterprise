package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.usecase.group.LoadGroupsUseCase;
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

    private final AuthorRepository authorRepository;
    private final GenreRepository genreRepository;
    private final SeriesRepository seriesRepository;
    private final LoadGroupsUseCase loadGroupsUseCase;
    private final BackgroundExecutor backgroundExecutor;

    public CompletableFuture<Void> loadAuthors(TreeView<LibraryNode> authorsTree, Consumer<AuthorId> onAuthorSelected) {
        return backgroundExecutor.submit(() -> authorRepository.findAll())
                .thenAccept(authors -> UiExecutor.runOnUiThread(() -> {
                    TreeItem<LibraryNode> root = new TreeItem<>(null);
                    root.setExpanded(true);
                    authors.stream()
                            .sorted(Comparator.comparing(Author::getLastName))
                            .forEach(author -> {
                                TreeItem<LibraryNode> item = new TreeItem<>(new AuthorNode(author));
                                root.getChildren().add(item);
                            });
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

    public CompletableFuture<Void> refreshAuthors(TreeView<LibraryNode> authorsTree, Consumer<AuthorId> onAuthorSelected) {
        authorsTree.setRoot(null);
        return loadAuthors(authorsTree, onAuthorSelected).thenRun(() -> {
            UiExecutor.runOnUiThread(authorsTree::refresh);
        });
    }

    public CompletableFuture<Void> loadSeries(ObservableList<String> seriesList) {
        return backgroundExecutor.submit(() -> seriesRepository.getAllSeriesNames())
                .thenAccept(names -> UiExecutor.runOnUiThread(() -> seriesList.setAll(names)));
    }

    public CompletableFuture<Void> loadGenres(TreeView<LibraryNode> genresTree, Consumer<String> onGenreSelected) {
        return backgroundExecutor.submit(() -> genreRepository.getAllGenresHierarchy())
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
                                if (parent != null) parent.getChildren().add(node);
                                else root.getChildren().add(node);
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
                }));
    }

    public CompletableFuture<Void> loadGroups(ObservableList<Group> groupsList) {
        return backgroundExecutor.submit(() -> loadGroupsUseCase.execute())
                .thenAccept(groups -> UiExecutor.runOnUiThread(() -> groupsList.setAll(groups)));
    }

    public CompletableFuture<Void> refreshAll(
            TreeView<LibraryNode> authorsTree,
            ObservableList<String> seriesList,
            TreeView<LibraryNode> genresTree,
            ObservableList<Group> groupsList
    ) {
        authorsTree.setRoot(null);
        genresTree.setRoot(null);

        CompletableFuture<Void> authorsFuture = loadAuthors(authorsTree, id -> {});
        CompletableFuture<Void> seriesFuture = loadSeries(seriesList);
        CompletableFuture<Void> genresFuture = loadGenres(genresTree, code -> {});
        CompletableFuture<Void> groupsFuture = loadGroups(groupsList);

        return CompletableFuture.allOf(authorsFuture, seriesFuture, genresFuture, groupsFuture)
                .thenRun(() -> log.info("✅ Навігацію повністю перезавантажено"));
    }
}