package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.SeriesService;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.navigation.AuthorNode;
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
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationViewModel {

    private final AuthorRepository authorRepository;
    private final SeriesService seriesService;
    private final BackgroundExecutor backgroundExecutor;

    private final ObjectProperty<TreeItem<LibraryNode>> authorsRoot = new SimpleObjectProperty<>();
    private final ObjectProperty<AuthorId> selectedAuthorId = new SimpleObjectProperty<>();
    private final ObservableList<String> seriesNames = FXCollections.observableArrayList();

    public ObjectProperty<TreeItem<LibraryNode>> authorsRootProperty() {
        return authorsRoot;
    }

    public ObjectProperty<AuthorId> selectedAuthorIdProperty() {
        return selectedAuthorId;
    }

    public ObservableList<String> seriesNamesProperty() {
        return seriesNames;
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
        backgroundExecutor.submit(() -> {
                    log.info("🔍 Виклик seriesService.getAllSeriesNames()...");
                    List<String> names = seriesService.getAllSeriesNames();
                    log.info("✅ Отримано {} назв серій з сервісу", names.size());
                    return names;
                })
                .thenAccept(names -> {
                    log.info("📌 thenAccept отримав {} назв", names.size());
                    Platform.runLater(() -> {
                        log.info("🖥️ Оновлення seriesNames у FX thread. Кількість: {}", names.size());
                        seriesNames.setAll(names);
                        log.info("✅ Після setAll, seriesNames.size() = {}", seriesNames.size());
                        if (!names.isEmpty()) {
                            log.info("📋 Перші 3 серії: {}", names.subList(0, Math.min(3, names.size())));
                        } else {
                            log.warn("⚠️ Список серій порожній!");
                        }
                    });
                })
                .exceptionally(ex -> {
                    log.error("❌ Помилка завантаження серій", ex);
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
    }
}