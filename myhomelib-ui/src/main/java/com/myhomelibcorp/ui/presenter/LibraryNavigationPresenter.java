package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.application.port.out.GroupService;
import com.myhomelibcorp.application.port.out.SeriesService;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.navigation.AuthorNode;
import com.myhomelibcorp.domain.model.navigation.GenreNode;
import com.myhomelibcorp.domain.model.navigation.LibraryNode;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class LibraryNavigationPresenter {

    private final AuthorRepository authorRepository;
    private final SeriesService seriesService;
    private final GenreService genreService;
    private final GroupService groupService;
    private final BackgroundExecutor backgroundExecutor;

    /**
     * Завантажує авторів у дерево та повертає CompletableFuture, щоб можна було виконати дії після завантаження.
     */
    public CompletableFuture<Void> loadAuthors(TreeView<LibraryNode> authorsTree, Consumer<AuthorId> onAuthorSelected) {
        return backgroundExecutor.submit(() -> authorRepository.findAll())
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
        backgroundExecutor.submit(() -> seriesService.getAllSeriesNames())
                .thenAccept(names -> UiExecutor.runOnUiThread(() -> seriesList.setAll(names)))
                .exceptionally(ex -> {
                    log.error("Failed to load series", ex);
                    return null;
                });
    }

    public void loadGenres(TreeView<LibraryNode> genresTree, Consumer<String> onGenreSelected) {
        backgroundExecutor.submit(() -> genreService.getAllGenresHierarchy())
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
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load genres", ex);
                    return null;
                });
    }

    public void loadGroups(ObservableList<Group> groupsList) {
        backgroundExecutor.submit(() -> groupService.getAllGroups())
                .thenAccept(groups -> UiExecutor.runOnUiThread(() -> groupsList.setAll(groups)))
                .exceptionally(ex -> {
                    log.error("Failed to load groups", ex);
                    return null;
                });
    }
}