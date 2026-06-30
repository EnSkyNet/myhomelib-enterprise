package com.myhomelibcorp.ui.viewmodel;

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
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationViewModel {

    private final AuthorRepository authorRepository;
    private final SeriesService seriesService;
    private final GenreService genreService;
    private final GroupService groupService;
    private final BackgroundExecutor backgroundExecutor;

    private final ObjectProperty<TreeItem<LibraryNode>> authorsRoot = new SimpleObjectProperty<>();
    private final ObjectProperty<AuthorId> selectedAuthorId = new SimpleObjectProperty<>();
    private final ObservableList<String> seriesNames = FXCollections.observableArrayList();
    private final ObjectProperty<TreeItem<LibraryNode>> genresRoot = new SimpleObjectProperty<>();
    private final ObservableList<Group> groups = FXCollections.observableArrayList();

    public ObjectProperty<TreeItem<LibraryNode>> authorsRootProperty() {
        return authorsRoot;
    }

    public ObjectProperty<AuthorId> selectedAuthorIdProperty() {
        return selectedAuthorId;
    }

    public ObservableList<String> seriesNamesProperty() {
        return seriesNames;
    }

    public ObjectProperty<TreeItem<LibraryNode>> genresRootProperty() {
        return genresRoot;
    }

    public ObservableList<Group> groupsProperty() {
        return groups;
    }

    public void loadAuthors() {
        log.info("📚 loadAuthors() called");
        backgroundExecutor.submit(() -> authorRepository.findAll())
                .thenAccept(authors -> Platform.runLater(() -> {
                    TreeItem<LibraryNode> root = new TreeItem<>(null);
                    root.setExpanded(true);
                    authors.stream()
                            .sorted(Comparator.comparing(Author::getLastName))
                            .forEach(author -> root.getChildren().add(new TreeItem<>(new AuthorNode(author))));
                    authorsRoot.set(root);
                    log.info("✅ Завантажено {} авторів", authors.size());
                }))
                .exceptionally(ex -> {
                    log.error("❌ Помилка завантаження авторів", ex);
                    return null;
                });
    }

    public void loadSeries() {
        log.info("📚 loadSeries() called");
        backgroundExecutor.submit(() -> seriesService.getAllSeriesNames())
                .thenAccept(names -> Platform.runLater(() -> {
                    seriesNames.setAll(names);
                    log.info("✅ Оновлено серії, розмір: {}", seriesNames.size());
                }))
                .exceptionally(ex -> {
                    log.error("❌ Помилка завантаження серій", ex);
                    return null;
                });
    }

    public void loadGenres() {
        log.info("📚 loadGenres() called");
        backgroundExecutor.submit(() -> genreService.getAllGenresHierarchy())
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
                    genresRoot.set(root);
                    log.info("✅ Дерево жанрів побудовано, вузлів: {}", genres.size());
                }))
                .exceptionally(ex -> {
                    log.error("❌ Помилка завантаження жанрів", ex);
                    return null;
                });
    }

    public void loadGroups() {
        log.info("📚 loadGroups() called");
        backgroundExecutor.submit(() -> groupService.getAllGroups())
                .thenAccept(groupsList -> Platform.runLater(() -> {
                    groups.setAll(groupsList);
                    log.info("✅ Оновлено групи, розмір: {}", groups.size());
                }))
                .exceptionally(ex -> {
                    log.error("❌ Помилка завантаження груп", ex);
                    return null;
                });
    }

    public void selectAuthor(AuthorId authorId) {
        selectedAuthorId.set(authorId);
    }

    public void refreshAll() {
        log.info("🔄 refreshAll() called");
        loadAuthors();
        loadSeries();
        loadGenres();
        loadGroups();
    }
}