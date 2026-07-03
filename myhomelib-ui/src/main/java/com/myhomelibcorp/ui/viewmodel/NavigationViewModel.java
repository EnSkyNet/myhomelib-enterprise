package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.usecase.author.LoadAuthorsUseCase;
import com.myhomelibcorp.application.usecase.genre.LoadGenresUseCase;
import com.myhomelibcorp.application.usecase.group.LoadGroupsUseCase;
import com.myhomelibcorp.application.usecase.series.LoadSeriesUseCase;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.ui.model.navigation.AuthorNode;
import com.myhomelibcorp.ui.model.navigation.GenreNode;
import com.myhomelibcorp.ui.model.navigation.LibraryNode;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationViewModel {

    private final LoadAuthorsUseCase loadAuthorsUseCase;
    private final LoadSeriesUseCase loadSeriesUseCase;
    private final LoadGenresUseCase loadGenresUseCase;
    private final LoadGroupsUseCase loadGroupsUseCase;
    private final BackgroundExecutor backgroundExecutor;

    private final ObjectProperty<TreeItem<LibraryNode>> authorsRoot = new SimpleObjectProperty<>();
    private final ObjectProperty<AuthorId> selectedAuthorId = new SimpleObjectProperty<>();
    private final ObservableList<String> seriesNames = FXCollections.observableArrayList();
    private final ObjectProperty<TreeItem<LibraryNode>> genresRoot = new SimpleObjectProperty<>();
    private final ObservableList<Group> groups = FXCollections.observableArrayList();

    // ... гетери ...

    public void loadAuthors() {
        backgroundExecutor.submit(() -> loadAuthorsUseCase.execute())
                .thenAccept(authors -> Platform.runLater(() -> {
                    TreeItem<LibraryNode> root = new TreeItem<>(null);
                    root.setExpanded(true);
                    authors.stream()
                            .sorted(Comparator.comparing(Author::getLastName))
                            .forEach(author -> root.getChildren().add(new TreeItem<>(new AuthorNode(author))));
                    authorsRoot.set(root);
                }))
                .exceptionally(ex -> {
                    log.error("Помилка завантаження авторів", ex);
                    return null;
                });
    }

    public void loadSeries() {
        backgroundExecutor.submit(() -> loadSeriesUseCase.execute())
                .thenAccept(names -> Platform.runLater(() -> seriesNames.setAll(names)))
                .exceptionally(ex -> {
                    log.error("Помилка завантаження серій", ex);
                    return null;
                });
    }

    public void loadGenres() {
        backgroundExecutor.submit(() -> loadGenresUseCase.getAllGenresHierarchy())
                .thenAccept(genres -> Platform.runLater(() -> {
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
                    genresRoot.set(root);
                }))
                .exceptionally(ex -> {
                    log.error("Помилка завантаження жанрів", ex);
                    return null;
                });
    }

    public void loadGroups() {
        backgroundExecutor.submit(() -> loadGroupsUseCase.execute())
                .thenAccept(groupsList -> Platform.runLater(() -> groups.setAll(groupsList)))
                .exceptionally(ex -> {
                    log.error("Помилка завантаження груп", ex);
                    return null;
                });
    }

    public void refreshAll() {
        loadAuthors();
        loadSeries();
        loadGenres();
        loadGroups();
    }
}